package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.repository.VfsChunkRepository;
import com.filetransfer.shared.repository.VfsIntentRepository;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Detects and optionally garbage-collects CAS objects with zero VFS references (orphans).
 *
 * <p>Runs daily. Behaviour depends on the {@code vfs.cas-gc-enabled} flag:
 * <ul>
 *   <li>{@code false} (default) — <b>dry-run</b>: reports orphans without deleting them</li>
 *   <li>{@code true} — <b>live GC</b>: soft-deletes orphaned CAS objects via Storage Manager</li>
 * </ul>
 *
 * <p>An object is considered an orphan only when <b>all</b> of the following are true:
 * <ul>
 *   <li>No VirtualEntry references its SHA-256 key</li>
 *   <li>No VfsChunk references its SHA-256 key</li>
 *   <li>No PENDING VfsIntent references its SHA-256 key</li>
 *   <li>It was created more than {@code vfs.cas-gc-grace-hours} ago (default 24h)
 *       to avoid races with in-flight writes</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.datasource.url")
public class CasOrphanReaper {

    private final VirtualEntryRepository entryRepository;
    private final VfsChunkRepository chunkRepository;
    private final VfsIntentRepository intentRepository;
    private final StorageServiceClient storageClient;

    /** When true, orphaned CAS objects are actually deleted. Default: false (dry-run). */
    @Value("${vfs.cas-gc-enabled:false}")
    private boolean gcEnabled;

    /** Minimum age (hours) before an unreferenced object is eligible for GC. */
    @Value("${vfs.cas-gc-grace-hours:24}")
    private int graceHours;

    @Scheduled(fixedDelay = 86_400_000) // every 24 hours
    @SchedulerLock(name = "vfs-cas-orphan-reaper", lockAtLeastFor = "PT23H", lockAtMostFor = "PT24H")
    public void reapOrphans() {
        List<Map<String, Object>> objects;
        try {
            objects = storageClient.listObjects(null, null);
        } catch (Exception e) {
            log.warn("CAS orphan reaper: failed to list storage objects: {}", e.getMessage());
            return;
        }

        Instant graceCutoff = Instant.now().minus(graceHours, ChronoUnit.HOURS);

        int scanned = 0;
        int orphansDetected = 0;
        long orphanBytes = 0;
        int deleted = 0;
        long deletedBytes = 0;
        int skippedGrace = 0;
        int skippedPendingIntent = 0;
        int deleteErrors = 0;

        for (Map<String, Object> obj : objects) {
            String sha256 = (String) obj.get("sha256");
            if (sha256 == null) continue;
            scanned++;

            long entryRefs = entryRepository.countByStorageKey(sha256);
            long chunkRefs = chunkRepository.countByStorageKey(sha256);
            if (entryRefs > 0 || chunkRefs > 0) continue;

            // Unreferenced — check guards before considering it an orphan

            // Guard 1: PENDING intents referencing this key (in-flight write)
            long pendingIntents = intentRepository.countByStatusAndStorageKey(
                    VfsIntent.IntentStatus.PENDING, sha256);
            if (pendingIntents > 0) {
                skippedPendingIntent++;
                log.debug("CAS GC: skipping sha256={} — {} PENDING intent(s)",
                        sha256.substring(0, 12), pendingIntents);
                continue;
            }

            // Guard 2: grace period — only GC objects older than the cutoff
            Object createdAtObj = obj.get("createdAt");
            Instant createdAt = parseInstant(createdAtObj);
            if (createdAt != null && createdAt.isAfter(graceCutoff)) {
                skippedGrace++;
                log.debug("CAS GC: skipping sha256={} — within {}-hour grace period",
                        sha256.substring(0, 12), graceHours);
                continue;
            }

            // This is a confirmed orphan
            Object sizeObj = obj.get("sizeBytes");
            long size = sizeObj instanceof Number n ? n.longValue() : 0;
            orphansDetected++;
            orphanBytes += size;

            if (gcEnabled) {
                try {
                    storageClient.deleteBySha256(sha256);
                    deleted++;
                    deletedBytes += size;
                    log.info("CAS GC: deleted orphan sha256={}, size={}B", sha256.substring(0, 12), size);
                } catch (Exception e) {
                    deleteErrors++;
                    log.warn("CAS GC: failed to delete sha256={}: {}", sha256.substring(0, 12), e.getMessage());
                }
            } else {
                log.info("CAS GC [DRY-RUN]: orphan sha256={}, size={}B (would delete)",
                        sha256.substring(0, 12), size);
            }
        }

        // Summary log
        if (orphansDetected > 0 || skippedGrace > 0 || skippedPendingIntent > 0) {
            log.warn("CAS orphan reaper: scanned={}, orphans={} ({}B), " +
                            "deleted={} ({}B), errors={}, skippedGrace={}, skippedPendingIntent={}, mode={}",
                    scanned, orphansDetected, orphanBytes,
                    deleted, deletedBytes, deleteErrors,
                    skippedGrace, skippedPendingIntent,
                    gcEnabled ? "LIVE" : "DRY-RUN");
        } else {
            log.debug("CAS orphan reaper: scanned {} objects, no orphans found", scanned);
        }
    }

    /** Parse an Instant from various possible representations in the storage object map. */
    private static Instant parseInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof String s) {
            try { return Instant.parse(s); } catch (Exception ignored) { }
        }
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null; // unknown format — treat as old enough to GC
    }
}
