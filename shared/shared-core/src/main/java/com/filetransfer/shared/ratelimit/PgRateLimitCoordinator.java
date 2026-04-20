package com.filetransfer.shared.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Cross-pod rate-limit counter backed by the {@code rate_limit_buckets}
 * Postgres table (see {@code V95__rate_limit_buckets.sql}). Replaces the
 * Redis {@code INCR}+{@code EXPIRE} pattern in
 * {@code shared-core/.../ratelimit/ApiRateLimitFilter} (flipped in Sprint 2
 * of the external-dep retirement plan).
 *
 * <p><b>Why Caffeine alone can't do this:</b> multi-pod gateway deployments
 * must share the counter so users can't bypass quotas by round-robining
 * across pods. Postgres with {@code ON CONFLICT DO UPDATE ... RETURNING}
 * is atomic and achieves ~10k req/s per PG core on warm cache — well
 * above realistic MFT API traffic.
 *
 * <p><b>Sprint 0 scope:</b> class exists, no caller has switched to it
 * yet. The Redis-backed {@code ApiRateLimitFilter} flips to this backend
 * in Sprint 2 once a feature flag has been validated in staging soak.
 * See {@code docs/rd/2026-04-R134-external-dep-retirement/01-redis-retirement.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgRateLimitCoordinator {

    private final JdbcTemplate jdbc;

    /**
     * Atomically add {@code delta} to the (bucketKey, windowStart) counter
     * and return the new count. One round-trip, one SQL statement.
     *
     * @param bucketKey   caller-chosen bucket ID (e.g. {@code "ip:203.0.113.5"},
     *                    {@code "user:sarah@acme.com"})
     * @param windowStart window begin instant; caller truncates to the
     *                    window size (e.g. minute-aligned for 1-min windows)
     * @param delta       usually 1 (per-request); can be > 1 for batch
     *                    accounting
     * @return the new request_count AFTER the increment
     */
    public long incrementAndGet(String bucketKey, Instant windowStart, int delta) {
        if (bucketKey == null || bucketKey.isBlank()) {
            throw new IllegalArgumentException("bucketKey is required");
        }
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        Long count = jdbc.queryForObject("""
            INSERT INTO rate_limit_buckets (bucket_key, window_start, request_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (bucket_key, window_start) DO UPDATE
              SET request_count = rate_limit_buckets.request_count + EXCLUDED.request_count,
                  updated_at    = now()
            RETURNING request_count
            """, Long.class, bucketKey, Timestamp.from(windowStart), delta);
        return count != null ? count : 0L;
    }

    /**
     * Add {@code deltaBytes} to the bytes-per-window counter for a bucket.
     * Used for bandwidth limits, not per-request counts. Same semantics
     * as {@link #incrementAndGet} but on the {@code bytes_count} column.
     */
    public long addBytesAndGet(String bucketKey, Instant windowStart, long deltaBytes) {
        Long bytes = jdbc.queryForObject("""
            INSERT INTO rate_limit_buckets (bucket_key, window_start, bytes_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (bucket_key, window_start) DO UPDATE
              SET bytes_count = rate_limit_buckets.bytes_count + EXCLUDED.bytes_count,
                  updated_at  = now()
            RETURNING bytes_count
            """, Long.class, bucketKey, Timestamp.from(windowStart), deltaBytes);
        return bytes != null ? bytes : 0L;
    }

    /**
     * Read the current count without incrementing. Useful for "how close
     * am I to the limit" dashboards.
     */
    public long currentCount(String bucketKey, Instant windowStart) {
        Long count = jdbc.queryForObject("""
            SELECT COALESCE(
                (SELECT request_count FROM rate_limit_buckets
                  WHERE bucket_key = ? AND window_start = ?),
                0)
            """, Long.class, bucketKey, Timestamp.from(windowStart));
        return count != null ? count : 0L;
    }

    /**
     * @Scheduled partition reaper — drops partitions older than the
     * configured retention. Runs hourly; wastes nothing since the partition
     * scan is O(1) in pg_class. Skipped when no partitions qualify.
     *
     * <p>Retention: 90 days of counter history is more than enough for
     * quota-violation forensics; older data lives nowhere useful.
     */
    @Scheduled(cron = "0 0 4 * * *")  // 04:00 daily, low-traffic window
    public void dropOldPartitions() {
        try {
            jdbc.query("""
                SELECT child.relname
                  FROM pg_inherits i
                  JOIN pg_class parent ON i.inhparent = parent.oid
                  JOIN pg_class child  ON i.inhrelid  = child.oid
                 WHERE parent.relname = 'rate_limit_buckets'
                   AND child.relname ~ '^rate_limit_buckets_\\d{6}$'
                   AND substring(child.relname FROM '\\d{6}$')::int <
                       to_char(now() - interval '90 days', 'YYYYMM')::int
                """, rs -> {
                    String part = rs.getString("relname");
                    jdbc.execute("DROP TABLE IF EXISTS " + part);
                    log.info("[RateLimit] Dropped old partition {}", part);
                });
        } catch (Exception e) {
            // Non-fatal — partitions just accumulate. Alert threshold in
            // the metrics dashboard.
            log.warn("[RateLimit] Partition reaper failed: {}", e.getMessage());
        }
    }

    /**
     * @Scheduled next-month partition creator — runs on the 25th so the
     * partition exists before the first request of the new month. Idempotent.
     */
    @Scheduled(cron = "0 0 3 25 * *")
    public void createNextMonthPartition() {
        try {
            jdbc.execute("""
                DO $$
                DECLARE
                    next_month timestamptz := date_trunc('month', now() + interval '1 month');
                    month_after timestamptz := date_trunc('month', now() + interval '2 months');
                    partition_name text := 'rate_limit_buckets_' || to_char(next_month, 'YYYYMM');
                BEGIN
                    EXECUTE format(
                        'CREATE TABLE IF NOT EXISTS %I PARTITION OF rate_limit_buckets FOR VALUES FROM (%L) TO (%L)',
                        partition_name, next_month, month_after
                    );
                    EXECUTE format(
                        'CREATE INDEX IF NOT EXISTS idx_%I_recent ON %I (window_start) WHERE window_start > (now() - INTERVAL ''2 hours'')',
                        partition_name, partition_name
                    );
                END $$;
                """);
            log.info("[RateLimit] Ensured next-month partition exists");
        } catch (Exception e) {
            log.error("[RateLimit] Next-month partition creator failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Convenience: truncate {@code now} to the window boundary. Most
     * callers want a 1-minute sliding window.
     */
    public static Instant windowStart(Duration windowSize) {
        long size = windowSize.toMillis();
        long now = Instant.now().toEpochMilli();
        return Instant.ofEpochMilli((now / size) * size);
    }
}
