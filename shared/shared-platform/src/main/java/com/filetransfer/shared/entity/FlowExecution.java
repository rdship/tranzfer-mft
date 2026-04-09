package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tracks one execution of a FileFlow pipeline for a specific file.
 */
@Entity
@Table(name = "flow_executions", indexes = {
    @Index(name = "idx_flow_exec_track_id", columnList = "trackId", unique = true),
    @Index(name = "idx_flow_exec_status", columnList = "status")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FlowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 12-character tracking ID (e.g. "TRZ-A1B2C3D4E") */
    @Column(unique = true, nullable = false, length = 12)
    private String trackId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id")
    private FileFlow flow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_record_id")
    private FileTransferRecord transferRecord;

    @Column(nullable = false)
    private String originalFilename;

    /** Current working file path (physical-mode accounts: local path; virtual-mode: VFS path). */
    private String currentFilePath;

    /**
     * Current CAS storage key (SHA-256) — set for VIRTUAL-mode accounts.
     * Identifies the content in storage-manager at each pipeline step boundary.
     * Null for physical-mode accounts.
     */
    @Column(length = 64)
    private String currentStorageKey;

    /**
     * SHA-256 of the file as it entered this flow — set once at creation, never changed.
     * Used as the restart key when admin retries from the beginning.
     */
    @Column(length = 64)
    private String initialStorageKey;

    /** 1-based attempt counter — increments on each restart. */
    @Builder.Default
    private int attemptNumber = 1;

    /**
     * JSONB array of previous failed/cancelled attempt summaries.
     * Each element: {attempt, startedAt, failedAt, steps[], errorMessage}.
     * Preserves full history across restarts without creating new rows.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.List<java.util.Map<String, Object>> attemptHistory;

    /** Set to true by terminate API. The running agent polls this between steps and exits cleanly. */
    @Builder.Default
    private boolean terminationRequested = false;

    private String restartedBy;
    private Instant restartedAt;
    private String terminatedBy;
    private Instant terminatedAt;

    /**
     * When non-null, the scheduler will restart this execution at this time.
     * Cleared atomically by ScheduledRetryExecutor before triggering to prevent double-fire.
     */
    private Instant scheduledRetryAt;
    private String scheduledRetryBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FlowStatus status = FlowStatus.PENDING;

    /** Which step index we're on (0-based) */
    @Builder.Default
    private int currentStep = 0;

    /** Per-step execution log */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<StepResult> stepResults;

    /** Snapshot of the criteria that matched this execution (audit trail) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private com.filetransfer.shared.matching.MatchCriteria matchedCriteria;

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    public enum FlowStatus { PENDING, PROCESSING, COMPLETED, FAILED, PAUSED, UNMATCHED, CANCELLED }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StepResult {
        private int stepIndex;
        private String stepType;
        private String status; // OK, FAILED, SKIPPED
        private String inputFile;
        private String outputFile;
        private long durationMs;
        private String error;
    }
}
