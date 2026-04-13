package com.filetransfer.shared.scheduler;

import com.filetransfer.shared.entity.integration.ScheduledTask;
import com.filetransfer.shared.repository.ScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;

/**
 * Cron-based scheduler engine. Checks every 30 seconds for tasks due to run.
 * Supports: RUN_FLOW, PULL_FILES, PUSH_FILES, EXECUTE_SCRIPT, CLEANUP
 */
@Service @RequiredArgsConstructor @Slf4j
public class SchedulerEngine {

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunner taskExecutor;

    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "schedulerEngine_checkSchedule", lockAtLeastFor = "PT15S", lockAtMostFor = "PT5M")
    public void checkSchedule() {
        List<ScheduledTask> tasks = taskRepository.findByEnabledTrueOrderByNextRunAsc();
        Instant now = Instant.now();

        for (ScheduledTask task : tasks) {
            // Compute next run if not set
            if (task.getNextRun() == null) {
                task.setNextRun(computeNextRun(task.getCronExpression(), task.getTimezone()));
                taskRepository.save(task);
                continue;
            }

            if (now.isAfter(task.getNextRun()) || now.equals(task.getNextRun())) {
                executeTask(task);
            }
        }
    }

    private void executeTask(ScheduledTask task) {
        log.info("Scheduler: executing '{}' (type={}, cron={})", task.getName(), task.getTaskType(), task.getCronExpression());
        task.setLastRun(Instant.now());
        task.setLastStatus("RUNNING");
        task.setTotalRuns(task.getTotalRuns() + 1);
        taskRepository.save(task);

        try {
            taskExecutor.execute(task);
            task.setLastStatus("SUCCESS");
            task.setLastError(null);
            log.info("Scheduler: '{}' completed successfully", task.getName());
        } catch (Exception e) {
            task.setLastStatus("FAILED");
            task.setLastError(e.getMessage());
            task.setFailedRuns(task.getFailedRuns() + 1);
            log.error("Scheduler: '{}' failed: {}", task.getName(), e.getMessage());
        }

        task.setNextRun(computeNextRun(task.getCronExpression(), task.getTimezone()));
        taskRepository.save(task);
    }

    private Instant computeNextRun(String cron, String tz) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            ZoneId zone = tz != null ? ZoneId.of(tz) : ZoneOffset.UTC;
            LocalDateTime next = expr.next(LocalDateTime.now(zone));
            return next != null ? next.atZone(zone).toInstant() : null;
        } catch (Exception e) {
            log.warn("Invalid cron expression '{}': {}", cron, e.getMessage());
            return Instant.now().plusSeconds(3600); // fallback: 1 hour
        }
    }
}
