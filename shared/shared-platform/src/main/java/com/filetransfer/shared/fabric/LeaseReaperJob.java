package com.filetransfer.shared.fabric;

import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.transfer.FabricCheckpoint;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Detects stuck work (IN_PROGRESS checkpoints with expired leases) and schedules
 * crash-recovery retries through the existing {@link com.filetransfer.shared.routing.FlowRestartService}
 * path.
 *
 * <p>Runs every 60 seconds. For each stuck checkpoint:
 * <ol>
 *   <li>Mark the checkpoint as ABANDONED (transactional)</li>
 *   <li>Transition the owning {@link FlowExecution} from PROCESSING → FAILED
 *       with an error message identifying the dead instance</li>
 *   <li>Set {@code scheduledRetryAt = now()} so {@code ScheduledRetryExecutor}
 *       (onboarding-api) picks it up within the next minute and restarts the
 *       flow from the beginning via the already-tested restart path</li>
 *   <li>Log loudly for operator visibility</li>
 * </ol>
 *
 * <p><b>Why not republish to {@code flow.pipeline}?</b> An earlier iteration called
 * {@code FlowFabricBridge.publishStep()} to resume at the exact stuck step. That
 * only works if a consumer is subscribed to {@code flow.pipeline} — which none
 * currently is, in any tier. The fabric consume path today runs whole-flow
 * execution via {@code flow.intake → executeFlowViaFabric}, not per-step. Until
 * a per-step pipeline consumer is built, crash recovery restarts the flow from
 * step 0 — correct behavior for a crashed mid-flow step, and it reuses the
 * multi-instance-safe {@code clearScheduledRetry} race guard.
 *
 * <p><b>Tier coverage:</b>
 * <ul>
 *   <li><b>Tier 1</b> (fabric off) — reaper gated off; never runs.</li>
 *   <li><b>Tier 2</b> (in-memory fabric, no Kafka) — reaper runs, schedules DB-mediated retry. Works.</li>
 *   <li><b>Tier 3</b> (Kafka) — reaper runs, schedules DB-mediated retry. Works.</li>
 * </ul>
 *
 * <p>ShedLock ensures only one pod runs this at a time across the cluster.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaseReaperJob {

    private final FabricCheckpointRepository checkpointRepo;
    private final FlowExecutionRepository executionRepo;
    private final FabricProperties properties;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "fabricLeaseReaper", lockAtLeastFor = "55s", lockAtMostFor = "5m")
    @Transactional
    public void reapStuckWork() {
        if (!properties.isEnabled() || !properties.getCheckpoint().isEnabled()) {
            return;
        }

        List<FabricCheckpoint> stuck;
        try {
            stuck = checkpointRepo.findStuckCheckpoints(Instant.now());
        } catch (Exception e) {
            log.warn("[LeaseReaper] Query for stuck checkpoints failed: {}", e.getMessage());
            return;
        }

        if (stuck.isEmpty()) return;

        log.warn("[LeaseReaper] Found {} stuck checkpoints - reaping", stuck.size());

        int reaped = 0;
        int scheduled = 0;

        for (FabricCheckpoint cp : stuck) {
            try {
                String originalInstance = cp.getProcessingInstance();
                cp.setStatus("ABANDONED");
                cp.setErrorCategory("LEASE_EXPIRED");
                cp.setErrorMessage("Lease expired after instance " + originalInstance + " failed to heartbeat");
                cp.setCompletedAt(Instant.now());
                checkpointRepo.save(cp);
                reaped++;

                log.warn("[LeaseReaper] Abandoned stuck checkpoint trackId={} step={} type={} instance={}",
                    cp.getTrackId(), cp.getStepIndex(), cp.getStepType(), originalInstance);

                // Schedule crash-recovery restart via the existing retry path.
                // ScheduledRetryExecutor polls scheduled_retry_at every 60s and calls
                // FlowRestartService.restartFromBeginning — which requires the execution
                // to be in a non-PROCESSING state, so transition it to FAILED first.
                if (scheduleRestart(cp, originalInstance)) {
                    scheduled++;
                }
            } catch (Exception e) {
                log.error("[LeaseReaper] Failed to process stuck checkpoint {}: {}",
                    cp.getId(), e.getMessage());
            }
        }

        log.warn("[LeaseReaper] Completed: {} abandoned, {} scheduled for restart", reaped, scheduled);
    }

    /**
     * Transition a stuck FlowExecution to FAILED and set scheduledRetryAt=now(),
     * so ScheduledRetryExecutor picks it up and calls restartFromBeginning.
     *
     * @return true if a restart was scheduled, false if the execution is missing or already terminal
     */
    private boolean scheduleRestart(FabricCheckpoint cp, String deadInstance) {
        Optional<FlowExecution> opt = executionRepo.findByTrackId(cp.getTrackId());
        if (opt.isEmpty()) {
            log.warn("[LeaseReaper] No FlowExecution for trackId={} - cannot schedule restart",
                cp.getTrackId());
            return false;
        }

        FlowExecution exec = opt.get();
        FlowExecution.FlowStatus status = exec.getStatus();

        // Already terminal or already scheduled — nothing to do
        if (status == FlowExecution.FlowStatus.COMPLETED
            || status == FlowExecution.FlowStatus.CANCELLED) {
            log.info("[LeaseReaper] trackId={} already in terminal state {} - skipping restart",
                cp.getTrackId(), status);
            return false;
        }
        if (exec.getScheduledRetryAt() != null) {
            log.info("[LeaseReaper] trackId={} already has scheduledRetryAt={} - skipping",
                cp.getTrackId(), exec.getScheduledRetryAt());
            return false;
        }

        // Transition PROCESSING/PENDING → FAILED so restartFromBeginning will accept it
        exec.setStatus(FlowExecution.FlowStatus.FAILED);
        if (exec.getErrorMessage() == null) {
            exec.setErrorMessage("Lease expired at step " + cp.getStepIndex()
                + " (" + cp.getStepType() + ") - instance " + deadInstance + " died mid-step");
        }
        if (exec.getCompletedAt() == null) {
            exec.setCompletedAt(Instant.now());
        }
        exec.setScheduledRetryAt(Instant.now());
        exec.setScheduledRetryBy("lease-reaper");
        executionRepo.save(exec);

        log.warn("[LeaseReaper] trackId={} scheduled for restart (was {}, now FAILED + scheduledRetryAt=now)",
            cp.getTrackId(), status);
        return true;
    }
}
