package com.filetransfer.storage.coordination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * File-level distributed lock coordinator. Hosts the platform_locks table
 * and provides a transactional lease API. Callers include:
 * <ul>
 *   <li><b>Listeners</b> (sftp/ftp/ftp-web/https/as2) serializing VFS writes
 *       to the same virtual path across pods</li>
 *   <li><b>FlowProcessingEngine</b> claiming a flow-step materialization
 *       temp dir</li>
 *   <li><b>Tier transition jobs</b> (future) — locking a SHA-256 key during
 *       HOT→WARM move</li>
 * </ul>
 *
 * <p>External callers reach this via {@link StorageCoordinationController}
 * (HTTP). In-process callers (storage-manager's own upload handlers) call
 * this service directly.
 *
 * <p><b>Why this lives in storage-manager, not a generic coordination
 * service:</b> see
 * {@code docs/rd/2026-04-R134-external-dep-retirement/03-storage-manager-evolution.md}.
 * Short version — file-path locks are about file lifecycle, which is
 * storage-manager's existing authoritative concern; scattering them to a
 * new service would break functional cohesion.
 *
 * <p>Sprint 0 of the external-dep retirement plan: this class exists on
 * the classpath with its REST controller but NO caller switches over
 * until Sprint 5 (the VFS-lock callsites in listener services). The bean
 * is safe to ship as-is; the lease table is empty until we start using
 * it. Zero behaviour change on landing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageCoordinationService {

    private final JdbcTemplate jdbc;

    /**
     * Acquire or refresh a lock for the given key. If no lock exists,
     * inserts one. If an expired lock exists, replaces it. If a live
     * lock exists and {@code holderId} matches, extends the lease
     * (idempotent refresh). If a live lock exists held by a different
     * holder, returns false without modification.
     *
     * @param lockKey  caller-chosen lock namespace (e.g. {@code "vfs:write:<account_id>:<path>"})
     * @param holderId caller identity; typically {@code "<pod_id>:<thread_id>"}
     * @param ttl      lease duration; the reaper purges expired rows every 30s
     * @return true iff we now hold the lock
     */
    @Transactional
    public boolean tryAcquire(String lockKey, String holderId, Duration ttl) {
        if (lockKey == null || lockKey.isBlank() || holderId == null || holderId.isBlank()) {
            throw new IllegalArgumentException("lockKey and holderId are required");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (ttl.toMinutes() > 30) {
            // Sanity cap — 30 min is way longer than any realistic VFS write or
            // flow step. Catches caller bugs (ttl in seconds interpreted as ms).
            throw new IllegalArgumentException("ttl > 30 minutes not supported");
        }

        Instant now = Instant.now();
        Instant expires = now.plus(ttl);

        // Atomic upsert via ON CONFLICT. The WHERE filter on the UPDATE branch
        // means: we extend the lease ONLY if (a) the existing row is expired
        // or (b) we already hold it. If a different holder has a live lease,
        // neither clause matches and the UPDATE is a no-op — caller gets false.
        int affected = jdbc.update("""
            INSERT INTO platform_locks (lock_key, holder_id, acquired_at, expires_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (lock_key) DO UPDATE
              SET holder_id   = EXCLUDED.holder_id,
                  acquired_at = EXCLUDED.acquired_at,
                  expires_at  = EXCLUDED.expires_at
              WHERE platform_locks.expires_at <= EXCLUDED.acquired_at
                 OR platform_locks.holder_id   =  EXCLUDED.holder_id
            """,
            lockKey, holderId, Timestamp.from(now), Timestamp.from(expires));

        if (affected == 1) {
            log.debug("[StorageCoordination] acquired key={} holder={} ttl={}", lockKey, holderId, ttl);
            return true;
        }
        // affected=0 means either (a) ON CONFLICT fired but the WHERE filter
        // rejected the UPDATE (someone else holds it), or (b) an extremely
        // unusual race where someone else grabbed it between the INSERT and
        // the UPDATE. Either way — we don't hold it. Return false so caller
        // backs off / retries.
        return false;
    }

    /**
     * Extend an existing lease held by this caller. Use-case: long-running
     * uploads that exceed the initial TTL. Returns false if the caller
     * doesn't hold the lock (possibly reaper already purged it).
     */
    @Transactional
    public boolean extend(String lockKey, String holderId, Duration newTtl) {
        Instant expires = Instant.now().plus(newTtl);
        int affected = jdbc.update("""
            UPDATE platform_locks
               SET expires_at = ?
             WHERE lock_key  = ?
               AND holder_id = ?
               AND expires_at > now()
            """, Timestamp.from(expires), lockKey, holderId);
        if (affected == 0) {
            log.warn("[StorageCoordination] extend failed — key={} holder={} (not held or expired)",
                    lockKey, holderId);
            return false;
        }
        return true;
    }

    /**
     * Release a lock. Only the holder may release — prevents an orphaned
     * caller from accidentally dropping someone else's lease.
     */
    @Transactional
    public boolean release(String lockKey, String holderId) {
        int affected = jdbc.update(
            "DELETE FROM platform_locks WHERE lock_key = ? AND holder_id = ?",
            lockKey, holderId);
        if (affected == 1) {
            log.debug("[StorageCoordination] released key={} holder={}", lockKey, holderId);
            return true;
        }
        return false;
    }

    /**
     * @Scheduled reaper — purges leases whose expires_at is in the past.
     * Runs every 30s so at any moment the oldest stale lease is at most
     * 30s stale. Shedlock (applied at the bean level in
     * {@link com.filetransfer.storage.coordination.CoordinationSchedulerConfig})
     * ensures only one storage-manager replica runs the reaper.
     */
    public int reapExpiredLeases() {
        int purged = jdbc.update("DELETE FROM platform_locks WHERE expires_at < now()");
        if (purged > 0) {
            log.info("[StorageCoordination] Reaped {} expired lease(s)", purged);
        }
        return purged;
    }
}
