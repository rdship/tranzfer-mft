package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Heartbeat-based registry of running pods/instances.
 * Each pod writes its status every 30 seconds.
 * Dead pod detection: if last_heartbeat > 2 min old, pod is assumed crashed.
 */
@Entity
@Table(name = "fabric_instances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FabricInstance {

    @Id
    @Size(max = 128)
    @Column(name = "instance_id", length = 128)
    private String instanceId;  // e.g., "onboarding-api-pod-7-uuid"

    @NotBlank
    @Size(max = 64)
    @Column(name = "service_name", nullable = false, length = 64)
    private String serviceName;

    @Size(max = 200)
    @Column(length = 200)
    private String host;

    @NotNull
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @NotNull
    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat;

    @NotBlank
    @Size(max = 16)
    @Column(nullable = false, length = 16)
    private String status;  // HEALTHY, DEGRADED, DRAINING

    @Column(name = "consumed_topics", columnDefinition = "TEXT")
    private String consumedTopics;  // JSON array string

    @Column(name = "current_partitions", columnDefinition = "TEXT")
    private String currentPartitions;  // JSON array string

    @Column(name = "in_flight_count")
    @Builder.Default
    private Integer inFlightCount = 0;

    /** Free-form instance metadata. Stored as JSONB via Hibernate 6 JdbcTypeCode. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
