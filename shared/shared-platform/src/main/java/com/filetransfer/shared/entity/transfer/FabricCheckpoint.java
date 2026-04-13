package com.filetransfer.shared.entity.transfer;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Per-step execution checkpoint for the Flow Fabric.
 * One row is written when a step starts, updated when it completes/fails.
 *
 * Queried to answer "where is file X right now?" in real-time.
 */
@Entity
@Table(name = "fabric_checkpoints")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FabricCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Size(max = 32)
    @Column(name = "track_id", nullable = false, length = 32)
    private String trackId;

    @NotNull
    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @NotBlank
    @Size(max = 64)
    @Column(name = "step_type", nullable = false, length = 64)
    private String stepType;

    @NotBlank
    @Size(max = 24)
    @Column(nullable = false, length = 24)
    private String status;  // PENDING, IN_PROGRESS, COMPLETED, FAILED, ABANDONED

    @Column(name = "input_storage_key", length = 64)
    private String inputStorageKey;

    @Column(name = "output_storage_key", length = 64)
    private String outputStorageKey;

    @Column(name = "input_size_bytes")
    private Long inputSizeBytes;

    @Column(name = "output_size_bytes")
    private Long outputSizeBytes;

    @Size(max = 128)
    @Column(name = "processing_instance", length = 128)
    private String processingInstance;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "attempt_number")
    @Builder.Default
    private Integer attemptNumber = 1;

    @Size(max = 32)
    @Column(name = "error_category", length = 32)
    private String errorCategory;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "fabric_offset")
    private Long fabricOffset;

    @Column(name = "fabric_partition")
    private Integer fabricPartition;

    /** Step-specific data. Stored as JSONB via Hibernate 6 JdbcTypeCode. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
