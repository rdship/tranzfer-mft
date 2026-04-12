package com.filetransfer.analytics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "metric_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"snapshot_time", "service_type"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MetricSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "snapshot_time", nullable = false)
    private Instant snapshotTime;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    private String protocol;

    @Builder.Default private Long totalTransfers = 0L;
    @Builder.Default private Long successfulTransfers = 0L;
    @Builder.Default private Long failedTransfers = 0L;
    @Builder.Default private Long totalBytesTransferred = 0L;
    @Builder.Default private Double avgLatencyMs = 0.0;
    @jakarta.persistence.Column(name = "p95_latency_ms")
    @Builder.Default private Double p95LatencyMs = 0.0;
    @jakarta.persistence.Column(name = "p99_latency_ms")
    @Builder.Default private Double p99LatencyMs = 0.0;
    @Builder.Default private Integer activeSessions = 0;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
