package com.filetransfer.shared.entity.transfer;

import com.filetransfer.shared.entity.core.*;

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

    /**
     * R105b: rich, step-type-specific semantic detail serialized as JSON.
     * Examples: {@code {"sourceFormat":"EDI-X12-850","targetFormat":"JSON","rows":12,"warnings":0}}
     * for CONVERT_EDI, {@code {"algorithm":"AES-256-GCM","keySource":"keystore-manager","keyId":"..."}}
     * for ENCRYPT_AES. Consumed by the Activity Monitor drill-down and AI Copilot.
     * Null for step types that emit no semantic detail — use {@code stepType} alone.
     */
    @Column(columnDefinition = "TEXT")
    private String stepDetailsJson;

    /** R105b: rows processed (CONVERT_EDI, EXECUTE_SCRIPT, SCREEN) — null when not applicable. */
    private Long rowsProcessed;

    /** R105b: which replica executed this step (hostname-suffix). Useful when diagnosing one-instance flakiness. */
    @Column(length = 120)
    private String processingInstance;

    /** R105b: attempt that produced this snapshot (1-based). Null for pre-R105 rows. */
    private Integer attemptCount;

    /** R105b: step config used at execution time (jsonified). Null when config is empty. */
    @Column(columnDefinition = "TEXT")
    private String stepConfigJson;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
