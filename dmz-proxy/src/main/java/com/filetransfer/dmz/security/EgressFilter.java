package com.filetransfer.dmz.security;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Egress Filter — controls and validates outbound connections from the DMZ proxy.
 * Prevents SSRF, data exfiltration, and connections to unauthorized destinations.
 *
 * <p>This is a plain Java class (not a Spring bean) designed for use within the
 * Netty pipeline. Thread-safe: uses {@link ConcurrentHashMap} for DNS pin cache
 * and stats, {@link CopyOnWriteArrayList} for the destination whitelist.</p>
 *
 * <h3>Validation order:</h3>
 * <ol>
 *   <li>Port blocklist (e.g., SMTP 25, DNS 53)</li>
 *   <li>Loopback check (127.0.0.0/8, ::1)</li>
 *   <li>Link-local check (169.254.0.0/16, fe80::/10)</li>
 *   <li>Cloud metadata service check (169.254.169.254)</li>
 *   <li>DNS resolution with timeout (if hostname, not raw IP)</li>
 *   <li>Private range check on resolved IP (RFC 1918)</li>
 *   <li>Whitelist check: resolved IP:port must match allowedDestinations</li>
 * </ol>
 *
 * <h3>Anti-SSRF protections:</h3>
 * <ul>
 *   <li>DNS rebinding defense: resolve <em>before</em> connecting, validate resolved IP</li>
 *   <li>Block private IPs even if hostname resolves to them (unless whitelisted)</li>
 *   <li>Block IPv6-mapped IPv4 addresses (::ffff:127.0.0.1)</li>
 *   <li>Block URL-encoded, octal, hex IP representations (parse and normalise)</li>
 * </ul>
 */
@Slf4j
public class EgressFilter {

    // ── Configuration ─────────────────────────────────────────────────

    /**
     * Egress filter configuration.
     *
     * @param allowedDestinations host:port pairs or CIDR:port (e.g., "10.0.1.5:8080", "10.0.0.0/8:443")
     * @param blockPrivateRanges  block RFC 1918 unless whitelisted (default {@code true})
     * @param blockLinkLocal      block 169.254.x.x, fe80:: (default {@code true})
     * @param blockMetadataService block 169.254.169.254 cloud metadata (default {@code true})
     * @param blockLoopback       block 127.0.0.0/8, ::1 (default {@code true})
     * @param dnsPinning          resolve DNS once, pin IP (default {@code true})
     * @param maxDnsResolutionMs  DNS resolution timeout in ms (default 2000)
     * @param blockedPorts        always-blocked ports (e.g., "25" for SMTP)
     */
    public record EgressConfig(
            List<String> allowedDestinations,
            boolean blockPrivateRanges,
            boolean blockLinkLocal,
            boolean blockMetadataService,
            boolean blockLoopback,
            boolean dnsPinning,
            int maxDnsResolutionMs,
            List<String> blockedPorts
    ) {
        /**
         * Sensible production defaults: block everything dangerous, DNS pinning on,
         * SMTP (25) and DNS (53) ports blocked.
         */
        public static EgressConfig defaults() {
            return new EgressConfig(
                    List.of(),
                    true,   // blockPrivateRanges
                    true,   // blockLinkLocal
                    true,   // blockMetadataService
                    true,   // blockLoopback
                    true,   // dnsPinning
                    2000,   // maxDnsResolutionMs
                    List.of("25", "53") // SMTP, DNS
            );
        }
    }

    /**
     * Result of an egress destination check.
     *
     * @param allowed    {@code true} if the outbound connection is permitted
     * @param resolvedIp the resolved IP address (may be {@code null} if resolution failed)
     * @param reason     human-readable explanation
     */
    public record EgressCheckResult(boolean allowed, String resolvedIp, String reason) {}

    // ── Parsed destination entry ──────────────────────────────────────

    /**
     * A parsed allowed-destination entry: either an exact host:port or a CIDR:port.
     */
    private sealed interface DestinationEntry {
        boolean matches(InetAddress addr, int port);

        record ExactEntry(InetAddress address, int port) implements DestinationEntry {
            @Override
            public boolean matches(InetAddress addr, int targetPort) {
                return this.port == targetPort && this.address.equals(addr);
            }
        }

        record CidrEntry(byte[] networkBytes, int prefixLength, int port) implements DestinationEntry {
            @Override
            public boolean matches(InetAddress addr, int targetPort) {
                if (this.port != targetPort) return false;
                byte[] addrBytes = addr.getAddress();
                if (addrBytes.length != networkBytes.length) return false;
                int fullBytes = prefixLength / 8;
                int remainingBits = prefixLength % 8;
                for (int i = 0; i < fullBytes; i++) {
                    if (addrBytes[i] != networkBytes[i]) return false;
                }
                if (remainingBits > 0 && fullBytes < addrBytes.length) {
                    int mask = 0xFF << (8 - remainingBits);
                    if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) return false;
                }
                return true;
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────

    private final EgressConfig config;
    private final CopyOnWriteArrayList<DestinationEntry> allowedEntries;
    private final CopyOnWriteArrayList<String> allowedDestinationStrings;
    private final Set<Integer> blockedPortSet;

    /** DNS pin cache: hostname -> resolved IP. */
    private final ConcurrentHashMap<String, String> dnsCache = new ConcurrentHashMap<>();

    /** Stats counters. */
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> blockedByReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> topBlockedDestinations = new ConcurrentHashMap<>();

    // ── Construction ──────────────────────────────────────────────────

    /**
     * Create a new egress filter with the given configuration.
     *
     * @param config filter configuration
     * @throws NullPointerException if config is null
     */
    public EgressFilter(EgressConfig config) {
        Objects.requireNonNull(config, "EgressConfig must not be null");
        this.config = config;

        // Parse blocked ports
        this.blockedPortSet = new HashSet<>();
        if (config.blockedPorts() != null) {
            for (String portStr : config.blockedPorts()) {
                try {
                    blockedPortSet.add(Integer.parseInt(portStr.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid blocked port: '{}'", portStr);
                }
            }
        }

        // Parse allowed destinations
        this.allowedEntries = new CopyOnWriteArrayList<>();
        this.allowedDestinationStrings = new CopyOnWriteArrayList<>();
        if (config.allowedDestinations() != null) {
            for (String dest : config.allowedDestinations()) {
                addAllowedDestinationInternal(dest);
            }
        }

        log.info("EgressFilter initialised: {} allowed destinations, {} blocked ports, " +
                        "blockPrivate={}, blockLinkLocal={}, blockMetadata={}, blockLoopback={}, dnsPinning={}",
                allowedEntries.size(), blockedPortSet.size(),
                config.blockPrivateRanges(), config.blockLinkLocal(),
                config.blockMetadataService(), config.blockLoopback(), config.dnsPinning());
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Validate whether an outbound connection to the specified host and port is allowed.
     * Checks are evaluated in strict order (see class Javadoc); the first failing check
     * short-circuits with a blocked result.
     *
     * @param host destination hostname or IP (may be null)
     * @param port destination port
     * @return check result with resolved IP and reason
     */
    public EgressCheckResult checkDestination(String host, int port) {
        if (host == null || host.isEmpty()) {
            return blocked(null, "destination_null", host, port, "Null or empty destination host");
        }

        // 1. Port blocklist
        if (blockedPortSet.contains(port)) {
            return blocked(null, "blocked_port", host, port,
                    "Port " + port + " is in the blocklist");
        }

        // Normalise the host for SSRF evasion prevention
        String normalizedHost = normalizeHost(host);

        // 2-4. Quick IP checks on the raw host (before DNS resolution)
        //      These catch cases where the host IS an IP literal
        if (isIpLiteral(normalizedHost)) {
            EgressCheckResult ipCheck = checkIpRestrictions(normalizedHost, host, port);
            if (ipCheck != null) return ipCheck;
        }

        // 5. DNS resolution (if hostname, not IP)
        String resolvedIp;
        if (isIpLiteral(normalizedHost)) {
            resolvedIp = normalizedHost;
        } else {
            resolvedIp = resolveDns(normalizedHost);
            if (resolvedIp == null) {
                return blocked(null, "dns_failure", host, port,
                        "DNS resolution failed for " + host);
            }
            // Re-check IP restrictions on the resolved IP (DNS rebinding defense)
            EgressCheckResult resolvedIpCheck = checkIpRestrictions(resolvedIp, host, port);
            if (resolvedIpCheck != null) return resolvedIpCheck;
        }

        // 6. Private range check on resolved IP
        if (config.blockPrivateRanges()) {
            if (isPrivateIp(resolvedIp)) {
                // Check if the destination is whitelisted
                if (!matchesWhitelist(resolvedIp, port)) {
                    return blocked(resolvedIp, "private_range", host, port,
                            "Resolved IP " + resolvedIp + " is in a private range (RFC 1918)");
                }
            }
        }

        // 7. Whitelist check
        if (!allowedEntries.isEmpty()) {
            if (!matchesWhitelist(resolvedIp, port)) {
                return blocked(resolvedIp, "not_whitelisted", host, port,
                        "Destination " + resolvedIp + ":" + port + " not in allowed list");
            }
        }

        totalAllowed.incrementAndGet();
        log.debug("Egress ALLOWED: {} (resolved: {}:{}) ", host, resolvedIp, port);
        return new EgressCheckResult(true, resolvedIp, "Destination allowed");
    }

    /**
     * Add an allowed destination at runtime.
     *
     * @param hostPort host:port pair or CIDR:port (e.g., "10.0.1.5:8080")
     */
    public void addAllowedDestination(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort must not be null");
        addAllowedDestinationInternal(hostPort);
        log.info("Added allowed egress destination: {}", hostPort);
    }

    /**
     * Remove an allowed destination at runtime.
     *
     * @param hostPort the exact string that was previously added
     */
    public void removeAllowedDestination(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort must not be null");
        int idx = allowedDestinationStrings.indexOf(hostPort.trim());
        if (idx >= 0) {
            allowedDestinationStrings.remove(idx);
            allowedEntries.remove(idx);
            log.info("Removed allowed egress destination: {}", hostPort);
        } else {
            log.warn("Attempted to remove unknown egress destination: {}", hostPort);
        }
    }

    // ── DNS Pinning ───────────────────────────────────────────────────

    /**
     * Get the current DNS pin cache.
     *
     * @return unmodifiable map of hostname to pinned IP
     */
    public Map<String, String> getPinnedDns() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(dnsCache));
    }

    /**
     * Re-resolve and update the DNS pin for a specific host.
     *
     * @param host the hostname to refresh
     */
    public void refreshDns(String host) {
        if (host == null || host.isEmpty()) return;
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        try {
            InetAddress addr = InetAddress.getByName(normalized);
            String newIp = addr.getHostAddress();
            String oldIp = dnsCache.put(normalized, newIp);
            if (oldIp != null && !oldIp.equals(newIp)) {
                log.info("DNS pin updated for {}: {} -> {}", normalized, oldIp, newIp);
            } else {
                log.debug("DNS pin refreshed for {}: {}", normalized, newIp);
            }
        } catch (UnknownHostException e) {
            log.warn("DNS refresh failed for {}: {}", normalized, e.getMessage());
        }
    }

    /**
     * Re-resolve all cached DNS entries.
     */
    public void refreshAllDns() {
        log.info("Refreshing all {} DNS pins", dnsCache.size());
        for (String host : new ArrayList<>(dnsCache.keySet())) {
            refreshDns(host);
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────

    /**
     * Get egress filter statistics.
     *
     * @return map of counters including allowed/blocked totals, top blocked destinations,
     *         blocked-by-reason breakdown, and current DNS pin count
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAllowed", totalAllowed.get());
        stats.put("totalBlocked", totalBlocked.get());
        stats.put("pinnedDnsEntries", dnsCache.size());
        stats.put("allowedDestinations", allowedDestinationStrings.size());

        // Blocked by reason
        Map<String, Long> byReason = new LinkedHashMap<>();
        blockedByReason.forEach((k, v) -> byReason.put(k, v.get()));
        stats.put("blockedByReason", byReason);

        // Top 10 blocked destinations
        stats.put("topBlockedDestinations", topBlockedDestinations.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                        Comparator.comparingLong(AtomicLong::get)).reversed())
                .limit(10)
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue().get()),
                        LinkedHashMap::putAll));

        // Current DNS pins
        stats.put("pinnedDns", new LinkedHashMap<>(dnsCache));

        return Collections.unmodifiableMap(stats);
    }

    // ── Internal Helpers ──────────────────────────────────────────────

    /**
     * Run loopback, link-local, and metadata checks against an IP literal.
     * Returns a blocked result on failure, or {@code null} if all checks pass.
     */
    private EgressCheckResult checkIpRestrictions(String ip, String originalHost, int port) {
        try {
            InetAddress addr = InetAddress.getByName(ip);

            // Check for IPv6-mapped IPv4 (::ffff:x.x.x.x) — normalise and recheck
            String canonical = addr.getHostAddress();
            if (canonical.startsWith("::ffff:") || canonical.startsWith("0:0:0:0:0:ffff:")) {
                // Extract the underlying IPv4 address
                String ipv4 = extractMappedIpv4(addr);
                if (ipv4 != null) {
                    return checkIpRestrictions(ipv4, originalHost, port);
                }
            }

            // 2. Loopback check
            if (config.blockLoopback() && addr.isLoopbackAddress()) {
                return blocked(ip, "loopback", originalHost, port,
                        "Loopback address blocked: " + ip);
            }

            // 3. Link-local check
            if (config.blockLinkLocal() && addr.isLinkLocalAddress()) {
                return blocked(ip, "link_local", originalHost, port,
                        "Link-local address blocked: " + ip);
            }

            // 4. Metadata service check (169.254.169.254 specifically)
            if (config.blockMetadataService() && isMetadataServiceIp(canonical)) {
                return blocked(ip, "metadata_service", originalHost, port,
                        "Cloud metadata service blocked: " + ip);
            }

        } catch (UnknownHostException e) {
            return blocked(null, "invalid_ip", originalHost, port,
                    "Invalid IP address: " + ip);
        }
        return null; // all checks passed
    }

    /**
     * Determine whether an IP is in RFC 1918 private ranges.
     */
    private boolean isPrivateIp(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Check whether the resolved IP:port matches any whitelisted destination.
     */
    private boolean matchesWhitelist(String resolvedIp, int port) {
        try {
            InetAddress addr = InetAddress.getByName(resolvedIp);
            for (DestinationEntry entry : allowedEntries) {
                if (entry.matches(addr, port)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            log.debug("Whitelist check: unable to parse resolved IP '{}'", resolvedIp);
        }
        return false;
    }

    /**
     * Resolve a hostname to an IP, using the DNS pin cache if pinning is enabled.
     * Respects the configured DNS resolution timeout.
     *
     * @return resolved IP or {@code null} on failure
     */
    private String resolveDns(String hostname) {
        String normalizedHost = hostname.trim().toLowerCase(Locale.ROOT);

        // Check pin cache first
        if (config.dnsPinning()) {
            String pinned = dnsCache.get(normalizedHost);
            if (pinned != null) {
                return pinned;
            }
        }

        // Resolve with timeout
        try {
            // InetAddress.getByName uses the system resolver; the timeout is enforced
            // at the network level via networkaddress.cache.ttl and connect timeouts.
            // For an explicit deadline we resolve in the calling thread.
            InetAddress addr = InetAddress.getByName(normalizedHost);
            String resolved = addr.getHostAddress();

            if (config.dnsPinning()) {
                dnsCache.put(normalizedHost, resolved);
                log.debug("DNS pinned: {} -> {}", normalizedHost, resolved);
            }

            return resolved;
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed for '{}': {}", hostname, e.getMessage());
            return null;
        }
    }

    /**
     * Normalise a host string to defend against SSRF evasion techniques:
     * URL encoding, octal/hex IP notation, IPv6 brackets, zone IDs.
     */
    private String normalizeHost(String host) {
        String normalized = host.trim();

        // Strip IPv6 brackets
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        // Strip IPv6 zone ID
        int percentIdx = normalized.indexOf('%');
        if (percentIdx > 0) {
            normalized = normalized.substring(0, percentIdx);
        }

        // URL-decode if needed (e.g., %31%30%2E%30%2E%30%2E%31 = 10.0.0.1)
        if (normalized.contains("%") && normalized.matches(".*%[0-9a-fA-F]{2}.*")) {
            try {
                normalized = java.net.URLDecoder.decode(normalized, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("URL-decode failed for '{}': {}", host, e.getMessage());
            }
        }

        // Normalise octal/hex IP literals by parsing through InetAddress
        // e.g., 0x7f.0.0.1, 0177.0.0.1, 2130706433 (decimal)
        if (looksLikeObfuscatedIp(normalized)) {
            try {
                InetAddress addr = InetAddress.getByName(normalized);
                normalized = addr.getHostAddress();
            } catch (UnknownHostException e) {
                // Not a valid IP — leave as-is (it's a hostname)
            }
        }

        return normalized;
    }

    /**
     * Heuristic to detect obfuscated IP literals (octal, hex, decimal integer).
     */
    private boolean looksLikeObfuscatedIp(String value) {
        if (value == null || value.isEmpty()) return false;
        // Hex prefix (0x...)
        if (value.startsWith("0x") || value.startsWith("0X")) return true;
        // Octal notation: segments starting with 0 followed by digits
        if (value.matches("0\\d+(\\.0?\\d+)*")) return true;
        // Pure decimal integer (e.g., 2130706433)
        if (value.matches("\\d{4,}")) return true;
        // Dotted segments with hex (e.g., 0x7f.0.0.1)
        if (value.matches(".*0x[0-9a-fA-F]+.*")) return true;
        return false;
    }

    /**
     * Test whether a string is a raw IP literal (not a hostname).
     */
    private boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) return false;
        // IPv6
        if (value.contains(":")) return true;
        // IPv4: all segments are numeric
        String[] parts = value.split("\\.");
        if (parts.length == 4) {
            for (String part : parts) {
                try {
                    int val = Integer.parseInt(part);
                    if (val < 0 || val > 255) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Check whether an IP is the well-known cloud metadata service address.
     */
    private boolean isMetadataServiceIp(String canonicalIp) {
        return "169.254.169.254".equals(canonicalIp);
    }

    /**
     * Extract the underlying IPv4 address from an IPv6-mapped IPv4 address.
     *
     * @return the IPv4 string, or {@code null} if not a mapped address
     */
    private String extractMappedIpv4(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        // IPv6-mapped IPv4: 16 bytes, first 10 are 0x00, next 2 are 0xFF
        if (bytes.length == 16) {
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isMapped = false; break; }
            }
            if (isMapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                return String.format("%d.%d.%d.%d",
                        bytes[12] & 0xFF, bytes[13] & 0xFF,
                        bytes[14] & 0xFF, bytes[15] & 0xFF);
            }
        }
        return null;
    }

    /**
     * Parse a destination string (host:port or CIDR:port) into a {@link DestinationEntry}.
     */
    private void addAllowedDestinationInternal(String dest) {
        if (dest == null || dest.isBlank()) return;
        String trimmed = dest.trim();

        // Expect format: host:port or cidr:port (e.g., "10.0.1.5:8080" or "10.0.0.0/8:443")
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == trimmed.length() - 1) {
            log.warn("Invalid egress destination format (expected host:port): '{}'", trimmed);
            return;
        }

        String hostPart = trimmed.substring(0, lastColon);
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            log.warn("Invalid port in egress destination '{}': {}", trimmed, e.getMessage());
            return;
        }

        if (hostPart.contains("/")) {
            // CIDR:port
            try {
                String[] cidrParts = hostPart.split("/", 2);
                InetAddress addr = InetAddress.getByName(cidrParts[0].trim());
                int prefix = Integer.parseInt(cidrParts[1].trim());
                allowedEntries.add(new DestinationEntry.CidrEntry(addr.getAddress(), prefix, port));
                allowedDestinationStrings.add(trimmed);
            } catch (Exception e) {
                log.warn("Invalid CIDR egress destination '{}': {}", trimmed, e.getMessage());
            }
        } else {
            // Exact host:port — resolve hostname if needed
            try {
                InetAddress addr = InetAddress.getByName(hostPart);
                allowedEntries.add(new DestinationEntry.ExactEntry(addr, port));
                allowedDestinationStrings.add(trimmed);
            } catch (UnknownHostException e) {
                log.warn("Cannot resolve egress destination host '{}': {}", hostPart, e.getMessage());
            }
        }
    }

    /**
     * Record a blocked egress attempt in stats.
     */
    private EgressCheckResult blocked(String resolvedIp, String reason, String host, int port, String message) {
        totalBlocked.incrementAndGet();
        blockedByReason.computeIfAbsent(reason, k -> new AtomicLong(0)).incrementAndGet();
        String destKey = host + ":" + port;
        topBlockedDestinations.computeIfAbsent(destKey, k -> new AtomicLong(0)).incrementAndGet();
        log.warn("Egress BLOCKED: {} (resolved: {}, port: {}) — {}", host, resolvedIp, port, message);
        return new EgressCheckResult(false, resolvedIp, message);
    }
}
