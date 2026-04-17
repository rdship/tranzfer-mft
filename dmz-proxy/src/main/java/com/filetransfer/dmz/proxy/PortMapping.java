package com.filetransfer.dmz.proxy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.filetransfer.dmz.security.ZoneEnforcer;

import java.util.List;
import java.util.Map;

/**
 * Represents a single port mapping: external port → internal host:port.
 * Includes per-mapping security, TLS, zone, egress, inspection, QoS, and health config.
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
    private TlsPolicy tlsPolicy;
    private ZonePolicy zonePolicy;
    private EgressPolicy egressPolicy;
    private InspectionPolicy inspectionPolicy;
    private QoSPolicy qosPolicy;
    private HealthCheckPolicy healthCheckPolicy;
    /**
     * When non-null, the mapping is treated as an FTP control-channel listener
     * and DMZ:
     *   1. Intercepts {@code 227 Entering Passive Mode} replies from the backend
     *      and rewrites the advertised IP to {@code externalHost} (reverse-proxy
     *      semantics — clients must see DMZ, not the internal service).
     *   2. Spawns sibling TCP forwarders for every port in
     *      {@code passivePortFrom..passivePortTo} so the data connection traverses
     *      DMZ too. Same port number on both sides → no port translation needed.
     */
    private FtpDataChannelPolicy ftpDataChannelPolicy;
    @Builder.Default private boolean proxyProtocolEnabled = false;
    @Builder.Default private boolean auditEnabled = true;

    /** Pre-resolved target zone — set by ProxyManager.add() to avoid DNS on the event loop. */
    private transient ZoneEnforcer.Zone cachedTargetZone;

    /**
     * Per-mapping security policy. Defines the security tier and manual rules.
     * When null, global security behavior applies (AI tier with global defaults).
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

    /** TLS termination and mutual TLS configuration. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TlsPolicy {
        @Builder.Default private boolean enabled = false;
        private String certAlias;                 // Keystore Manager alias (preferred over file paths)
        private String certPath;                  // PEM cert file fallback
        private String keyPath;                   // PEM key file fallback
        private String keyPassword;
        private String trustStorePath;            // CA certs for client validation (mTLS)
        @Builder.Default private boolean requireClientCert = false;
        @Builder.Default private String minTlsVersion = "TLSv1.2";
        @Builder.Default private List<String> cipherSuites = List.of();
        @Builder.Default private boolean enableOcspStapling = false;
        @Builder.Default private long sessionTimeoutSeconds = 3600;
        @Builder.Default private int sessionCacheSize = 10_000;
    }

    /** Network zone enforcement — source and target zone classification. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZonePolicy {
        @Builder.Default private String sourceZone = "EXTERNAL";
        @Builder.Default private String targetZone = "INTERNAL";
    }

    /** Egress filtering — controls outbound connections from this mapping. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EgressPolicy {
        @Builder.Default private boolean enabled = true;
        @Builder.Default private List<String> allowedDestinations = List.of();
        @Builder.Default private boolean blockPrivateRanges = false;  // false: backends are private
        @Builder.Default private boolean dnsPinning = true;
    }

    /** Deep packet inspection and protocol-specific filtering. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InspectionPolicy {
        @Builder.Default private boolean enabled = true;
        @Builder.Default private boolean enforceMinTls = true;
        @Builder.Default private String minTlsVersion = "TLSv1.2";
        @Builder.Default private boolean blockWeakCiphers = true;
        @Builder.Default private boolean blockSshV1 = true;
        @Builder.Default private boolean validateHttpHeaders = true;
        @Builder.Default private int maxHttpHeaderSize = 8192;
        @Builder.Default private boolean blockSqlInjection = true;
        @Builder.Default private boolean blockCommandInjection = true;
        @Builder.Default private boolean blockPathTraversal = true;
        // FTP-specific
        @Builder.Default private boolean ftpFilterEnabled = true;
        @Builder.Default private boolean blockFtpPortCommand = true;
        @Builder.Default private boolean blockFtpSiteCommand = true;
        @Builder.Default private boolean requireFtpPassiveMode = true;
        @Builder.Default private List<String> ftpAllowedCommands = List.of();
        // Content screening
        @Builder.Default private boolean contentScreeningEnabled = false;
        @Builder.Default private boolean blockOnScreeningHit = true;
        @Builder.Default private boolean asyncScreening = true;
    }

    /** Quality-of-service and bandwidth management. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QoSPolicy {
        @Builder.Default private boolean enabled = false;
        @Builder.Default private long maxBytesPerSecond = 0;          // 0 = unlimited
        @Builder.Default private long perConnectionMaxBytesPerSecond = 0;
        @Builder.Default private int priority = 5;                     // 1=highest, 10=lowest
        @Builder.Default private int burstAllowancePercent = 20;
    }

    /**
     * FTP data-channel support for reverse-proxy topology.
     *
     * <p>FTP is uniquely nasty for a reverse proxy: PASV replies embed the
     * server's IP/port in the control-channel payload. Without rewriting,
     * clients receive the internal container IP and passive transfers fail or
     * leak topology. With rewriting + sibling port forwarders, the client
     * sees only DMZ's external address and a range of DMZ-side passive ports
     * that each forward 1:1 to the backend's same-numbered port.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FtpDataChannelPolicy {
        /** Lower bound of the passive-port range DMZ will listen on + forward. */
        private Integer passivePortFrom;
        /** Upper bound (inclusive). */
        private Integer passivePortTo;
        /**
         * Externally-visible host advertised in rewritten PASV replies. Must be
         * the address clients reach DMZ at, not the internal container name.
         * When null, falls back to the service-wide {@code dmz.external-host}
         * property (env {@code DMZ_EXTERNAL_HOST}).
         */
        private String externalHost;
        /** When false, DMZ forwards PASV replies untouched. Defaults to true. */
        @Builder.Default private boolean rewritePasvResponse = true;
    }

    /** Backend health checking configuration. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthCheckPolicy {
        @Builder.Default private boolean enabled = true;
        @Builder.Default private int intervalSeconds = 10;
        @Builder.Default private int timeoutSeconds = 3;
        @Builder.Default private int unhealthyThreshold = 3;
        @Builder.Default private int healthyThreshold = 1;
    }
}
