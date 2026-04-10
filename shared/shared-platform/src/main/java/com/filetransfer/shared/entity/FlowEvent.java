package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, append-only event journal entry for file flow executions.
 * Events capture every state transition and are never updated or deleted.
 * Supports time-travel debugging, actor recovery, and regulatory audit.
 */
@Entity @Table(name = "flow_events", indexes = {
    @Index(name = "idx_fe_track_id", columnList = "trackId"),
    @Index(name = "idx_fe_execution_id", columnList = "executionId"),
    @Index(name = "idx_fe_created_at", columnList = "createdAt")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class FlowEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 12)
    private String trackId;

    private UUID executionId;

    @Column(nullable = false, length = 40)
    private String eventType;  // FLOW_MATCHED, EXECUTION_STARTED, STEP_STARTED, STEP_COMPLETED,
                               // STEP_FAILED, STEP_RETRYING, EXECUTION_PAUSED, APPROVAL_RECEIVED,
                               // EXECUTION_RESUMED, EXECUTION_COMPLETED, EXECUTION_FAILED,
                               // EXECUTION_TERMINATED, EXECUTION_RESTARTED

    private Integer stepIndex;

    private String stepType;   // COMPRESS_GZIP, ENCRYPT_PGP, etc.

    private String storageKey; // SHA-256 key at this point in the pipeline

    private String virtualPath;

    private Long sizeBytes;

    private Long durationMs;

    private Integer attemptNumber;

    private String status;     // OK, FAILED, PAUSED, etc.

    @Column(length = 2000)
    private String errorMessage;

    @Column(length = 500)
    private String actor;      // who/what caused this event (system, admin@company.com, etc.)

    @Column(columnDefinition = "TEXT")
    private String metadata;   // JSON blob for event-specific data

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
