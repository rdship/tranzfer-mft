package com.filetransfer.dmz.proxy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a single port mapping: external port → internal host:port.
 * Optionally includes a per-mapping SecurityPolicy for fine-grained security control.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortMapping {
    private String name;
    private int listenPort;
    private String targetHost;
    private int targetPort;
    private boolean active;
    private SecurityPolicy securityPolicy;

    /**
     * Per-mapping security policy. Defines the security tier and manual rules for this mapping.
     * When null, the global security behavior applies (AI tier with global defaults).
     *
     * Security tiers:
     * - RULES: Admin-managed rules only (IP whitelist/blacklist, geo, rate limits, transfer windows). No AI.
     * - AI: Rules + AI verdict (default).
     * - AI_LLM: Rules + AI verdict with LLM-enhanced analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityPolicy {
        @Builder.Default private String securityTier = "AI";
        @Builder.Default private List<String> ipWhitelist = List.of();
        @Builder.Default private List<String> ipBlacklist = List.of();
        @Builder.Default private List<String> geoAllowedCountries = List.of();
        @Builder.Default private List<String> geoBlockedCountries = List.of();
        @Builder.Default private int rateLimitPerMinute = 60;
        @Builder.Default private int maxConcurrent = 20;
        @Builder.Default private long maxBytesPerMinute = 500_000_000L;
        @Builder.Default private int maxAuthAttempts = 5;
        @Builder.Default private int idleTimeoutSeconds = 300;
        @Builder.Default private boolean requireEncryption = false;
        @Builder.Default private boolean connectionLogging = true;
        @Builder.Default private List<String> allowedFileExtensions = List.of();
        @Builder.Default private List<String> blockedFileExtensions = List.of();
        @Builder.Default private long maxFileSizeBytes = 0;
        @Builder.Default private List<Map<String, String>> transferWindows = List.of();
    }
}
