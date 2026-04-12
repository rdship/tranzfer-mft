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
 * Recovers stuck flow executions that have been in PROCESSING state
 * for longer than 30 minutes (likely due to JVM crash or deadlock).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowExecutionRecoveryJob {

    private final FlowExecutionRepository executionRepository;

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    @SchedulerLock(name = "flow-execution-recovery", lockAtMostFor = "PT4M")
    @Transactional
    public void recoverStuckExecutions() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(30));
        List<FlowExecution> stuck = executionRepository
                .findByStatusAndStartedAtBefore(FlowExecution.FlowStatus.PROCESSING, threshold);

        for (FlowExecution exec : stuck) {
            log.warn("Recovering stuck execution: trackId={} flow={} startedAt={}",
                    exec.getTrackId(),
                    exec.getFlow() != null ? exec.getFlow().getName() : "N/A",
                    exec.getStartedAt());
            exec.setStatus(FlowExecution.FlowStatus.FAILED);
            exec.setErrorMessage("Recovered: stuck in PROCESSING for > 30 minutes");
            exec.setCompletedAt(Instant.now());
            executionRepository.save(exec);
        }

        if (!stuck.isEmpty()) {
            log.info("Recovered {} stuck flow execution(s)", stuck.size());
        }
    }
}
