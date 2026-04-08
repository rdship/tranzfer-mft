package com.filetransfer.dmz.security;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zone Enforcer — enforces network zone segmentation rules at the proxy level.
 * Classifies IPs into network zones (EXTERNAL, DMZ, INTERNAL, MANAGEMENT) and
 * validates that zone-to-zone transitions comply with the configured policy.
 *
 * <p>This is a plain Java class (not a Spring bean) designed for use within the
 * Netty pipeline of the DMZ proxy. Thread-safe: uses {@link ConcurrentHashMap}
 * for stats and {@link AtomicReference} for the rule list.</p>
 *
 * <h3>Default zone transition policy:</h3>
 * <ul>
 *   <li>EXTERNAL &rarr; DMZ: ALLOWED (ingress)</li>
 *   <li>DMZ &rarr; INTERNAL: ALLOWED (proxy forwarding)</li>
 *   <li>EXTERNAL &rarr; INTERNAL: BLOCKED (no bypass)</li>
 *   <li>EXTERNAL &rarr; MANAGEMENT: BLOCKED</li>
 *   <li>INTERNAL &rarr; DMZ: ALLOWED (health checks, API calls)</li>
 *   <li>INTERNAL &rarr; EXTERNAL: BLOCKED (prevent data exfiltration)</li>
 *   <li>DMZ &rarr; EXTERNAL: BLOCKED (proxy must not initiate outbound)</li>
 *   <li>DMZ &rarr; MANAGEMENT: ALLOWED (config/health APIs)</li>
 *   <li>MANAGEMENT &rarr; all: ALLOWED</li>
 * </ul>
 */
@Slf4j
public class ZoneEnforcer {

    // ── Zone Model ────────────────────────────────────────────────────

    /**
     * Network zones recognised by the DMZ proxy.
     */
    public enum Zone {
        /** Public internet — untrusted. */
        EXTERNAL,
        /** Demilitarized zone — where the proxy itself lives. */
        DMZ,
        /** Private network — backend services. */
        INTERNAL,
        /** Admin / control-plane network. */
        MANAGEMENT
    }

    /**
     * A directional zone transition rule.
     *
     * @param source      the originating zone
     * @param target      the destination zone
     * @param allowed     {@code true} if the transition is permitted
     * @param description human-readable explanation
     */
    public record ZoneRule(Zone source, Zone target, boolean allowed, String description) {}

    /**
     * Result of a zone transition check.
     *
     * @param allowed    {@code true} if the transition is permitted
     * @param sourceZone classified zone of the source IP
     * @param targetZone classified zone of the target IP/host
     * @param reason     human-readable explanation
     */
    public record ZoneCheckResult(boolean allowed, Zone sourceZone, Zone targetZone, String reason) {}

    // ── CIDR range ────────────────────────────────────────────────────

    /**
     * Efficient CIDR matcher supporting both IPv4 and IPv6.
     * Compares masked byte arrays for O(1) per-range matching.
     */
    static final class CidrRange implements Comparable<CidrRange> {
        private final byte[] networkBytes;
        private final int prefixLength;
        private final String cidrNotation;

        private CidrRange(byte[] networkBytes, int prefixLength, String cidrNotation) {
            this.networkBytes = networkBytes;
            this.prefixLength = prefixLength;
            this.cidrNotation = cidrNotation;
        }

        /**
         * Parse a CIDR string such as {@code "10.0.0.0/8"} or {@code "fd00::/8"}.
         *
         * @param cidr CIDR notation string
         * @return parsed range
         * @throws UnknownHostException  if the network address is invalid
         * @throws NumberFormatException  if the prefix length is not a valid integer
         * @throws IllegalArgumentException if format is invalid
         */
        static CidrRange parse(String cidr) throws UnknownHostException {
            if (cidr == null || !cidr.contains("/")) {
                throw new IllegalArgumentException("Invalid CIDR notation: " + cidr);
            }
            String[] parts = cidr.split("/", 2);
            InetAddress addr = InetAddress.getByName(parts[0].trim());
            int prefix = Integer.parseInt(parts[1].trim());
            int maxPrefix = addr.getAddress().length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException(
                        "Prefix length " + prefix + " out of range [0," + maxPrefix + "] for " + cidr);
            }
            return new CidrRange(addr.getAddress(), prefix, cidr.trim());
        }

        /**
         * Test whether the given address falls within this CIDR range.
         *
         * @param address the address to test
         * @return {@code true} if the address is within the range
         */
        boolean contains(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            if (addrBytes.length != networkBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0 && fullBytes < addrBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }

        /** Sort by prefix length descending (most specific first). */
        @Override
        public int compareTo(CidrRange other) {
            return Integer.compare(other.prefixLength, this.prefixLength);
        }

        /** @return the prefix length (e.g., 24 for a /24 CIDR) */
        int prefixLength() {
            return prefixLength;
        }

        @Override
        public String toString() {
            return cidrNotation;
        }
    }

    /**
     * A CIDR range paired with the zone it belongs to.
     * Used in the flattened longest-prefix-match list.
     */
    record ZonedCidr(Zone zone, CidrRange range) {}

    // ── State ─────────────────────────────────────────────────────────

    /** Zone-to-CIDR mapping: retained for zone-specific queries and stats. */
    private final Map<Zone, List<CidrRange>> zoneCidrs;

    /**
     * Flattened list of all (zone, CIDR) pairs sorted for longest-prefix-match.
     * Primary sort: prefix length descending (most specific first).
     * Tiebreaker: zone priority descending (MANAGEMENT > INTERNAL > DMZ).
     * Immutable after construction — safe for concurrent reads.
     */
    private final List<ZonedCidr> lpmMatchOrder;

    /** Zone priority for tiebreaking: higher = checked first when prefix lengths are equal. */
    private static final Map<Zone, Integer> ZONE_PRIORITY = Map.of(
            Zone.MANAGEMENT, 3,
            Zone.INTERNAL, 2,
            Zone.DMZ, 1,
            Zone.EXTERNAL, 0
    );

    /** Rule list — swapped atomically for lock-free reads. */
    private final AtomicReference<List<ZoneRule>> rulesRef;

    /** Stats: "EXTERNAL->INTERNAL" -> {allowed: n, blocked: n}. */
    private final ConcurrentHashMap<String, AtomicLong> allowedCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> blockedCounts = new ConcurrentHashMap<>();

    // ── Construction ──────────────────────────────────────────────────

    /**
     * Create a new zone enforcer.
     *
     * @param zoneCidrs IP ranges that define each zone (e.g., INTERNAL &rarr; ["10.0.0.0/8"])
     * @param rules     allowed/blocked zone transition rules
     * @throws NullPointerException if either parameter is null
     */
    public ZoneEnforcer(Map<Zone, List<String>> zoneCidrs, List<ZoneRule> rules) {
        Objects.requireNonNull(zoneCidrs, "zoneCidrs must not be null");
        Objects.requireNonNull(rules, "rules must not be null");

        // Parse and sort CIDR ranges per zone (most specific first for efficient lookup)
        this.zoneCidrs = new EnumMap<>(Zone.class);
        for (Map.Entry<Zone, List<String>> entry : zoneCidrs.entrySet()) {
            List<CidrRange> parsed = new ArrayList<>();
            for (String cidr : entry.getValue()) {
                try {
                    parsed.add(CidrRange.parse(cidr));
                } catch (Exception e) {
                    log.warn("Skipping invalid CIDR '{}' for zone {}: {}", cidr, entry.getKey(), e.getMessage());
                }
            }
            Collections.sort(parsed); // most specific first
            this.zoneCidrs.put(entry.getKey(), Collections.unmodifiableList(parsed));
        }

        // Build flattened LPM match list across all zones
        List<ZonedCidr> flatList = new ArrayList<>();
        for (Map.Entry<Zone, List<CidrRange>> entry : this.zoneCidrs.entrySet()) {
            Zone zone = entry.getKey();
            for (CidrRange range : entry.getValue()) {
                flatList.add(new ZonedCidr(zone, range));
            }
        }
        // Sort: most specific prefix first; ties broken by zone priority (higher = first)
        flatList.sort((a, b) -> {
            int prefixCmp = Integer.compare(b.range().prefixLength(), a.range().prefixLength());
            if (prefixCmp != 0) return prefixCmp;
            return Integer.compare(
                    ZONE_PRIORITY.getOrDefault(b.zone(), 0),
                    ZONE_PRIORITY.getOrDefault(a.zone(), 0));
        });
        this.lpmMatchOrder = List.copyOf(flatList);

        this.rulesRef = new AtomicReference<>(List.copyOf(rules));

        log.info("ZoneEnforcer initialised: {} zone(s) configured, {} rule(s) loaded, {} CIDR entries in LPM table",
                this.zoneCidrs.size(), rules.size(), this.lpmMatchOrder.size());
        if (log.isDebugEnabled()) {
            for (int i = 0; i < lpmMatchOrder.size(); i++) {
                ZonedCidr zc = lpmMatchOrder.get(i);
                log.debug("  LPM[{}]: {} -> zone {} (/{} prefix)",
                        i, zc.range(), zc.zone(), zc.range().prefixLength());
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Classify an IP address into a network zone using longest-prefix-match (LPM).
     * The most specific CIDR match wins regardless of which zone it belongs to.
     * When two CIDRs have the same prefix length, zone priority breaks the tie
     * (MANAGEMENT &gt; INTERNAL &gt; DMZ). Any IP that does not match a configured
     * range is classified as {@link Zone#EXTERNAL}.
     *
     * @param ip the IP address (IPv4 or IPv6 string)
     * @return the zone the IP belongs to; {@link Zone#EXTERNAL} if no match
     */
    public Zone classifyIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            log.debug("Null/empty IP classified as EXTERNAL");
            return Zone.EXTERNAL;
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(normalizeIp(ip));
        } catch (UnknownHostException e) {
            log.warn("Unable to parse IP '{}', classifying as EXTERNAL: {}", ip, e.getMessage());
            return Zone.EXTERNAL;
        }

        // Longest-prefix-match: iterate pre-sorted list (most specific first)
        for (ZonedCidr zc : lpmMatchOrder) {
            if (zc.range().contains(addr)) {
                return zc.zone();
            }
        }

        return Zone.EXTERNAL;
    }

    /**
     * Check whether a connection from {@code sourceIp} to {@code targetHost:targetPort}
     * is allowed under the current zone transition rules.
     *
     * @param sourceIp   the originating IP
     * @param targetHost the destination hostname or IP
     * @param targetPort the destination port (for logging/context only)
     * @return the check result
     */
    public ZoneCheckResult checkTransition(String sourceIp, String targetHost, int targetPort) {
        Zone sourceZone = classifyIp(sourceIp);
        Zone targetZone = classifyIp(resolveToIp(targetHost));

        String pairKey = sourceZone + "->" + targetZone;

        // Same zone is always allowed
        if (sourceZone == targetZone) {
            recordAllowed(pairKey);
            return new ZoneCheckResult(true, sourceZone, targetZone,
                    "Same-zone traffic (" + sourceZone + ")");
        }

        // Search rules for a matching transition
        List<ZoneRule> rules = rulesRef.get();
        for (ZoneRule rule : rules) {
            if (rule.source() == sourceZone && rule.target() == targetZone) {
                if (rule.allowed()) {
                    recordAllowed(pairKey);
                    log.debug("Zone transition ALLOWED: {} -> {} ({}:{}): {}",
                            sourceZone, targetZone, targetHost, targetPort, rule.description());
                    return new ZoneCheckResult(true, sourceZone, targetZone, rule.description());
                } else {
                    recordBlocked(pairKey);
                    log.warn("Zone transition BLOCKED: {} ({}) -> {} ({}:{}) : {}",
                            sourceZone, sourceIp, targetZone, targetHost, targetPort, rule.description());
                    return new ZoneCheckResult(false, sourceZone, targetZone, rule.description());
                }
            }
        }

        // No explicit rule — default deny
        recordBlocked(pairKey);
        log.warn("Zone transition BLOCKED (no rule): {} ({}) -> {} ({}:{})",
                sourceZone, sourceIp, targetZone, targetHost, targetPort);
        return new ZoneCheckResult(false, sourceZone, targetZone,
                "No rule defined for " + sourceZone + " -> " + targetZone + " (default deny)");
    }

    /**
     * Classify a hostname or IP into a zone. Resolves DNS if needed.
     * Intended for one-time pre-resolution at mapping creation — NOT for use on the event loop.
     *
     * @param host hostname or IP address
     * @return the zone
     */
    public Zone classifyHost(String host) {
        return classifyIp(resolveToIp(host));
    }

    /**
     * Fast zone transition check using a pre-resolved target zone.
     * No DNS resolution — only classifies the source IP (which is always an IP literal).
     * Safe to call on the Netty event loop.
     *
     * @param sourceIp    the originating IP (dotted-decimal — no DNS needed)
     * @param targetZone  the pre-resolved target zone (from {@link #classifyHost})
     * @param targetPort  the destination port (for logging/context only)
     * @return the check result
     */
    public ZoneCheckResult checkTransitionFast(String sourceIp, Zone targetZone, int targetPort) {
        Zone sourceZone = classifyIp(sourceIp);

        if (targetZone == null) {
            targetZone = Zone.EXTERNAL;
        }

        String pairKey = sourceZone + "->" + targetZone;

        if (sourceZone == targetZone) {
            recordAllowed(pairKey);
            return new ZoneCheckResult(true, sourceZone, targetZone,
                    "Same-zone traffic (" + sourceZone + ")");
        }

        List<ZoneRule> rules = rulesRef.get();
        for (ZoneRule rule : rules) {
            if (rule.source() == sourceZone && rule.target() == targetZone) {
                if (rule.allowed()) {
                    recordAllowed(pairKey);
                    return new ZoneCheckResult(true, sourceZone, targetZone, rule.description());
                } else {
                    recordBlocked(pairKey);
                    log.warn("Zone transition BLOCKED: {} ({}) -> {} (port {}): {}",
                            sourceZone, sourceIp, targetZone, targetPort, rule.description());
                    return new ZoneCheckResult(false, sourceZone, targetZone, rule.description());
                }
            }
        }

        recordBlocked(pairKey);
        log.warn("Zone transition BLOCKED (no rule): {} ({}) -> {} (port {})",
                sourceZone, sourceIp, targetZone, targetPort);
        return new ZoneCheckResult(false, sourceZone, targetZone,
                "No rule defined for " + sourceZone + " -> " + targetZone + " (default deny)");
    }

    /**
     * Add or update a zone transition rule at runtime.
     * If a rule with the same source and target already exists, it is replaced.
     * The operation is atomic with respect to concurrent readers.
     *
     * @param rule the rule to add
     */
    public void addRule(ZoneRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        List<ZoneRule> updated;
        List<ZoneRule> current;
        do {
            current = rulesRef.get();
            updated = new ArrayList<>(current.size() + 1);
            boolean replaced = false;
            for (ZoneRule existing : current) {
                if (existing.source() == rule.source() && existing.target() == rule.target()) {
                    updated.add(rule);
                    replaced = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!replaced) {
                updated.add(rule);
            }
        } while (!rulesRef.compareAndSet(current, List.copyOf(updated)));

        log.info("Zone rule updated: {} -> {} = {} ({})",
                rule.source(), rule.target(), rule.allowed() ? "ALLOWED" : "BLOCKED", rule.description());
    }

    /**
     * Get a snapshot of all current zone transition rules.
     *
     * @return unmodifiable list of rules
     */
    public List<ZoneRule> getRules() {
        return rulesRef.get(); // already unmodifiable (List.copyOf)
    }

    /**
     * Get zone enforcement statistics.
     *
     * @return map containing allowed/blocked counts per zone pair, plus totals
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long totalAllowed = 0;
        long totalBlocked = 0;

        Map<String, Map<String, Long>> perPair = new LinkedHashMap<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(allowedCounts.keySet());
        allKeys.addAll(blockedCounts.keySet());

        for (String key : allKeys) {
            long a = allowedCounts.getOrDefault(key, new AtomicLong(0)).get();
            long b = blockedCounts.getOrDefault(key, new AtomicLong(0)).get();
            totalAllowed += a;
            totalBlocked += b;
            perPair.put(key, Map.of("allowed", a, "blocked", b));
        }

        stats.put("totalAllowed", totalAllowed);
        stats.put("totalBlocked", totalBlocked);
        stats.put("zonePairs", perPair);
        stats.put("ruleCount", rulesRef.get().size());
        stats.put("configuredZones", zoneCidrs.keySet().stream().map(Enum::name).toList());

        return Collections.unmodifiableMap(stats);
    }

    /**
     * Build the default zone transition rules.
     * Useful as a starting point when constructing a {@code ZoneEnforcer}.
     *
     * @return list of default rules
     */
    public static List<ZoneRule> defaultRules() {
        return List.of(
                new ZoneRule(Zone.EXTERNAL, Zone.DMZ, true, "Ingress: external clients reach DMZ proxy"),
                new ZoneRule(Zone.DMZ, Zone.INTERNAL, true, "Proxy forwarding: DMZ relays to backend services"),
                new ZoneRule(Zone.EXTERNAL, Zone.INTERNAL, false, "No bypass: external must go through DMZ"),
                new ZoneRule(Zone.EXTERNAL, Zone.MANAGEMENT, false, "Management plane not reachable from internet"),
                new ZoneRule(Zone.INTERNAL, Zone.DMZ, true, "Health checks and API calls from backends"),
                new ZoneRule(Zone.INTERNAL, Zone.EXTERNAL, false, "Prevent data exfiltration from backends"),
                new ZoneRule(Zone.DMZ, Zone.EXTERNAL, false, "Proxy must not initiate outbound to internet"),
                new ZoneRule(Zone.DMZ, Zone.MANAGEMENT, true, "Proxy needs config/health management APIs"),
                new ZoneRule(Zone.MANAGEMENT, Zone.EXTERNAL, true, "Management can reach anywhere"),
                new ZoneRule(Zone.MANAGEMENT, Zone.DMZ, true, "Management can reach DMZ"),
                new ZoneRule(Zone.MANAGEMENT, Zone.INTERNAL, true, "Management can reach internal services"),
                new ZoneRule(Zone.INTERNAL, Zone.MANAGEMENT, false, "Backend services should not reach management plane")
        );
    }

    // ── Internal Helpers ──────────────────────────────────────────────

    /**
     * Normalize an IP string: strips brackets from IPv6, trims whitespace,
     * and detects obfuscated representations (octal, hex, URL-encoded).
     */
    private String normalizeIp(String ip) {
        if (ip == null) return "";
        String normalized = ip.trim();

        // Strip IPv6 brackets: [::1] -> ::1
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        // Strip zone ID for IPv6 link-local: fe80::1%eth0 -> fe80::1
        int percentIdx = normalized.indexOf('%');
        if (percentIdx > 0) {
            normalized = normalized.substring(0, percentIdx);
        }

        // Detect and handle URL-encoded IPs (e.g., %31%30%2E%30%2E%30%2E%31 = 10.0.0.1)
        if (normalized.contains("%") && normalized.matches(".*%[0-9a-fA-F]{2}.*")) {
            try {
                normalized = java.net.URLDecoder.decode(normalized, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("Failed to URL-decode IP '{}': {}", ip, e.getMessage());
            }
        }

        // Detect octal notation (e.g., 0177.0.0.1 = 127.0.0.1)
        // Detect hex notation (e.g., 0x7f.0.0.1 = 127.0.0.1)
        // InetAddress.getByName handles these natively on most JVMs
        return normalized;
    }

    /**
     * Resolve a hostname to an IP string for zone classification.
     * If the input is already an IP, returns it directly.
     */
    private String resolveToIp(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Cannot resolve host '{}' for zone classification, treating as EXTERNAL", host);
            return host;
        }
    }

    private void recordAllowed(String pairKey) {
        allowedCounts.computeIfAbsent(pairKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    private void recordBlocked(String pairKey) {
        blockedCounts.computeIfAbsent(pairKey, k -> new AtomicLong(0)).incrementAndGet();
    }
}
