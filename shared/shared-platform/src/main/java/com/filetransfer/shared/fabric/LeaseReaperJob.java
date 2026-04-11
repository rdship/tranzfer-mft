package com.filetransfer.shared.fabric;

import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.FabricCheckpoint;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Detects stuck work (IN_PROGRESS checkpoints with expired leases).
 *
 * Runs every 60 seconds. For each stuck checkpoint:
 * 1. Mark as ABANDONED (transactional)
 * 2. Republish the work item to flow.pipeline so another instance picks up
 * 3. Log loudly for operator visibility
 *
 * ShedLock ensures only one pod runs this at a time across the cluster.
 * Fabric must be enabled; otherwise this job is a no-op.
 *
 * <p><b>Phase 5 note — subsumes the delayed-retry topic:</b> The original
 * Phase 5 plan called for a separate {@code flow.retry.scheduled} delayed
 * topic mechanism to handle crash-recovery retries. That work is now
 * covered here: when a pod dies mid-step, its lease expires and this job
 * republishes the work item directly to {@code flow.pipeline}. Another
 * instance picks it up through the normal consumer path — no extra
 * delayed-topic plumbing required. User-scheduled retries (UI/API driven)
 * continue to flow through {@code ScheduledRetryExecutor}, which is
 * already multi-instance safe via its own ShedLock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaseReaperJob {

    private final FabricCheckpointRepository checkpointRepo;
    private final FlowFabricBridge fabricBridge;
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
        int republished = 0;

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

                // Republish to pipeline so another instance picks it up
                try {
                    fabricBridge.publishStep(
                        cp.getTrackId(),
                        cp.getStepIndex(),
                        cp.getStepType(),
                        cp.getInputStorageKey()
                    );
                    republished++;
                } catch (Exception e) {
                    log.error("[LeaseReaper] Failed to republish trackId={} step={}: {}",
                        cp.getTrackId(), cp.getStepIndex(), e.getMessage());
                }
            } catch (Exception e) {
                log.error("[LeaseReaper] Failed to process stuck checkpoint {}: {}",
                    cp.getId(), e.getMessage());
            }
        }

        log.warn("[LeaseReaper] Completed: {} abandoned, {} republished", reaped, republished);
    }
}
