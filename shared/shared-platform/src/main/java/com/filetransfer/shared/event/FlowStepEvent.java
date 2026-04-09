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
        String  errorMessage        // null on success
) {}
