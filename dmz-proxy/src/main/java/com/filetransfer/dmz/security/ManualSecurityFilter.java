package com.filetransfer.dmz.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import com.filetransfer.dmz.proxy.PortMapping;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure Java manual security filter — no Spring, no network calls.
 * Created once per mapping, shared across all connections on that port.
 * Thread-safe: all state is immutable after construction.
 *
 * Evaluates IP whitelist/blacklist (exact + CIDR), geo restrictions,
 * file extension rules, transfer windows, and encryption requirements.
 */
@Slf4j
public class ManualSecurityFilter {

    public record FilterResult(boolean allowed, String action, String reason) {
        public static FilterResult allow() { return new FilterResult(true, "ALLOW", "Passed manual security checks"); }
        public static FilterResult block(String reason) { return new FilterResult(false, "BLOCK", reason); }
    }

    // Pre-computed data structures for O(1) lookups
    private final Set<String> exactWhitelist;      // exact IPs
    private final List<CidrRange> cidrWhitelist;   // CIDR ranges
    private final Set<String> exactBlacklist;
    private final List<CidrRange> cidrBlacklist;
    private final boolean hasWhitelist;             // if true, only whitelisted IPs allowed
    private final Set<String> geoAllowed;           // empty = all allowed
    private final Set<String> geoBlocked;
    private final Set<String> allowedExtensions;    // empty = all allowed
    private final Set<String> blockedExtensions;
    private final boolean requireEncryption;
    private final List<TransferWindow> transferWindows; // empty = 24/7
    private final PortMapping.SecurityPolicy policy;

    public ManualSecurityFilter(PortMapping.SecurityPolicy policy) {
        this.policy = policy;

        // Parse IP whitelist
        var whitelistParsed = parseIpList(policy.getIpWhitelist());
        this.exactWhitelist = whitelistParsed.exactIps;
        this.cidrWhitelist = whitelistParsed.cidrRanges;
        this.hasWhitelist = !exactWhitelist.isEmpty() || !cidrWhitelist.isEmpty();

        // Parse IP blacklist
        var blacklistParsed = parseIpList(policy.getIpBlacklist());
        this.exactBlacklist = blacklistParsed.exactIps;
        this.cidrBlacklist = blacklistParsed.cidrRanges;

        // Geo
        this.geoAllowed = new HashSet<>(policy.getGeoAllowedCountries() != null ? policy.getGeoAllowedCountries() : List.of());
        this.geoBlocked = new HashSet<>(policy.getGeoBlockedCountries() != null ? policy.getGeoBlockedCountries() : List.of());

        // File extensions (normalize to lowercase with dot prefix)
        this.allowedExtensions = normalizeExtensions(policy.getAllowedFileExtensions());
        this.blockedExtensions = normalizeExtensions(policy.getBlockedFileExtensions());

        this.requireEncryption = policy.isRequireEncryption();

        // Parse transfer windows
        this.transferWindows = parseTransferWindows(policy.getTransferWindows());
    }

    /**
     * Check if a connection from this IP should be allowed.
     * Evaluates IP whitelist/blacklist and transfer window.
     */
    public FilterResult checkConnection(String sourceIp) {
        // 1. IP Whitelist check (if whitelist defined, ONLY whitelisted IPs pass)
        if (hasWhitelist) {
            if (!isIpInList(sourceIp, exactWhitelist, cidrWhitelist)) {
                return FilterResult.block("IP not in whitelist: " + sourceIp);
            }
        }

        // 2. IP Blacklist check
        if (isIpInList(sourceIp, exactBlacklist, cidrBlacklist)) {
            return FilterResult.block("IP blacklisted: " + sourceIp);
        }

        // 3. Transfer window check
        if (!transferWindows.isEmpty() && !isWithinTransferWindow()) {
            return FilterResult.block("Outside transfer window");
        }

        return FilterResult.allow();
    }

    /**
     * Check connection with geo-IP information (country code resolved externally).
     */
    public FilterResult checkConnectionWithGeo(String sourceIp, String countryCode) {
        FilterResult ipResult = checkConnection(sourceIp);
        if (!ipResult.allowed()) return ipResult;

        // Geo check
        if (countryCode != null && !countryCode.isEmpty()) {
            if (!geoAllowed.isEmpty() && !geoAllowed.contains(countryCode.toUpperCase())) {
                return FilterResult.block("Country not in allowed list: " + countryCode);
            }
            if (geoBlocked.contains(countryCode.toUpperCase())) {
                return FilterResult.block("Country blocked: " + countryCode);
            }
        }

        return FilterResult.allow();
    }

    /**
     * Check if a filename is allowed by extension rules.
     */
    public boolean isFileAllowed(String filename) {
        if (filename == null || filename.isEmpty()) return true;
        String ext = extractExtension(filename);
        if (ext == null) return blockedExtensions.isEmpty(); // no extension
        ext = ext.toLowerCase();
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(ext)) return false;
        return !blockedExtensions.contains(ext);
    }

    /**
     * Check if the current time falls within any configured transfer window.
     */
    public boolean isWithinTransferWindow() {
        if (transferWindows.isEmpty()) return true; // no restrictions
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        DayOfWeek today = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();

        for (TransferWindow tw : transferWindows) {
            if (tw.days.contains(today)) {
                if (!currentTime.isBefore(tw.startTime) && currentTime.isBefore(tw.endTime)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isRequireEncryption() { return requireEncryption; }
    public PortMapping.SecurityPolicy getPolicy() { return policy; }

    // ---- Internal helpers ----

    private boolean isIpInList(String ip, Set<String> exactIps, List<CidrRange> cidrRanges) {
        if (exactIps.contains(ip)) return true;
        if (cidrRanges.isEmpty()) return false;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            for (CidrRange range : cidrRanges) {
                if (range.contains(addr)) return true;
            }
        } catch (UnknownHostException e) {
            log.warn("Invalid IP address: {}", ip);
        }
        return false;
    }

    private record ParsedIpList(Set<String> exactIps, List<CidrRange> cidrRanges) {}

    private ParsedIpList parseIpList(List<String> ipList) {
        if (ipList == null || ipList.isEmpty()) return new ParsedIpList(Set.of(), List.of());
        Set<String> exact = new HashSet<>();
        List<CidrRange> cidrs = new ArrayList<>();
        for (String entry : ipList) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("/")) {
                try {
                    cidrs.add(CidrRange.parse(trimmed));
                } catch (Exception e) {
                    log.warn("Invalid CIDR range: {}", trimmed);
                }
            } else {
                exact.add(trimmed);
            }
        }
        return new ParsedIpList(Collections.unmodifiableSet(exact), Collections.unmodifiableList(cidrs));
    }

    private Set<String> normalizeExtensions(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) return Set.of();
        return extensions.stream()
            .map(e -> e.trim().toLowerCase())
            .map(e -> e.startsWith(".") ? e : "." + e)
            .collect(Collectors.toUnmodifiableSet());
    }

    private String extractExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot).toLowerCase() : null;
    }

    private List<TransferWindow> parseTransferWindows(List<Map<String, String>> windows) {
        if (windows == null || windows.isEmpty()) return List.of();
        List<TransferWindow> result = new ArrayList<>();
        for (Map<String, String> w : windows) {
            try {
                String daysStr = w.getOrDefault("dayOfWeek", "");
                String start = w.getOrDefault("startTime", "00:00");
                String end = w.getOrDefault("endTime", "23:59");
                Set<DayOfWeek> days = new HashSet<>();
                for (String d : daysStr.split(",")) {
                    d = d.trim().toUpperCase();
                    if (!d.isEmpty()) days.add(DayOfWeek.valueOf(d));
                }
                if (!days.isEmpty()) {
                    result.add(new TransferWindow(days, LocalTime.parse(start), LocalTime.parse(end)));
                }
            } catch (Exception e) {
                log.warn("Invalid transfer window: {}", w);
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ---- Inner classes ----

    /**
     * CIDR range for efficient subnet matching.
     * Supports both IPv4 and IPv6.
     */
    public static class CidrRange {
        private final byte[] networkBytes;
        private final int prefixLength;

        private CidrRange(byte[] networkBytes, int prefixLength) {
            this.networkBytes = networkBytes;
            this.prefixLength = prefixLength;
        }

        public static CidrRange parse(String cidr) throws UnknownHostException {
            String[] parts = cidr.split("/");
            InetAddress addr = InetAddress.getByName(parts[0].trim());
            int prefix = Integer.parseInt(parts[1].trim());
            return new CidrRange(addr.getAddress(), prefix);
        }

        public boolean contains(InetAddress address) {
            byte[] addrBytes = address.getAddress();
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

    private record TransferWindow(Set<DayOfWeek> days, LocalTime startTime, LocalTime endTime) {}
}
