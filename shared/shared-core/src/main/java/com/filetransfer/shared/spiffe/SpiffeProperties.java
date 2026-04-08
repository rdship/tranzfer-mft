package com.filetransfer.shared.spiffe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SPIFFE/SPIRE workload identity.
 *
 * <p>Add to each service's application.yml to opt in:
 * <pre>
 * spiffe:
 *   enabled: true
 *   trust-domain: filetransfer.io
 *   socket: unix:/run/spire/sockets/agent.sock
 *   service-name: gateway-service   # this service's SPIFFE path segment
 * </pre>
 *
 * <p>When {@code enabled=false} (default) the platform falls back to
 * X-Internal-Key header authentication — no disruption during rollout.
 */
@Data
@ConfigurationProperties(prefix = "spiffe")
public class SpiffeProperties {

    /** Master switch. Default false so existing deployments are unaffected. */
    private boolean enabled = false;

    /** SPIFFE trust domain (must match SPIRE Server trust_domain). */
    private String trustDomain = "filetransfer.io";

    /**
     * SPIRE Agent Workload API socket path.
     * Default matches the standard SPIRE Docker Compose volume mount.
     */
    private String socket = "unix:/run/spire/sockets/agent.sock";

    /**
     * This service's SPIFFE path segment (e.g. "gateway-service").
     * Produces SPIFFE ID: spiffe://{trustDomain}/{serviceName}
     * Auto-detected from spring.application.name if not set.
     */
    private String serviceName;

    /** How long (ms) to wait for the SPIRE agent on startup. Default 10s. */
    private long initTimeoutMs = 10_000;

    /** Build this service's full SPIFFE ID. */
    public String selfSpiffeId() {
        return "spiffe://" + trustDomain + "/" + serviceName;
    }

    /** Build the audience SPIFFE ID for a target service. */
    public String audienceFor(String targetServiceName) {
        return "spiffe://" + trustDomain + "/" + targetServiceName;
    }
}
