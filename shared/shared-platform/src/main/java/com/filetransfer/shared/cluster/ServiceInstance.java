package com.filetransfer.shared.cluster;

import lombok.*;
import java.time.Instant;

/**
 * Live service instance record — read from Redis presence keys.
 * Lighter than {@link com.filetransfer.shared.entity.ServiceRegistration}
 * (no DB round-trip, TTL-based freshness).
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceInstance {
    private String instanceId;
    private String serviceType;
    private String host;
    private int    port;
    /** Derived: http://{host}:{port} */
    private String url;
    private String clusterId;
    private Instant startedAt;
    private Instant lastSeen;
}
