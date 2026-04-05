package com.filetransfer.sftp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces IP-based access control for SFTP connections.
 *
 * <p>Supports both an allowlist and a denylist. When an allowlist is configured
 * (non-empty), only those IPs are permitted. The denylist is always checked
 * and takes precedence over the allowlist.</p>
 *
 * <p>IP addresses are matched as exact strings after stripping the leading
 * slash added by Java's {@code InetSocketAddress.toString()}.</p>
 */
@Slf4j
@Component
public class IpAccessControl {

    @Value("${sftp.security.ip-allowlist:}")
    private List<String> ipAllowlist;

    @Value("${sftp.security.ip-denylist:}")
    private List<String> ipDenylist;

    private Set<String> normalizedAllowlist = Collections.emptySet();
    private Set<String> normalizedDenylist = Collections.emptySet();

    @PostConstruct
    void init() {
        if (ipAllowlist != null && !ipAllowlist.isEmpty()) {
            normalizedAllowlist = ipAllowlist.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (!normalizedAllowlist.isEmpty()) {
                log.info("IP allowlist enabled with {} entries: {}", normalizedAllowlist.size(), normalizedAllowlist);
            }
        }
        if (ipDenylist != null && !ipDenylist.isEmpty()) {
            normalizedDenylist = ipDenylist.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (!normalizedDenylist.isEmpty()) {
                log.info("IP denylist enabled with {} entries: {}", normalizedDenylist.size(), normalizedDenylist);
            }
        }
    }

    /**
     * Checks whether the given IP address is allowed to connect.
     *
     * @param rawIp the IP address string (may contain leading slash or port suffix)
     * @return true if the IP is permitted
     */
    public boolean isAllowed(String rawIp) {
        String ip = normalizeIp(rawIp);

        // Denylist always takes precedence
        if (!normalizedDenylist.isEmpty() && normalizedDenylist.contains(ip)) {
            log.warn("Connection denied by IP denylist: {}", ip);
            return false;
        }

        // If allowlist is configured, IP must be on it
        if (!normalizedAllowlist.isEmpty() && !normalizedAllowlist.contains(ip)) {
            log.warn("Connection denied: IP {} not on allowlist", ip);
            return false;
        }

        return true;
    }

    /**
     * Normalizes an IP address string by removing leading slashes and port suffixes.
     * Example: "/192.168.1.1:54321" becomes "192.168.1.1"
     */
    static String normalizeIp(String rawIp) {
        if (rawIp == null) return "unknown";
        String ip = rawIp;
        // Remove leading slash (Java InetSocketAddress.toString() adds it)
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        // Remove port suffix if present
        int colonIndex = ip.lastIndexOf(':');
        if (colonIndex > 0) {
            // Avoid stripping IPv6 colons: only strip if it looks like host:port
            String afterColon = ip.substring(colonIndex + 1);
            try {
                Integer.parseInt(afterColon);
                ip = ip.substring(0, colonIndex);
            } catch (NumberFormatException e) {
                // Not a port suffix; keep as-is (IPv6)
            }
        }
        return ip;
    }
}
