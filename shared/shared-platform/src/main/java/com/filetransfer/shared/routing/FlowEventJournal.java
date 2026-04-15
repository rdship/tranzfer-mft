package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.transfer.FlowEvent;
import com.filetransfer.shared.repository.transfer.FlowEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Append-only event journal for flow executions.
 * All writes are async (fire-and-forget) to avoid blocking the hot path.
 * Reads are synchronous for recovery and debugging.
 */
@Service @RequiredArgsConstructor @Slf4j
public class FlowEventJournal {

    private final FlowEventRepository eventRepo;

    // ── Async event writers (fire-and-forget, non-blocking) ──────────────

    @Async
    public void recordFlowMatched(String trackId, UUID executionId, String flowName) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("FLOW_MATCHED")
                .metadata("{\"flowName\":\"" + escapeJson(flowName) + "\"}")
                .actor("system").build());
    }

    @Async
    public void recordExecutionStarted(String trackId, UUID executionId, String storageKey, int stepCount) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_STARTED")
                .storageKey(storageKey)
                .metadata("{\"stepCount\":" + stepCount + "}")
                .actor("system").build());
    }

    @Async
    public void recordStepStarted(String trackId, UUID executionId, int stepIndex, String stepType,
                                   String storageKey, int attempt) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("STEP_STARTED")
                .stepIndex(stepIndex).stepType(stepType)
                .storageKey(storageKey).attemptNumber(attempt)
                .actor("system").build());
    }

    @Async
    public void recordStepCompleted(String trackId, UUID executionId, int stepIndex, String stepType,
                                     String outputKey, long sizeBytes, long durationMs) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("STEP_COMPLETED")
                .stepIndex(stepIndex).stepType(stepType)
                .storageKey(outputKey).sizeBytes(sizeBytes)
                .durationMs(durationMs).status("OK")
                .actor("system").build());
    }

    @Async
    public void recordStepFailed(String trackId, UUID executionId, int stepIndex, String stepType,
                                  String error, int attempt) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("STEP_FAILED")
                .stepIndex(stepIndex).stepType(stepType)
                .errorMessage(truncate(error, 2000)).attemptNumber(attempt)
                .status("FAILED").actor("system").build());
    }

    @Async
    public void recordStepRetrying(String trackId, UUID executionId, int stepIndex, String stepType,
                                    int attempt, long backoffMs) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("STEP_RETRYING")
                .stepIndex(stepIndex).stepType(stepType)
                .attemptNumber(attempt).durationMs(backoffMs)
                .actor("system").build());
    }

    @Async
    public void recordExecutionPaused(String trackId, UUID executionId, int stepIndex, String reason) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_PAUSED")
                .stepIndex(stepIndex).status("PAUSED")
                .metadata("{\"reason\":\"" + escapeJson(reason) + "\"}")
                .actor("system").build());
    }

    @Async
    public void recordApprovalReceived(String trackId, UUID executionId, int stepIndex,
                                        String reviewer, String decision) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("APPROVAL_RECEIVED")
                .stepIndex(stepIndex).status(decision)
                .actor(reviewer).build());
    }

    @Async
    public void recordExecutionCompleted(String trackId, UUID executionId, long totalDurationMs, int stepsExecuted) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_COMPLETED")
                .durationMs(totalDurationMs).status("COMPLETED")
                .metadata("{\"stepsExecuted\":" + stepsExecuted + "}")
                .actor("system").build());
    }

    @Async
    public void recordExecutionFailed(String trackId, UUID executionId, String error) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_FAILED")
                .errorMessage(truncate(error, 2000)).status("FAILED")
                .actor("system").build());
    }

    @Async
    public void recordExecutionRestarted(String trackId, UUID executionId, int fromStep, String requestedBy) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_RESTARTED")
                .stepIndex(fromStep).actor(requestedBy).build());
    }

    @Async
    public void recordExecutionResumed(String trackId, UUID executionId, int fromStep, String resumedBy) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_RESUMED")
                .stepIndex(fromStep).actor(resumedBy).build());
    }

    @Async
    public void recordExecutionTerminated(String trackId, UUID executionId, String terminatedBy) {
        save(FlowEvent.builder()
                .trackId(trackId).executionId(executionId)
                .eventType("EXECUTION_TERMINATED")
                .status("CANCELLED").actor(terminatedBy).build());
    }

    // ── Synchronous readers (for recovery and debugging) ─────────────────

    /** Get full event history for a track ID — used for time-travel debugging. */
    public List<FlowEvent> getHistory(String trackId) {
        return eventRepo.findByTrackIdOrderByCreatedAtAsc(trackId);
    }

    /** Get events for a specific execution — used for actor recovery. */
    public List<FlowEvent> getExecutionEvents(UUID executionId) {
        return eventRepo.findByExecutionIdOrderByCreatedAtAsc(executionId);
    }

    /** Count events for a track ID — used for metrics. */
    public long eventCount(String trackId) {
        return eventRepo.countByTrackId(trackId);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void save(FlowEvent event) {
        try {
            eventRepo.save(event);
        } catch (Exception e) {
            log.warn("Failed to save flow event (non-blocking): {} — {}", event.getEventType(), e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
