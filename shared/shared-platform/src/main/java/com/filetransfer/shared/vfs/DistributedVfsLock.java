package com.filetransfer.shared.vfs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Distributed path-level lock for VFS write operations.
 *
 * <h3>Problem with pg_advisory_xact_lock</h3>
 * PostgreSQL advisory locks are session-scoped. Two pods can each acquire an
 * advisory lock on the same hash value because they hold separate DB connections.
 * With multiple storage-manager replicas, concurrent writes to the same VFS path
 * from different pods bypass each other's locks.
 *
 * <h3>This fix</h3>
 * Redis {@code SET NX EX} provides a cross-pod mutex. Only one pod can hold the
 * lock at a time. On lock acquisition failure, the caller must retry or fail fast.
 *
 * <h3>Fallback</h3>
 * When Redis is unavailable this component is not activated — the caller falls back
 * to {@code pg_advisory_xact_lock} which is correct for single-instance deployments.
 *
 * <p>Lock TTL: 30 s. VFS writes are expected to complete well within this window.
 * If a pod crashes mid-write, the lock expires automatically.
 */
@Slf4j
@Component
@ConditionalOnBean(RedisConnectionFactory.class)
public class DistributedVfsLock {

    private static final String LOCK_PREFIX  = "platform:vfs:lock:";
    private static final Duration LOCK_TTL   = Duration.ofSeconds(30);
    private static final int MAX_RETRIES     = 5;
    private static final long RETRY_DELAY_MS = 200;

    private final StringRedisTemplate redis;

    @Autowired
    public DistributedVfsLock(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Acquire the distributed lock for the given VFS path and account.
     * Blocks (with retries) up to ~1 second before throwing.
     *
     * @param accountId the account owning the path
     * @param path      the virtual path being written
     * @return a {@link LockHandle} that MUST be closed in a finally block
     * @throws IllegalStateException if the lock cannot be acquired after retries
     */
    public LockHandle acquire(UUID accountId, String path) {
        if (redis == null) return LockHandle.NOOP;

        long hash = accountId.getMostSignificantBits()
                ^ (accountId.getLeastSignificantBits() >>> 17)
                ^ ((long) path.hashCode() << 31);
        String key   = LOCK_PREFIX + Long.toUnsignedString(hash);
        String value = System.getenv().getOrDefault("HOSTNAME", "pod") + ":" + Thread.currentThread().getId();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, value, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                log.trace("[VfsLock] Acquired {} attempt={}", key, attempt + 1);
                return () -> releaseLock(key, value);
            }
            if (attempt < MAX_RETRIES - 1) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for VFS lock on: " + path);
                }
            }
        }

        log.warn("[VfsLock] Could not acquire distributed lock for path={} account={} after {} retries",
                path, accountId, MAX_RETRIES);
        throw new IllegalStateException(
                "Concurrent write contention on VFS path [" + path + "] — retry the operation");
    }

    private void releaseLock(String key, String expectedValue) {
        try {
            String current = redis.opsForValue().get(key);
            if (expectedValue.equals(current)) {
                redis.delete(key);
            }
            // If current != expectedValue: lock expired and was acquired by another pod — don't delete
        } catch (Exception e) {
            log.debug("[VfsLock] Release failed for {}: {}", key, e.getMessage());
        }
    }

    /** AutoCloseable handle returned by {@link #acquire}. */
    @FunctionalInterface
    public interface LockHandle extends AutoCloseable {
        @Override void close();

        /** No-op handle used when Redis is unavailable (single-instance mode). */
        LockHandle NOOP = () -> {};
    }
}
