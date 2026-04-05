package com.filetransfer.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of truth for all platform-wide configuration.
 * All services bind to the same "platform.*" namespace.
 *
 * Usage in application.yml:
 *   platform:
 *     track-id:
 *       prefix: TRZ
 *     cluster:
 *       id: cluster-1
 *       host: service-name
 *       service-type: SFTP
 *     security:
 *       jwt-secret: ...
 *       control-api-key: ...
 *     storage:
 *       sftp-home: /data/sftp
 *       ftp-home: /data/ftp
 *       ftpweb-home: /data/ftpweb
 */
@Configuration
@ConfigurationProperties(prefix = "platform")
@Getter @Setter
public class PlatformConfig {

    private TrackIdConfig trackId = new TrackIdConfig();
    private ClusterConfig cluster = new ClusterConfig();
    private SecurityConfig security = new SecurityConfig();
    private StorageConfig storage = new StorageConfig();
    private FlowConfig flow = new FlowConfig();
    private ProxyConfig proxy = new ProxyConfig();

    @Getter @Setter
    public static class TrackIdConfig {
        /** First 3 characters of every tracking ID (e.g. "TRZ") */
        private String prefix = "TRZ";
    }

    @Getter @Setter
    public static class ClusterConfig {
        private String id = "default-cluster";
        private String host = "localhost";
        private String serviceType = "UNKNOWN";
        /** WITHIN_CLUSTER = isolate to same cluster; CROSS_CLUSTER = federate across clusters */
        private String communicationMode = "WITHIN_CLUSTER";
    }

    @Getter @Setter
    public static class SecurityConfig {
        private String jwtSecret = "change_me_in_production_256bit_secret_key!!";
        private long jwtExpirationMs = 900000;
        private String controlApiKey = "internal_control_secret";
    }

    @Getter @Setter
    public static class StorageConfig {
        private String sftpHome = "/data/sftp";
        private String ftpHome = "/data/ftp";
        private String ftpwebHome = "/data/ftpweb";
    }

    @Getter @Setter
    public static class FlowConfig {
        /** Max concurrent flow executions per service instance */
        private int maxConcurrent = 50;
        /** Work directory for intermediate flow files */
        private String workDir = "/tmp/mft-flow-work";
        /** Retain completed flow work files for N hours (0 = delete immediately) */
        private int retainWorkHours = 24;
    }

    @Getter @Setter
    public static class ProxyConfig {
        /** Whether this service routes outbound calls through a proxy. Default: false (direct) */
        private boolean enabled = false;
        /** Proxy type: HTTP, SOCKS5, DMZ */
        private String type = "HTTP";
        /** Proxy hostname, e.g. "dmz-proxy" */
        private String host;
        /** Proxy port */
        private int port = 8080;
        /**
         * Comma-separated list of hosts to bypass the proxy for (direct connection).
         * e.g. "localhost,postgres,rabbitmq" — internal infra should typically bypass.
         */
        private String noProxyHosts = "localhost,127.0.0.1,postgres,rabbitmq";
    }
}
