package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FlowApproval;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FlowApprovalRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.vfs.FileRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-facing lifecycle operations for APPROVE flow steps.
 *
 * <p>When the flow engine hits an APPROVE step it pauses the execution and creates a
 * {@link FlowApproval} record (status=PENDING).  This service handles the two outcomes:
 *
 * <ul>
 *   <li><b>Approve</b> — marks the approval record APPROVED, then resumes the flow from the step
 *       immediately after the APPROVE gate (@Async, non-blocking).
 *   <li><b>Reject</b> — marks the approval record REJECTED and cancels the execution.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowApprovalService {

    private final FlowApprovalRepository approvalRepo;
    private final FlowExecutionRepository executionRepo;
    private final FileFlowRepository flowRepo;
    private final FlowProcessingEngine flowEngine;
    private final AuditService auditService;

    // ── Query ─────────────────────────────────────────────────────────────────

    public List<FlowApproval> getPendingApprovals() {
        return approvalRepo.findByStatusOrderByRequestedAtDesc(FlowApproval.ApprovalStatus.PENDING);
    }

    public List<FlowApproval> getApprovalsForTrack(String trackId) {
        return approvalRepo.findByTrackIdOrderByStepIndex(trackId);
    }

    // ── Approve ───────────────────────────────────────────────────────────────

    /**
     * Mark the pending approval as APPROVED and asynchronously resume the flow.
     *
     * @param trackId   execution track ID
     * @param stepIndex 0-based index of the APPROVE step
     * @param reviewer  admin username
     * @param note      optional reviewer note
     */
    @Transactional
    public void approve(String trackId, int stepIndex, String reviewer, String note) {
        FlowApproval approval = loadPendingApproval(trackId, stepIndex);

        approval.setStatus(FlowApproval.ApprovalStatus.APPROVED);
        approval.setReviewedAt(Instant.now());
        approval.setReviewedBy(reviewer);
        approval.setReviewNote(note);
        approvalRepo.save(approval);

        FlowExecution exec = loadPausedExecution(trackId);

        log.info("[{}] APPROVED at step {} by {}", trackId, stepIndex, reviewer);
        auditService.logAction(reviewer, "FLOW_APPROVE", true, null,
                Map.of("trackId", trackId, "stepIndex", stepIndex, "by", reviewer));

        // Snapshot values for async continuation (avoids lazy-load issues across tx boundary)
        final String storageKey  = approval.getPausedStorageKey();
        final String virtualPath = approval.getPausedVirtualPath() != null
                ? approval.getPausedVirtualPath() : "/inbox/" + exec.getOriginalFilename();
        final long   sizeBytes   = approval.getPausedSizeBytes() != null
                ? approval.getPausedSizeBytes() : -1L;
        final UUID   execId      = exec.getId();
        final int    nextStep    = stepIndex + 1;

        // Resume async so HTTP request returns immediately
        continueAfterApproval(trackId, execId, nextStep, storageKey, virtualPath, sizeBytes, reviewer);
    }

    /**
     * Resumes the paused flow execution from {@code nextStep} in a new transaction on a
     * virtual thread.  Called via Spring proxy so {@code @Async} fires correctly.
     */
    @Async
    @Transactional
    public void continueAfterApproval(String trackId, UUID executionId, int nextStep,
                                       String storageKey, String virtualPath, long sizeBytes,
                                       String reviewer) {
        try {
            FlowExecution exec = executionRepo.findById(executionId)
                    .orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
            FileFlow flow = flowRepo.findById(exec.getFlow().getId())
                    .orElseThrow(() -> new IllegalStateException("Flow no longer exists"));

            exec.setStatus(FlowExecution.FlowStatus.PROCESSING);
            exec.setErrorMessage(null);
            exec.setCompletedAt(null);
            exec.setCurrentStep(nextStep);
            exec.setTerminationRequested(false);
            exec.setRestartedBy(reviewer);
            exec.setRestartedAt(Instant.now());

            FileRef ref = new FileRef(storageKey, virtualPath, null, sizeBytes,
                    trackId, null, "STANDARD");

            log.info("[{}] Resuming flow from step {} after approval by {}", trackId, nextStep, reviewer);

            flowEngine.executeFlowRef(flow, trackId, exec.getOriginalFilename(), ref,
                    exec.getMatchedCriteria(), exec, nextStep);

        } catch (Exception e) {
            auditService.logAction(reviewer, "FLOW_APPROVE_RESUME", false, e.getMessage(),
                    Map.of("trackId", trackId, "nextStep", nextStep, "error", e.getMessage()));
            log.error("[{}] Flow resume after approval failed: {}", trackId, e.getMessage());
        }
    }

    // ── Reject ────────────────────────────────────────────────────────────────

    /**
     * Mark the pending approval as REJECTED and cancel the execution.
     *
     * @param trackId   execution track ID
     * @param stepIndex 0-based index of the APPROVE step
     * @param reviewer  admin username
     * @param note      required rejection reason
     */
    @Transactional
    public void reject(String trackId, int stepIndex, String reviewer, String note) {
        FlowApproval approval = loadPendingApproval(trackId, stepIndex);

        approval.setStatus(FlowApproval.ApprovalStatus.REJECTED);
        approval.setReviewedAt(Instant.now());
        approval.setReviewedBy(reviewer);
        approval.setReviewNote(note);
        approvalRepo.save(approval);

        FlowExecution exec = loadPausedExecution(trackId);
        exec.setStatus(FlowExecution.FlowStatus.CANCELLED);
        exec.setErrorMessage("Rejected by " + reviewer + " at step " + stepIndex
                + (note != null && !note.isBlank() ? ": " + note : ""));
        exec.setTerminatedBy(reviewer);
        exec.setTerminatedAt(Instant.now());
        exec.setCompletedAt(Instant.now());
        executionRepo.save(exec);

        log.info("[{}] REJECTED at step {} by {} — reason: {}", trackId, stepIndex, reviewer, note);
        auditService.logAction(reviewer, "FLOW_REJECT", true, null,
                Map.of("trackId", trackId, "stepIndex", stepIndex, "by", reviewer,
                       "reason", note != null ? note : ""));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FlowApproval loadPendingApproval(String trackId, int stepIndex) {
        FlowApproval approval = approvalRepo.findByTrackIdAndStepIndex(trackId, stepIndex)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No approval record for trackId=" + trackId + " step=" + stepIndex));
        if (approval.getStatus() != FlowApproval.ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "Approval already " + approval.getStatus() + " for trackId=" + trackId);
        }
        return approval;
    }

    private FlowExecution loadPausedExecution(String trackId) {
        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + trackId));
        if (exec.getStatus() != FlowExecution.FlowStatus.PAUSED) {
            throw new IllegalStateException(
                    "Execution is not PAUSED (status=" + exec.getStatus() + ")");
        }
        return exec;
    }
}
