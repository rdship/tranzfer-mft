package com.filetransfer.ai.entity.edi;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata about a training run — tracks what was trained, how long it took,
 * and what the outcome was. Provides full audit trail for map provenance.
 */
@Entity
@Table(name = "edi_training_sessions", indexes = {
        @Index(name = "idx_ets_status", columnList = "status"),
        @Index(name = "idx_ets_map_key", columnList = "mapKey")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TrainingSession {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Which map this session trained */
    @Column(nullable = false, length = 200)
    private String mapKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** Number of samples used for training */
    private int trainingSampleCount;

    /** Number of samples held out for testing */
    private int testSampleCount;

    /** Strategies applied during training (comma-separated) */
    @Column(length = 500)
    private String strategiesUsed;

    /** Map version produced by this session */
    private int producedMapVersion;

    /** Overall confidence of the produced map */
    private int producedMapConfidence;

    /** Test accuracy on held-out samples (0-100) */
    private Integer testAccuracy;

    /** Number of field mappings discovered */
    private int fieldMappingsDiscovered;

    /** Improvement over previous version (delta in confidence points) */
    private int improvementDelta;

    /** Duration of the training run in milliseconds */
    private long durationMs;

    /** Error message if training failed */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Detailed training log/report */
    @Column(columnDefinition = "TEXT")
    private String trainingReport;

    /** Who triggered this training (user email or "system") */
    @Column(length = 200)
    private String triggeredBy;

    private Instant startedAt;
    private Instant completedAt;

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (startedAt == null) startedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.durationMs = Duration.between(startedAt, completedAt).toMillis();
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
        this.durationMs = Duration.between(startedAt, completedAt).toMillis();
        this.errorMessage = error;
    }
}
