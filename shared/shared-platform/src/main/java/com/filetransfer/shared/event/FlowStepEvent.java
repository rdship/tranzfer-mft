package com.filetransfer.shared.event;

import java.util.UUID;

/**
 * Fired by {@link com.filetransfer.shared.routing.FlowProcessingEngine} after every
 * VIRTUAL-mode pipeline step — both on success and failure.
 *
 * <p><b>Fire-and-forget contract:</b> the publisher calls
 * {@code ApplicationEventPublisher.publishEvent(event)} and returns immediately.
 * The actual DB write happens asynchronously in {@link FlowStepEventListener}
 * on the shared task-executor thread pool. The hot path is never blocked.
 *
 * <p>Plain record — Spring 4.2+ supports publishing any object as an event.
 * No {@code ApplicationEvent} superclass needed.
 */
public record FlowStepEvent(
        String  trackId,
        UUID    flowExecutionId,    // exec.getId() — set after first save
        int     stepIndex,
        String  stepType,
        String  stepStatus,         // OK | OK_AFTER_RETRY_N | FAILED

        /** SHA-256 of the file entering this step (before transform). */
        String  inputStorageKey,
        /** SHA-256 of the file leaving this step (after transform). Null on failure. */
        String  outputStorageKey,

        String  inputVirtualPath,
        String  outputVirtualPath,  // null on failure

        long    inputSizeBytes,
        long    outputSizeBytes,    // 0 on failure
        long    durationMs,
        String  errorMessage,       // null on success

        /** R105b: rich per-step semantic detail as JSON. Null when step type emits none. */
        String  stepDetailsJson,
        /** R105b: rows processed (data steps only). Null when not applicable. */
        Long    rowsProcessed,
        /** R105b: replica that executed this step. */
        String  processingInstance,
        /** R105b: 1-based attempt number that produced this snapshot. */
        Integer attemptCount,
        /** R105b: step config used (JSON). Null when config is empty. */
        String  stepConfigJson
) {
    /** Convenience constructor for pre-R105 call sites — populates R105b fields with nulls. */
    public FlowStepEvent(String trackId, UUID flowExecutionId, int stepIndex, String stepType,
                          String stepStatus, String inputStorageKey, String outputStorageKey,
                          String inputVirtualPath, String outputVirtualPath,
                          long inputSizeBytes, long outputSizeBytes, long durationMs,
                          String errorMessage) {
        this(trackId, flowExecutionId, stepIndex, stepType, stepStatus,
                inputStorageKey, outputStorageKey, inputVirtualPath, outputVirtualPath,
                inputSizeBytes, outputSizeBytes, durationMs, errorMessage,
                null, null, null, null, null);
    }
}
