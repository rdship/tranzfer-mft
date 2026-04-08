package com.filetransfer.shared.vfs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archives old resolved VFS intents from the cold partition to the archive table.
 *
 * <p>Runs daily at 03:00 UTC. Moves COMMITTED/ABORTED intents older than 30 days
 * from {@code vfs_intents_resolved} (the cold partition of {@code vfs_intents})
 * into {@code vfs_intents_archive}, then deletes them from the live table.
 *
 * <p>This keeps the cold partition small and query-friendly for recent audit lookups,
 * while preserving historical data in the archive table for compliance.
 *
 * <p>Uses batch processing (10,000 rows per iteration) to avoid long-running
 * transactions and excessive WAL generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.datasource.url")
public class VfsIntentArchiveJob {

    private static final int BATCH_SIZE = 10_000;
    private static final int ARCHIVE_DAYS = 30;

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "vfs-intent-archive", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    public void archiveOldResolvedIntents() {
        int totalArchived = 0;
        int batchCount;

        do {
            batchCount = archiveBatch();
            totalArchived += batchCount;
        } while (batchCount == BATCH_SIZE);

        if (totalArchived > 0) {
            log.info("VFS intent archive: moved {} resolved intents (older than {} days) to archive",
                    totalArchived, ARCHIVE_DAYS);
        }

        // Purge archive entries older than 1 year (retention policy)
        int purged = jdbc.update(
                "DELETE FROM vfs_intents_archive WHERE archived_at < now() - INTERVAL '365 days'");
        if (purged > 0) {
            log.info("VFS intent archive: purged {} entries older than 1 year from archive", purged);
        }
    }

    /**
     * Archives one batch using a CTE: DELETE from the live resolved partition
     * and INSERT into the archive table in a single atomic statement.
     *
     * <p>The DELETE targets only the {@code vfs_intents_resolved} partition via
     * the status filter, so the hot partition is never touched.
     */
    @Transactional
    protected int archiveBatch() {
        return jdbc.update("""
                WITH archived AS (
                    DELETE FROM vfs_intents
                    WHERE ctid IN (
                        SELECT ctid FROM vfs_intents
                        WHERE status IN ('COMMITTED', 'ABORTED')
                          AND resolved_at < now() - INTERVAL '%d days'
                        LIMIT %d
                    )
                    RETURNING id, account_id, op, path, dest_path, storage_key, track_id,
                              size_bytes, content_type, status, pod_id, created_at, resolved_at
                )
                INSERT INTO vfs_intents_archive
                    (id, account_id, op, path, dest_path, storage_key, track_id,
                     size_bytes, content_type, status, pod_id, created_at, resolved_at)
                SELECT id, account_id, op, path, dest_path, storage_key, track_id,
                       size_bytes, content_type, status, pod_id, created_at, resolved_at
                FROM archived
                """.formatted(ARCHIVE_DAYS, BATCH_SIZE));
    }
}
