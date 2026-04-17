package com.filetransfer.dmz.proxy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "dmz")
public class DmzProperties {
    private List<PortMapping> mappings;

    /**
     * The reverse-proxy's externally-visible host (IPv4 literal or DNS name
     * resolvable by external clients). Used to rewrite FTP PASV replies when
     * a mapping has an {@link PortMapping.FtpDataChannelPolicy} but no
     * per-mapping {@code externalHost} override. Null or blank disables
     * PASV rewriting (fail-open, with a WARN at mapping creation).
     */
    private String externalHost;

    /** AI-powered security configuration */
    private Security security = new Security();

    /** TLS termination defaults */
    private Tls tls = new Tls();

    /** Audit logging */
    private Audit audit = new Audit();

    /** Network zone enforcement */
    private Zones zones = new Zones();

    /** Egress filtering defaults */
    private Egress egress = new Egress();

    /** Deep packet inspection defaults */
    private Inspection inspection = new Inspection();

    /** Backend health checking defaults */
    private HealthCheck healthCheck = new HealthCheck();

    /** Bandwidth QoS defaults */
    private Qos qos = new Qos();

    /** Inbound PROXY protocol (when behind a load balancer) */
    private ProxyProtocol proxyProtocol = new ProxyProtocol();

    /** Single-port multiplexed tunnel (replaces all cross-DMZ connections) */
    private Tunnel tunnel = new Tunnel();

    @Data
    public static class Security {
        private boolean enabled = true;
        private String aiEngineUrl = "http://ai-engine:8091";
        private long verdictTimeoutMs = 200;
        private int defaultRatePerMinute = 60;
        private int defaultMaxConcurrent = 20;
        private long defaultMaxBytesPerMinute = 500_000_000L;
        private int globalRatePerMinute = 10_000;
        private int eventQueueCapacity = 10_000;
        private int eventBatchSize = 50;
        private long eventFlushIntervalMs = 5_000;
        /** Rewrite backend SSH banners to hide implementation details */
        private boolean sshBannerRewrite = true;
        /** Replacement SSH banner (must start with SSH-2.0-) */
        private String sshBanner = "SSH-2.0-TranzFer_MFT_Proxy";
    }

    @Data
    public static class Tls {
        /** Enable TLS termination at the proxy edge */
        private boolean enabled = false;
        /** Keystore Manager URL for certificate management */
        private String keystoreManagerUrl = "http://keystore-manager:8093";
        /** Local directory for caching certificates from Keystore Manager */
        private String certCacheDir = "./cert-cache";
        /** How often to check for certificate updates (seconds) */
        private long certRefreshIntervalSeconds = 3600;
        /** Minimum TLS version (TLSv1.2 or TLSv1.3) */
        private String minTlsVersion = "TLSv1.2";
        /** Block weak cipher suites (NULL, EXPORT, DES, RC4, MD5) */
        private boolean blockWeakCiphers = true;
    }

    @Data
    public static class Audit {
        /** Enable persistent audit logging */
        private boolean enabled = true;
        /** Directory for audit log files */
        private String logDirectory = "./audit-logs";
        /** Retention period in days (compliance: 90 days) */
        private int maxDays = 90;
        /** Max single log file size in MB before rotation */
        private long maxFileSizeMb = 100;
    }

    @Data
    public static class Zones {
        /** Enable network zone enforcement */
        private boolean enabled = true;
        /** CIDR ranges per zone. Keys: EXTERNAL, DMZ, INTERNAL, MANAGEMENT */
        private Map<String, List<String>> cidrs = Map.of(
            "INTERNAL", List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"),
            "MANAGEMENT", List.of("10.255.0.0/16")
        );
    }

    @Data
    public static class Egress {
        /** Enable egress filtering */
        private boolean enabled = true;
        /** Block connections to cloud metadata endpoints */
        private boolean blockMetadataService = true;
        /** Block connections to loopback addresses */
        private boolean blockLoopback = true;
        /** Block connections to link-local addresses */
        private boolean blockLinkLocal = true;
        /** Enable DNS pinning (resolve once, cache IP) */
        private boolean dnsPinning = true;
        /** DNS resolution timeout (ms) */
        private int maxDnsResolutionMs = 2000;
        /** Always-blocked destination ports */
        private List<String> blockedPorts = List.of("25", "53", "135", "137", "138", "139", "445");
        /** TTL for DNS cache entries in seconds (default 300 = 5 minutes) */
        private int dnsTtlSeconds = 300;
    }

    @Data
    public static class Inspection {
        /** Enable deep packet inspection */
        private boolean enabled = true;
        /** Block SSH protocol v1 */
        private boolean blockSshV1 = true;
        /** Block SQL injection patterns in HTTP */
        private boolean blockSqlInjection = true;
        /** Block command injection patterns in HTTP */
        private boolean blockCommandInjection = true;
        /** Block path traversal attempts */
        private boolean blockPathTraversal = true;
        /** Enable FTP command filtering */
        private boolean ftpFilterEnabled = true;
        /** Screening service URL for content screening */
        private String screeningServiceUrl = "http://screening-service:8092";
    }

    @Data
    public static class HealthCheck {
        /** Enable backend health checking */
        private boolean enabled = true;
        /** Probe interval (seconds) */
        private int intervalSeconds = 10;
        /** TCP connect timeout (seconds) */
        private int timeoutSeconds = 3;
        /** Consecutive failures before marking unhealthy */
        private int unhealthyThreshold = 3;
        /** Consecutive successes before marking healthy */
        private int healthyThreshold = 1;
    }

    @Data
    public static class Qos {
        /** Enable bandwidth QoS */
        private boolean enabled = false;
        /** Global max bytes per second (0 = unlimited) */
        private long globalMaxBytesPerSecond = 0;
        /** Default per-mapping max bytes per second (0 = unlimited) */
        private long perMappingMaxBytesPerSecond = 0;
    }

    @Data
    public static class Tunnel {
        /** Enable multiplexed tunnel (gateway-service connects inbound on tunnelPort) */
        private boolean enabled = false;
        /** Tunnel listen port (accepts single connection from gateway-service) */
        private int port = 9443;
        /** Enable TLS on the tunnel connection */
        private boolean tlsEnabled = false;
        /** Max concurrent multiplexed streams */
        private int maxStreams = 1024;
        /** Per-stream flow control window in bytes */
        private int windowSize = 262144;  // 256KB
        /** Fall back to direct connections when tunnel is down */
        private boolean fallbackToDirect = true;
    }

    @Data
    public static class ProxyProtocol {
        /**
         * Enable inbound PROXY protocol parsing. Set to true ONLY when the
         * proxy sits behind a load balancer that sends PROXY protocol headers
         * (v1 or v2). When false, connections that send PROXY headers will be
         * treated as normal TCP data.
         */
        private boolean inboundEnabled = false;
    }
}
