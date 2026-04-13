package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of one FileFlow pipeline step — persisted asynchronously
 * via {@link com.filetransfer.shared.event.FlowStepEventListener}.
 *
 * <p>Stores only CAS keys (SHA-256), never file bytes. The actual content remains
 * in storage-manager's HOT tier and is streamed on-demand via the preview API.
 * Writing this row is zero additional I/O for the files themselves.
 */
@Entity
@Table(name = "flow_step_snapshots", indexes = {
    @Index(name = "idx_fss_track_id",   columnList = "trackId"),
    @Index(name = "idx_fss_exec_id",    columnList = "flowExecutionId"),
    @Index(name = "idx_fss_track_step", columnList = "trackId, stepIndex", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FlowStepSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 12)
    private String trackId;

    /** FK to flow_executions.id — stored as bare UUID (no JPA association to avoid lazy-load overhead). */
    @Column
    private UUID flowExecutionId;

    @Column(nullable = false)
    private int stepIndex;

    @Column(nullable = false, length = 50)
    private String stepType;

    /** OK | OK_AFTER_RETRY_N | FAILED */
    @Column(length = 30)
    private String stepStatus;

    /** SHA-256 of the file before this step. Always set. */
    @Column(length = 512)
    private String inputStorageKey;

    /** SHA-256 of the file after this step. Null on failure; same as input for pass-through steps. */
    @Column(length = 512)
    private String outputStorageKey;

    @Column(length = 1024)
    private String inputVirtualPath;

    /** Null on failure. */
    @Column(length = 1024)
    private String outputVirtualPath;

    private Long inputSizeBytes;
    private Long outputSizeBytes;
    private Long durationMs;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
