package com.filetransfer.storage.coordination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Hosts the {@link StorageCoordinationService#reapExpiredLeases()} schedule.
 * In a multi-replica storage-manager deployment we want exactly one reaper
 * running — ShedLock gates the schedule via the existing
 * {@code shedlock_entries} table so only the replica that holds the lock
 * actually runs the purge.
 *
 * <p>Kept as a separate {@code @Configuration} class so the service class
 * stays pure (no Spring-scheduled annotations, simpler to unit-test).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CoordinationSchedulerConfig {

    private final StorageCoordinationService service;

    /**
     * Purge expired leases every 30 seconds. lockAtMostFor caps how long one
     * replica can hold the ShedLock token — if a replica dies mid-reap, at
     * most 2 minutes pass before a different replica takes over.
     */
    @Scheduled(fixedDelayString = "PT30S")
    @SchedulerLock(name = "platform_locks_reaper",
                   lockAtMostFor = "PT2M",
                   lockAtLeastFor = "PT10S")
    public void reap() {
        try {
            service.reapExpiredLeases();
        } catch (Exception e) {
            log.error("[StorageCoordination] reaper run failed — leases may accumulate; "
                      + "next run in 30s: {}", e.getMessage(), e);
        }
    }
}
