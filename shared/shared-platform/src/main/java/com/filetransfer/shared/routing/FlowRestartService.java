package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.entity.transfer.FlowStepSnapshot;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
import com.filetransfer.shared.vfs.FileRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Admin-initiated flow execution lifecycle operations.
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li><b>Restart from beginning</b> — re-run all steps using the original input file
 *       (from {@code initialStorageKey} or step-0 snapshot).
 *   <li><b>Restart from step N</b> — skip steps 0…N-1, start at step N using that step's
 *       captured input key ({@link FlowStepSnapshot#getInputStorageKey}).
 *   <li><b>Terminate</b> — set {@code terminationRequested=true}; the running agent polls
 *       this flag between steps and exits cleanly with status CANCELLED.
 * </ul>
 *
 * <p>All operations write an audit record via {@link AuditService}.
 * Restart methods run {@code @Async} — the HTTP request returns immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowRestartService {

    private final FlowExecutionRepository executionRepo;
    private final FlowStepSnapshotRepository snapshotRepo;
    private final FileFlowRepository flowRepo;
    private final FlowProcessingEngine flowEngine;
    private final AuditService auditService;

    // ── Restart ──────────────────────────────────────────────────────────────

    /**
     * Restart a failed/cancelled execution from the beginning.
     *
     * @param trackId     execution to restart
     * @param requestedBy admin username (from JWT)
     */
    @Async
    public void restartFromBeginning(String trackId, String requestedBy) {
        try {
            FlowExecution exec = loadRestartable(trackId);
            FileFlow flow = resolveFlow(exec);
            String startKey = resolveInitialKey(exec, trackId);

            archiveCurrentAttempt(exec);
            exec.setAttemptNumber(exec.getAttemptNumber() + 1);
            exec.setRestartedBy(requestedBy);
            exec.setRestartedAt(Instant.now());

            FileRef ref = buildRef(exec, startKey);
            log.info("[{}] RESTART (from beginning) by {} — attempt {}",
                    trackId, requestedBy, exec.getAttemptNumber());

            auditService.logAction(requestedBy, "FLOW_RESTART",
                    true, null,
                    Map.of("trackId", trackId, "fromStep", 0,
                           "attempt", exec.getAttemptNumber(), "by", requestedBy));

            flowEngine.executeFlowRef(flow, trackId, exec.getOriginalFilename(), ref,
                    exec.getMatchedCriteria(), exec, 0);

        } catch (Exception e) {
            auditService.logAction(requestedBy, "FLOW_RESTART", false, e.getMessage(),
                    Map.of("trackId", trackId, "error", e.getMessage()));
            log.error("[{}] RESTART failed: {}", trackId, e.getMessage());
        }
    }

    /**
     * Restart from step {@code fromStep} using that step's captured input file.
     *
     * @param fromStep 0-based step index to restart from
     */
    @Async
    public void restartFromStep(String trackId, int fromStep, String requestedBy) {
        try {
            FlowExecution exec = loadRestartable(trackId);
            FileFlow flow = resolveFlow(exec);

            // Use the snapshot's inputStorageKey for this step as the restart point
            FlowStepSnapshot snap = snapshotRepo.findByTrackIdAndStepIndex(trackId, fromStep)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No step snapshot for trackId=" + trackId + " step=" + fromStep
                            + ". Run at least one full attempt first."));

            String restartKey = snap.getInputStorageKey();
            if (restartKey == null) throw new IllegalStateException(
                    "Step " + fromStep + " snapshot has no inputStorageKey");

            archiveCurrentAttempt(exec);
            exec.setAttemptNumber(exec.getAttemptNumber() + 1);
            exec.setRestartedBy(requestedBy);
            exec.setRestartedAt(Instant.now());

            FileRef ref = new FileRef(
                    restartKey,
                    snap.getInputVirtualPath() != null ? snap.getInputVirtualPath()
                            : "/" + exec.getOriginalFilename(),
                    exec.getFlow() != null && exec.getFlow().getId() != null
                            ? null : null,  // accountId resolved by VfsFlowBridge
                    snap.getInputSizeBytes() != null ? snap.getInputSizeBytes() : -1L,
                    trackId,
                    null,
                    "STANDARD");

            log.info("[{}] RESTART (from step {}) by {} — attempt {}",
                    trackId, fromStep, requestedBy, exec.getAttemptNumber());

            auditService.logAction(requestedBy, "FLOW_RESTART_FROM_STEP",
                    true, null,
                    Map.of("trackId", trackId, "fromStep", fromStep,
                           "restartKey", restartKey.substring(0, Math.min(12, restartKey.length())),
                           "attempt", exec.getAttemptNumber(), "by", requestedBy));

            flowEngine.executeFlowRef(flow, trackId, exec.getOriginalFilename(), ref,
                    exec.getMatchedCriteria(), exec, fromStep);

        } catch (Exception e) {
            auditService.logAction(requestedBy, "FLOW_RESTART_FROM_STEP", false, e.getMessage(),
                    Map.of("trackId", trackId, "fromStep", fromStep, "error", e.getMessage()));
            log.error("[{}] RESTART from step {} failed: {}", trackId, fromStep, e.getMessage());
        }
    }

    /**
     * Skip step {@code stepIndex} and resume execution from step {@code stepIndex + 1}.
     *
     * <p>Uses the skipped step's {@link FlowStepSnapshot#getInputStorageKey()} as the input
     * to the next step — treating the skip as a no-op pass-through.
     * Useful when a step is permanently broken (e.g., a script step that can never succeed)
     * and you want the file to continue down the pipeline unchanged.
     *
     * @param stepIndex 0-based index of the step to skip
     * @throws IllegalArgumentException if no snapshot exists for the step, or it's the last step
     */
    @Async
    public void skipStep(String trackId, int stepIndex, String requestedBy) {
        try {
            FlowExecution exec = loadRestartable(trackId);
            FileFlow flow = resolveFlow(exec);

            int totalSteps = flow.getSteps() != null ? flow.getSteps().size() : 0;
            if (stepIndex + 1 >= totalSteps) {
                throw new IllegalArgumentException(
                        "Cannot skip the last step (step " + stepIndex + " of " + totalSteps +
                        "). Use terminate instead.");
            }

            FlowStepSnapshot snap = snapshotRepo.findByTrackIdAndStepIndex(trackId, stepIndex)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No step snapshot for trackId=" + trackId + " step=" + stepIndex +
                            ". Run at least one full attempt first so a snapshot exists."));

            String skipKey = snap.getInputStorageKey();
            if (skipKey == null) throw new IllegalStateException(
                    "Step " + stepIndex + " snapshot has no inputStorageKey — cannot skip");

            archiveCurrentAttempt(exec);
            exec.setAttemptNumber(exec.getAttemptNumber() + 1);
            exec.setRestartedBy(requestedBy);
            exec.setRestartedAt(Instant.now());

            // Pass the SKIPPED step's input straight to step N+1 (skip = no-op pass-through)
            FileRef ref = new FileRef(
                    skipKey,
                    snap.getInputVirtualPath() != null ? snap.getInputVirtualPath()
                            : "/" + exec.getOriginalFilename(),
                    null,
                    snap.getInputSizeBytes() != null ? snap.getInputSizeBytes() : -1L,
                    trackId,
                    null,
                    "STANDARD");

            log.info("[{}] SKIP step {} by {} — resuming at step {} (attempt {})",
                    trackId, stepIndex, requestedBy, stepIndex + 1, exec.getAttemptNumber());

            auditService.logAction(requestedBy, "FLOW_SKIP_STEP",
                    true, null,
                    Map.of("trackId", trackId, "skippedStep", stepIndex,
                           "resumeAtStep", stepIndex + 1,
                           "attempt", exec.getAttemptNumber(), "by", requestedBy));

            flowEngine.executeFlowRef(flow, trackId, exec.getOriginalFilename(), ref,
                    exec.getMatchedCriteria(), exec, stepIndex + 1);

        } catch (Exception e) {
            auditService.logAction(requestedBy, "FLOW_SKIP_STEP", false, e.getMessage(),
                    Map.of("trackId", trackId, "stepIndex", stepIndex, "error", e.getMessage()));
            log.error("[{}] SKIP step {} failed: {}", trackId, stepIndex, e.getMessage());
        }
    }

    // ── Terminate ─────────────────────────────────────────────────────────────

    /**
     * Request termination of a flow execution.
     *
     * <p>For PROCESSING flows: sets the {@code terminationRequested} flag.
     * The running agent reads this between steps and exits with status CANCELLED.
     * For already-terminal states (FAILED, COMPLETED, CANCELLED): marks directly as CANCELLED.
     */
    @Transactional
    public void terminate(String trackId, String requestedBy) {
        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + trackId));

        if (exec.getStatus() == FlowExecution.FlowStatus.CANCELLED) {
            throw new IllegalStateException("Execution already cancelled");
        }

        exec.setTerminationRequested(true);
        exec.setTerminatedBy(requestedBy);
        exec.setTerminatedAt(Instant.now());

        if (exec.getStatus() != FlowExecution.FlowStatus.PROCESSING) {
            // Already stopped — flip to CANCELLED immediately
            exec.setStatus(FlowExecution.FlowStatus.CANCELLED);
            exec.setErrorMessage("Terminated by " + requestedBy);
            exec.setCompletedAt(Instant.now());
        }

        executionRepo.save(exec);

        log.info("[{}] TERMINATE requested by {}, current status={}",
                trackId, requestedBy, exec.getStatus());

        auditService.logAction(requestedBy, "FLOW_TERMINATE",
                true, null,
                Map.of("trackId", trackId, "previousStatus", exec.getStatus().name(), "by", requestedBy));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FlowExecution loadRestartable(String trackId) {
        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + trackId));
        if (exec.getStatus() == FlowExecution.FlowStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Cannot restart a PROCESSING execution. Terminate it first.");
        }
        if (exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Execution is COMPLETED. Create a replay if you want to re-process.");
        }
        return exec;
    }

    private FileFlow resolveFlow(FlowExecution exec) {
        if (exec.getFlow() == null) throw new IllegalStateException(
                "Execution has no associated flow (UNMATCHED execution cannot be restarted)");
        return flowRepo.findById(exec.getFlow().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Flow no longer exists: " + exec.getFlow().getId()));
    }

    private String resolveInitialKey(FlowExecution exec, String trackId) {
        // Primary: initialStorageKey set at creation
        if (exec.getInitialStorageKey() != null) return exec.getInitialStorageKey();
        // Fallback: step-0 snapshot inputStorageKey
        return snapshotRepo.findByTrackIdAndStepIndex(trackId, 0)
                .map(FlowStepSnapshot::getInputStorageKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot determine original file key for trackId=" + trackId
                        + ". No initialStorageKey and no step-0 snapshot."));
    }

    private FileRef buildRef(FlowExecution exec, String storageKey) {
        String virtualPath = exec.getCurrentFilePath() != null
                ? exec.getCurrentFilePath()
                : "/inbox/" + exec.getOriginalFilename();
        return new FileRef(storageKey, virtualPath, null, -1L,
                exec.getTrackId(), null, "STANDARD");
    }

    /**
     * Move the current step results + error to {@code attemptHistory} JSONB,
     * so each restart attempt is fully auditable.
     */
    private void archiveCurrentAttempt(FlowExecution exec) {
        if (exec.getStepResults() == null || exec.getStepResults().isEmpty()) return;

        List<Map<String, Object>> history = exec.getAttemptHistory() != null
                ? new ArrayList<>(exec.getAttemptHistory()) : new ArrayList<>();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("attempt",       exec.getAttemptNumber());
        record.put("startedAt",     exec.getStartedAt() != null ? exec.getStartedAt().toString() : null);
        record.put("failedAt",      exec.getCompletedAt() != null ? exec.getCompletedAt().toString() : null);
        record.put("status",        exec.getStatus().name());
        record.put("errorMessage",  exec.getErrorMessage());
        record.put("stepCount",     exec.getStepResults().size());

        history.add(record);
        exec.setAttemptHistory(history);
    }
}
