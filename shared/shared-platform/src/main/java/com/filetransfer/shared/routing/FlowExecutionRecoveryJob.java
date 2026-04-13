package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 7.3: Recovers stuck flow executions in PROCESSING state > 5 minutes.
 *
 * <p>Strategy:
 * <ul>
 *   <li>If execution has a checkpoint (currentStorageKey): mark PENDING for retry from checkpoint</li>
 *   <li>If no checkpoint: mark FAILED with descriptive error</li>
 *   <li>Tracks recovery count for observability</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@org.springframework.context.annotation.Lazy(false)
public class FlowExecutionRecoveryJob {

    private final FlowExecutionRepository executionRepository;
    private volatile long totalRecovered;
    private volatile long totalRestarted;

    @Scheduled(fixedDelay = 60_000) // Phase 7.3: every 60s (was 5 min)
    @SchedulerLock(name = "flow-execution-recovery", lockAtMostFor = "PT50S")
    @Transactional
    public void recoverStuckExecutions() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(2)); // 2 min (was 5)
        List<FlowExecution> stuck = executionRepository
                .findByStatusAndStartedAtBefore(FlowExecution.FlowStatus.PROCESSING, threshold);

        int recovered = 0, restarted = 0;
        for (FlowExecution exec : stuck) {
            String flowName = exec.getFlow() != null ? exec.getFlow().getName() : "N/A";

            if (exec.getCurrentStorageKey() != null && exec.getAttemptNumber() < 3) {
                // Has checkpoint + retries left → mark PENDING for auto-restart
                exec.setStatus(FlowExecution.FlowStatus.PENDING);
                exec.setAttemptNumber(exec.getAttemptNumber() + 1);
                exec.setErrorMessage(null); // Clear previous error
                executionRepository.save(exec);
                restarted++;
                log.info("[{}] Flow '{}' recovered: restarting from step {} (attempt {})",
                        exec.getTrackId(), flowName, exec.getCurrentStep(), exec.getAttemptNumber());
            } else {
                // No checkpoint or max retries exceeded → FAILED
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage(exec.getCurrentStorageKey() != null
                        ? "Recovered: max retries exceeded (attempt " + exec.getAttemptNumber() + ")"
                        : "Recovered: stuck in PROCESSING > 2 min, no checkpoint available");
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                recovered++;
                log.warn("[{}] Flow '{}' FAILED after recovery: {}", exec.getTrackId(), flowName, exec.getErrorMessage());
            }
        }

        if (recovered + restarted > 0) {
            totalRecovered += recovered;
            totalRestarted += restarted;
            log.info("Recovery sweep: {} restarted, {} marked FAILED (total: {} restarted, {} failed)",
                    restarted, recovered, totalRestarted, totalRecovered);
        }
    }

    /** Phase 6: recovery stats for pipeline health. */
    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
                "totalRecovered", totalRecovered,
                "totalRestarted", totalRestarted,
                "checkIntervalMs", 60000,
                "stuckThresholdMin", 2
        );
    }
}
