package com.filetransfer.ftp.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IP allowlist / denylist filter for FTP connections.
 *
 * <p>Evaluation order:
 * <ol>
 *   <li>If the denylist is non-empty and the IP is in it, <strong>deny</strong>.</li>
 *   <li>If the allowlist is non-empty and the IP is <em>not</em> in it, <strong>deny</strong>.</li>
 *   <li>Otherwise, <strong>allow</strong>.</li>
 * </ol>
 *
 * <p>Both lists default to empty (all IPs allowed).
 */
@Slf4j
@Service
public class IpFilterService {

    @Value("${ftp.security.ip-allowlist:}")
    private String allowlistRaw;

    @Value("${ftp.security.ip-denylist:}")
    private String denylistRaw;

    private Set<String> allowlist = Collections.emptySet();
    private Set<String> denylist = Collections.emptySet();

    @PostConstruct
    void init() {
        allowlist = parseList(allowlistRaw);
        denylist = parseList(denylistRaw);
        if (!allowlist.isEmpty()) {
            log.info("FTP IP allowlist active: {}", allowlist);
        }
        if (!denylist.isEmpty()) {
            log.info("FTP IP denylist active: {}", denylist);
        }
    }

    /**
     * Determine whether a given IP address is permitted to connect.
     *
     * @param ipAddress the client IP address
     * @return {@code true} if the connection should be allowed
     */
    public boolean isAllowed(String ipAddress) {
        if (ipAddress == null) {
            return false;
        }
        // Strip IPv6 prefix that Java sometimes adds
        String ip = ipAddress.startsWith("/") ? ipAddress.substring(1) : ipAddress;

        if (!denylist.isEmpty() && denylist.contains(ip)) {
            log.warn("FTP connection denied (denylist): ip={}", ip);
            return false;
        }
        if (!allowlist.isEmpty() && !allowlist.contains(ip)) {
            log.warn("FTP connection denied (not in allowlist): ip={}", ip);
            return false;
        }
        return true;
    }

    private Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
