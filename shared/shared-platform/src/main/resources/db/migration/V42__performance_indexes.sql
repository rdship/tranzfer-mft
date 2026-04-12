-- ──────────────────────────────────────────────────────────────────────────────
-- V42: Performance indexes for high-frequency query paths
--
-- R28 fix (BUG-A): removed CREATE INDEX CONCURRENTLY.
--
-- CONCURRENTLY was causing a deterministic self-deadlock inside Flyway's
-- own Hikari connection pool on every cold boot:
--   Connection A: holds Flyway advisory lock + open transaction (idle)
--   Connection B: runs CIC, waits for ALL transactions to finish
--   Connection A IS one of those transactions → deadlock.
-- This killed 22 services within 10 seconds of the first failure.
--
-- Plain CREATE INDEX is safe here because V42 runs on:
--   (a) fresh databases with zero rows, or
--   (b) upgrade databases where these tables are small (<1M rows).
-- For very large production upgrades with live traffic, a DBA can run
-- the CONCURRENTLY variants manually outside Flyway.
--
-- The old `flyway:executeInTransaction:false` directive is also removed —
-- plain CREATE INDEX runs fine inside a normal Flyway transaction.
-- ──────────────────────────────────────────────────────────────────────────────

-- 1. Live-stats GROUP BY (4 statuses checked every 5–10 s by the Dashboard)
CREATE INDEX IF NOT EXISTS idx_fe_status_live
    ON flow_executions(status)
    WHERE status IN ('PROCESSING', 'PENDING', 'PAUSED', 'FAILED');

-- 2. Scheduled-retry polling covering index
CREATE INDEX IF NOT EXISTS idx_fe_scheduled_retry_cover
    ON flow_executions(scheduled_retry_at, status, track_id)
    WHERE scheduled_retry_at IS NOT NULL;

-- 3. FlowStepSnapshot retention purge & latency aggregation
CREATE INDEX IF NOT EXISTS idx_fss_created_at
    ON flow_step_snapshots(created_at);

-- 4. FlowStepSnapshot latency heatmap
CREATE INDEX IF NOT EXISTS idx_fss_step_type
    ON flow_step_snapshots(step_type);

-- 5. Partner webhooks: active lookup
CREATE INDEX IF NOT EXISTS idx_pw_active
    ON partner_webhooks(active)
    WHERE active = true;

-- 6. Audit log range scans by trackId
CREATE INDEX IF NOT EXISTS idx_audit_track_id_ts
    ON audit_logs(track_id, timestamp DESC)
    WHERE track_id IS NOT NULL;

-- 7. Flow executions by status + date range
CREATE INDEX IF NOT EXISTS idx_fe_status_started
    ON flow_executions(status, started_at DESC);

-- 8. Flow executions by track_id (transfer journey, execution detail)
CREATE INDEX IF NOT EXISTS idx_fe_track_id
    ON flow_executions(track_id);
