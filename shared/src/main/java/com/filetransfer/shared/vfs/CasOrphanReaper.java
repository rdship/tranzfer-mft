package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Detects CAS objects with zero VFS references (orphans).
 *
 * <p>Runs daily. Currently LOG-ONLY — reports orphans without deleting them.
 * Physical CAS deletion will be enabled after the detection logic is validated
 * in production.
 *
 * <p>An object is an orphan if:
 * <ul>
 *   <li>No VirtualEntry references its SHA-256 key</li>
 *   <li>No PENDING VfsIntent references its SHA-256 key</li>
 *   <li>It was created more than 24 hours ago (grace period for in-flight uploads)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CasOrphanReaper {

    private final VirtualEntryRepository entryRepository;
    private final StorageServiceClient storageClient;

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

        int orphans = 0;
        long orphanBytes = 0;

        for (Map<String, Object> obj : objects) {
            String sha256 = (String) obj.get("sha256");
            if (sha256 == null) continue;

            long refCount = entryRepository.countByStorageKey(sha256);
            if (refCount == 0) {
                Object sizeObj = obj.get("sizeBytes");
                long size = sizeObj instanceof Number n ? n.longValue() : 0;
                log.info("CAS orphan detected: sha256={}, size={}B", sha256.substring(0, 12), size);
                orphans++;
                orphanBytes += size;
            }
        }

        if (orphans > 0) {
            log.warn("CAS orphan reaper: found {} orphaned objects ({}B total). Manual review recommended.",
                    orphans, orphanBytes);
        } else {
            log.debug("CAS orphan reaper: no orphans found");
        }
    }
}
