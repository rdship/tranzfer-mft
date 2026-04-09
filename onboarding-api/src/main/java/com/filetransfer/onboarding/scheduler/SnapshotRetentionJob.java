package com.filetransfer.onboarding.scheduler;

import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.repository.FlowStepSnapshotRepository;
import com.filetransfer.shared.repository.PlatformSettingRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daily purge of old {@link com.filetransfer.shared.entity.FlowStepSnapshot} records.
 *
 * <p>Runs at 02:00 UTC every day. Reads the retention period from the
 * {@code snapshot.retention.days} platform setting (default 90). If retention is 0,
 * the job is a no-op (retention disabled).
 *
 * <p>ShedLock ensures only one instance runs the purge in a multi-pod deployment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotRetentionJob {

    private static final String SETTING_KEY = "snapshot.retention.days";
    private static final int    DEFAULT_DAYS = 90;

    private final FlowStepSnapshotRepository snapshotRepo;
    private final PlatformSettingRepository  settingRepo;

    /** In-memory stats for the management endpoint — reset on restart, that's acceptable. */
    @Getter private final AtomicLong          lastPurgeCount  = new AtomicLong(-1);
    @Getter private final AtomicReference<Instant> lastPurgeAt = new AtomicReference<>(null);

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @SchedulerLock(name = "snapshot-retention-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        int days = readRetentionDays();
        if (days <= 0) {
            log.debug("SnapshotRetentionJob: retention disabled (days={})", days);
            return;
        }

        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long eligible  = snapshotRepo.countByCreatedAtBefore(cutoff);

        if (eligible == 0) {
            log.debug("SnapshotRetentionJob: no snapshots older than {} days — nothing to purge", days);
            lastPurgeCount.set(0);
            lastPurgeAt.set(Instant.now());
            return;
        }

        log.info("SnapshotRetentionJob: purging {} snapshots older than {} days (cutoff={})", eligible, days, cutoff);
        snapshotRepo.deleteByCreatedAtBefore(cutoff);
        lastPurgeCount.set(eligible);
        lastPurgeAt.set(Instant.now());
        log.info("SnapshotRetentionJob: purge complete — {} records deleted", eligible);
    }

    /**
     * Immediate (manual) purge, called from the management endpoint.
     * Does not acquire the ShedLock — admin-triggered, single-request.
     */
    public long purgeNow() {
        int days = readRetentionDays();
        if (days <= 0) return 0;
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        long count     = snapshotRepo.countByCreatedAtBefore(cutoff);
        if (count > 0) snapshotRepo.deleteByCreatedAtBefore(cutoff);
        lastPurgeCount.set(count);
        lastPurgeAt.set(Instant.now());
        log.info("SnapshotRetentionJob (manual): purged {} snapshots", count);
        return count;
    }

    public int readRetentionDays() {
        return settingRepo
                .findBySettingKeyAndEnvironmentAndServiceName(SETTING_KEY, Environment.PROD, "GLOBAL")
                .map(s -> {
                    try { return Integer.parseInt(s.getSettingValue()); }
                    catch (NumberFormatException e) { return DEFAULT_DAYS; }
                })
                .orElse(DEFAULT_DAYS);
    }
}
