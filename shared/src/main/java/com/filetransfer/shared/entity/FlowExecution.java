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
    @JoinColumn(name = "flow_id", nullable = false)
    private FileFlow flow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_record_id")
    private FileTransferRecord transferRecord;

    @Column(nullable = false)
    private String originalFilename;

    /** Current working file path (changes as steps process) */
    private String currentFilePath;

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

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    public enum FlowStatus { PENDING, PROCESSING, COMPLETED, FAILED, PAUSED }

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
