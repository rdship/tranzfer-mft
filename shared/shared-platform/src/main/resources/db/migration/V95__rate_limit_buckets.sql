-- =============================================================================
-- V95 — rate_limit_buckets (Sprint 0 of R134-external-dep-retirement)
--
-- Replaces Redis INCR+EXPIRE for the sliding-window rate limiter.
-- PK = (bucket_key, window_start) so the same user / IP / client bucket
-- gets one row per window (1-min sliding, 1-hour window buckets,
-- whatever the filter configures).
--
-- Partitioning: RANGE on window_start. Monthly partitions; a @Scheduled
-- task in shared-platform drops partitions > 90 days old and creates
-- next-month's partition on the 25th. Keeps the primary index small and
-- index scans bounded regardless of total traffic.
--
-- Access pattern:
--   INSERT ... ON CONFLICT (bucket_key, window_start) DO UPDATE
--     SET request_count = rate_limit_buckets.request_count + EXCLUDED.request_count,
--         updated_at    = now()
--     RETURNING request_count
--
-- At application load this is one statement, one round-trip. Benchmark
-- target is 10k req/s per gateway pod; realistic MFT load is <50 req/s.
--
-- This table is NEW — no reads/writes occur until Sprint 2 flips the
-- `platform.ratelimit.backend=pg` flag. Nothing regresses from this
-- migration landing.
-- =============================================================================

CREATE TABLE IF NOT EXISTS rate_limit_buckets (
    bucket_key       VARCHAR(255) NOT NULL,
    window_start     TIMESTAMPTZ  NOT NULL,
    request_count    INTEGER      NOT NULL DEFAULT 0,
    bytes_count      BIGINT       NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_key, window_start)
) PARTITION BY RANGE (window_start);

-- Seed the current month + next month so the first insert after migration
-- doesn't race the partition creator.
DO $$
DECLARE
    this_month_start timestamptz := date_trunc('month', now());
    next_month_start timestamptz := date_trunc('month', now() + interval '1 month');
    month_after_that timestamptz := date_trunc('month', now() + interval '2 months');
    this_partition   text := 'rate_limit_buckets_' || to_char(this_month_start, 'YYYYMM');
    next_partition   text := 'rate_limit_buckets_' || to_char(next_month_start, 'YYYYMM');
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF rate_limit_buckets FOR VALUES FROM (%L) TO (%L)',
        this_partition, this_month_start, next_month_start
    );
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF rate_limit_buckets FOR VALUES FROM (%L) TO (%L)',
        next_partition, next_month_start, month_after_that
    );
END $$;

-- R134E (fix for R134A-B-runtime-verification.md V95 block) — plain index on
-- window_start per partition, NO partial predicate.
--
-- The original V95 used `WHERE window_start > (now() - INTERVAL '2 hours')`
-- to restrict the index to recent rows (the hot-path query range). PostgreSQL
-- rejected that predicate:
--
--   ERROR:  functions in index predicate must be marked IMMUTABLE
--
-- `now()` is STABLE (wall-clock-dependent), which would let the index's
-- visible-row set drift as time advances — an impossible invariant to keep
-- correct on planner cache reuse. PG refuses at DDL time, failing the whole
-- migration and (via Flyway's strict ordering) blocking V96–V99 and every
-- dependent Sprint 1–5 feature at boot.
--
-- Fix: plain btree index on window_start. The query planner uses it for
-- range scans just fine; the "last 2 hours" partial was only an optimisation
-- and monthly partitioning already bounds each partition's row set. Dead
-- rows in older partitions are pruned by the @Scheduled reaper anyway.
DO $$
DECLARE
    this_month_start timestamptz := date_trunc('month', now());
    next_month_start timestamptz := date_trunc('month', now() + interval '1 month');
    this_partition   text := 'rate_limit_buckets_' || to_char(this_month_start, 'YYYYMM');
    next_partition   text := 'rate_limit_buckets_' || to_char(next_month_start, 'YYYYMM');
BEGIN
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON %I (window_start)',
        'idx_' || this_partition || '_ws', this_partition
    );
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS %I ON %I (window_start)',
        'idx_' || next_partition || '_ws', next_partition
    );
END $$;

COMMENT ON TABLE rate_limit_buckets IS
    'Sliding-window rate limit counters. Replaces Redis INCR+EXPIRE. '
    'PartyBy: window_start. Reaper drops partitions > 90 days. '
    'See docs/rd/2026-04-R134-external-dep-retirement/01-redis-retirement.md';
