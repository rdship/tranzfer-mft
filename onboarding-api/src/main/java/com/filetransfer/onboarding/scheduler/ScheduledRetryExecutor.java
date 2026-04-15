package com.filetransfer.onboarding.scheduler;

import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.routing.FlowRestartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Polls every 60 seconds for flow executions whose scheduled retry time has arrived,
 * then fires {@link FlowRestartService#restartFromBeginning} for each due execution.
 *
 * <p><b>Double-trigger prevention</b> (multi-instance safe): before restarting,
 * {@link FlowExecutionRepository#clearScheduledRetry} issues a conditional
 * {@code UPDATE … WHERE scheduled_retry_at IS NOT NULL}. Only the instance that
 * updates 1 row wins; other instances get 0 and skip. No ShedLock required.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledRetryExecutor {

    private static final List<FlowExecution.FlowStatus> RETRYABLE = List.of(
            FlowExecution.FlowStatus.FAILED,
            FlowExecution.FlowStatus.CANCELLED,
            FlowExecution.FlowStatus.UNMATCHED
    );

    private final FlowExecutionRepository executionRepo;
    private final FlowRestartService restartService;

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void executeScheduledRetries() {
        List<FlowExecution> due = executionRepo.findDueForRetry(Instant.now(), RETRYABLE);
        if (due.isEmpty()) return;

        log.info("ScheduledRetryExecutor: {} execution(s) due for retry", due.size());

        for (FlowExecution exec : due) {
            // Atomic clear — only the instance that clears 1 row triggers the restart
            int cleared = executionRepo.clearScheduledRetry(exec.getId());
            if (cleared == 0) {
                log.debug("[{}] Scheduled retry already claimed by another instance — skipping", exec.getTrackId());
                continue;
            }

            String by = (exec.getScheduledRetryBy() != null ? exec.getScheduledRetryBy() : "scheduler") + " (scheduled)";
            log.info("[{}] Triggering scheduled retry — requested by {}", exec.getTrackId(), by);
            try {
                restartService.restartFromBeginning(exec.getTrackId(), by);
            } catch (Exception e) {
                log.error("[{}] Scheduled retry failed to dispatch: {}", exec.getTrackId(), e.getMessage());
            }
        }
    }
}
