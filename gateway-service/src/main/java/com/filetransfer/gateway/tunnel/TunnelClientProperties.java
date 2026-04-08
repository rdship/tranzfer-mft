package com.filetransfer.gateway.tunnel;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the outbound tunnel connection to dmz-proxy.
 * All cross-zone traffic is multiplexed over a single TCP tunnel on port 9443.
 */
@Configuration
@ConfigurationProperties(prefix = "tunnel")
@Getter
@Setter
public class TunnelClientProperties {

    private boolean enabled = false;

    private String dmzHost = "dmz-proxy";
    private int dmzPort = 9443;

    private boolean tlsEnabled = false;
    private String tlsCertPath;
    private String tlsKeyPath;

    private int maxStreams = 1024;
    private int windowSize = 262144;  // 256KB

    private int keepaliveIntervalSeconds = 15;
    private int keepaliveTimeoutSeconds = 20;

    // Reconnection
    private int reconnectBaseMs = 1000;
    private int reconnectMaxMs = 30000;
    private double reconnectJitter = 0.25;

    // Internal service URLs for control request forwarding
    private String aiEngineUrl = "http://ai-engine:8091";
    private String screeningServiceUrl = "http://screening-service:8092";
    private String keystoreManagerUrl = "http://keystore-manager:8093";
}
