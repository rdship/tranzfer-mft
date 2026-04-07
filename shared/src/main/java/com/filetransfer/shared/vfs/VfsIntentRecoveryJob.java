package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VfsIntent.IntentStatus;
import com.filetransfer.shared.repository.VfsIntentRepository;
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
 * Recovers stale PENDING VFS intents left by crashed pods.
 *
 * <p>Runs every 2 minutes. An intent is "stale" if it has been PENDING
 * for longer than 5 minutes — well beyond any normal transaction duration.
 *
 * <p>Recovery logic per operation type:
 * <ul>
 *   <li><b>WRITE</b>: If CAS has the file → replay DB entry (COMMITTED).
 *       If CAS doesn't have it → ABORTED (file was never stored).</li>
 *   <li><b>DELETE</b>: Always ABORTED (safe — data preserved).</li>
 *   <li><b>MOVE</b>: Inspect source/dest existence to determine outcome.</li>
 * </ul>
 *
 * <p>Uses PENDING → RECOVERING CAS transition to prevent double-recovery
 * when multiple pods run the recovery job concurrently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VfsIntentRecoveryJob {

    private final VfsIntentRepository intentRepository;
    private final VirtualFileSystem vfs;
    private final StorageServiceClient storageClient;

    @Scheduled(fixedDelay = 120_000)
    @SchedulerLock(name = "vfs-intent-recovery", lockAtLeastFor = "PT90S", lockAtMostFor = "PT4M")
    @Transactional
    public void recoverStaleIntents() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(5));
        List<VfsIntent> stale = intentRepository.findByStatusAndCreatedAtBefore(
                IntentStatus.PENDING, threshold);

        if (!stale.isEmpty()) {
            int recovered = 0;
            for (VfsIntent intent : stale) {
                try {
                    if (recoverIntent(intent)) recovered++;
                } catch (Exception e) {
                    log.error("Failed to recover intent {}: {}", intent.getId(), e.getMessage());
                }
            }
            log.info("VFS intent recovery: processed {} stale, recovered {}", stale.size(), recovered);
        }

        // Purge resolved intents older than 7 days (always runs)
        int purged = intentRepository.purgeResolved(Instant.now().minus(Duration.ofDays(7)));
        if (purged > 0) {
            log.info("VFS intent recovery: purged {} old resolved intents", purged);
        }
    }

    private boolean recoverIntent(VfsIntent intent) {
        // CAS transition: PENDING → RECOVERING (prevents double-recovery)
        int updated = intentRepository.resolve(intent.getId(),
                IntentStatus.PENDING, IntentStatus.RECOVERING);
        if (updated == 0) return false; // Another pod beat us

        return switch (intent.getOp()) {
            case WRITE -> recoverWrite(intent);
            case DELETE -> recoverDelete(intent);
            case MOVE -> recoverMove(intent);
        };
    }

    private boolean recoverWrite(VfsIntent intent) {
        boolean casHasFile = intent.getStorageKey() != null
                && storageClient.existsBySha256(intent.getStorageKey());
        boolean vfsHasEntry = vfs.exists(intent.getAccountId(), intent.getPath());

        if (casHasFile && !vfsHasEntry) {
            // CAS write succeeded, DB write didn't → replay
            try {
                vfs.writeFile(intent.getAccountId(), intent.getPath(), intent.getStorageKey(),
                        intent.getSizeBytes(), intent.getTrackId(), intent.getContentType());
                intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
                log.info("Recovered WRITE intent {} → COMMITTED (replayed DB entry for {})",
                        intent.getId(), intent.getPath());
                return true;
            } catch (Exception e) {
                intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
                log.warn("Recovered WRITE intent {} → ABORTED: {}", intent.getId(), e.getMessage());
                return false;
            }
        }

        if (casHasFile && vfsHasEntry) {
            // Both succeeded, intent just wasn't marked
            intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
            return true;
        }

        // CAS write never completed or INLINE (storageKey is null) — abort
        intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
        log.info("Recovered WRITE intent {} → ABORTED (CAS object not found for {})",
                intent.getId(), intent.getPath());
        return false;
    }

    private boolean recoverDelete(VfsIntent intent) {
        // Delete never completed → safe to abort (data preserved)
        intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
        log.info("Recovered DELETE intent {} → ABORTED (delete never completed for {})",
                intent.getId(), intent.getPath());
        return false;
    }

    private boolean recoverMove(VfsIntent intent) {
        boolean sourceExists = vfs.exists(intent.getAccountId(), intent.getPath());
        boolean destExists = intent.getDestPath() != null
                && vfs.exists(intent.getAccountId(), intent.getDestPath());

        if (sourceExists && !destExists) {
            // Move never happened → abort
            intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
            log.info("Recovered MOVE intent {} → ABORTED ({} still at source)",
                    intent.getId(), intent.getPath());
        } else if (!sourceExists && destExists) {
            // Move completed, intent not marked → commit
            intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
            log.info("Recovered MOVE intent {} → COMMITTED ({} → {})",
                    intent.getId(), intent.getPath(), intent.getDestPath());
            return true;
        } else {
            // Ambiguous — abort (safe default)
            intentRepository.resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
            log.warn("Recovered MOVE intent {} → ABORTED (ambiguous state for {})",
                    intent.getId(), intent.getPath());
        }
        return false;
    }
}
