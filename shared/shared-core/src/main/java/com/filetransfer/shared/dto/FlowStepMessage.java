package com.filetransfer.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Saga-pattern message for per-step flow execution via RabbitMQ.
 *
 * <p>Each step in a file processing flow is an independent message.
 * Workers consume from a single pipeline queue, execute the current step,
 * checkpoint the result to storage-manager, then publish the next step
 * message. If the worker crashes mid-step, RabbitMQ redelivers the
 * unacked message to another worker.
 *
 * <p>This design provides:
 * <ul>
 *   <li>Reliability — durable messages survive broker restarts
 *   <li>Resilience — unacked messages redeliver to healthy workers
 *   <li>Scalability — add workers to increase throughput
 *   <li>Observability — each step is a discrete event with timing
 *   <li>Resume — on failure, restart from the exact step that failed
 *   <li>Backpressure — prefetch controls memory usage per worker
 * </ul>
 *
 * <p>Idempotency key: {@code trackId + "#" + stepIndex + "#" + attempt}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowStepMessage {

    /** 12-char tracking ID — primary audit key across all services */
    private String trackId;

    /** FK to flow_executions — the parent execution record */
    private UUID flowExecutionId;

    /** FK to file_flows — the flow definition (steps, config, priority) */
    private UUID flowId;

    /** 0-based step index within the flow */
    private int stepIndex;

    /** Total steps in the flow (for progress tracking: stepIndex/totalSteps) */
    private int totalSteps;

    /** Step type: SCREEN, ENCRYPT_PGP, COMPRESS_GZIP, CONVERT_EDI, MAILBOX, etc. */
    private String stepType;

    /** SHA-256 key of the input file in storage-manager (content-addressed) */
    private String inputStorageKey;

    /** Virtual file path (metadata for logging/audit) */
    private String inputVirtualPath;

    /** Input file size in bytes */
    private long inputSizeBytes;

    /** Account ID for VFS scoping */
    private UUID accountId;

    /** Original filename (for RENAME step, MAILBOX delivery, audit) */
    private String originalFilename;

    /** Step-specific configuration (keyId, targetFormat, destinationUsername, etc.) */
    private Map<String, String> stepConfig;

    /** Current retry attempt (0 = first try, 1 = first retry, etc.) */
    @Builder.Default
    private int attempt = 0;

    /** Max retries for this step (from stepConfig or default 0) */
    @Builder.Default
    private int maxRetries = 0;

    /** When this message was first enqueued (for SLA/timeout tracking) */
    @Builder.Default
    private Instant enqueuedAt = Instant.now();
}
