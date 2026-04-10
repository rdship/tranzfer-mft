package com.filetransfer.storage.lifecycle;

import com.filetransfer.storage.entity.WriteIntent;
import com.filetransfer.storage.repository.WriteIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Write-Ahead Intent Log service. Provides crash recovery for file writes.
 * On startup and every 10 minutes, scans for orphaned IN_PROGRESS intents
 * older than 30 minutes and cleans up their temp files.
 */
@Service @RequiredArgsConstructor @Slf4j
public class WriteIntentService {

    private final WriteIntentRepository intentRepo;

    /** Create an intent before starting a write operation. */
    public WriteIntent create(String tempPath, long expectedSize, int stripeCount) {
        return intentRepo.save(WriteIntent.builder()
                .tempPath(tempPath)
                .expectedSizeBytes(expectedSize)
                .stripeCount(stripeCount)
                .build());
    }

    /** Mark intent as completed after successful write. */
    public void complete(WriteIntent intent, String destPath) {
        intent.setStatus("DONE");
        intent.setDestPath(destPath);
        intent.setCompletedAt(Instant.now());
        intentRepo.save(intent);
    }

    /** Mark intent as abandoned (e.g., dedup hit, intentional skip). */
    public void abandon(WriteIntent intent) {
        intent.setStatus("ABANDONED");
        intent.setCompletedAt(Instant.now());
        intentRepo.save(intent);
    }

    /** Run on startup to clean orphans from previous crashes. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("WAIL: Scanning for orphaned write intents from previous run...");
        cleanOrphanedWrites();
    }

    /** Periodic cleanup every 10 minutes. */
    @Scheduled(fixedDelay = 600_000)
    @SchedulerLock(name = "wail_cleanup", lockAtMostFor = "PT9M")
    public void scheduledCleanup() {
        cleanOrphanedWrites();
    }

    private void cleanOrphanedWrites() {
        var orphans = intentRepo.findByStatusAndCreatedAtBefore(
                "IN_PROGRESS", Instant.now().minus(30, ChronoUnit.MINUTES));

        int cleaned = 0;
        for (WriteIntent intent : orphans) {
            try {
                Path tempFile = Path.of(intent.getTempPath());
                if (Files.exists(tempFile)) {
                    long size = Files.size(tempFile);
                    Files.delete(tempFile);
                    log.warn("WAIL: Cleaned orphaned temp file {} ({} bytes, created {})",
                            tempFile, size, intent.getCreatedAt());
                }
                intent.setStatus("ABANDONED");
                intent.setCompletedAt(Instant.now());
                intentRepo.save(intent);
                cleaned++;
            } catch (Exception e) {
                log.error("WAIL: Failed to clean orphan {}: {}", intent.getTempPath(), e.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("WAIL: Cleaned {} orphaned write intents", cleaned);
        }
    }
}
