-- flyway:executeInTransaction:false
-- ──────────────────────────────────────────────────────────────────────────────
-- V42: Performance indexes for high-frequency query paths
--
-- All indexes use CREATE INDEX CONCURRENTLY to avoid table locks in production.
-- CONCURRENTLY cannot run inside a Flyway transaction, so we opt out via the
-- directive above (Flyway 9.5+). Without it, every fresh boot hits a statement
-- timeout and onboarding-api / config-service crash on startup.
-- Run individually if you need to monitor progress.
-- ──────────────────────────────────────────────────────────────────────────────

-- 1. Live-stats GROUP BY (4 statuses checked every 5–10 s by the Dashboard)
--    Partial index — only covers the rows the query touches (skips COMPLETED/CANCELLED).
--    Before: seq scan or full idx_flow_exec_status scan → after: tiny partial index scan.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fe_status_live
    ON flow_executions(status)
    WHERE status IN ('PROCESSING', 'PENDING', 'PAUSED', 'FAILED');

-- 2. Scheduled-retry polling (already has a partial index on scheduled_retry_at, keep it)
--    Add covering index so the query can be answered without a heap fetch.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fe_scheduled_retry_cover
    ON flow_executions(scheduled_retry_at, status, track_id)
    WHERE scheduled_retry_at IS NOT NULL;

-- 3. FlowStepSnapshot retention purge & latency aggregation
--    countByCreatedAtBefore + deleteByCreatedAtBefore + heatmap GROUP BY both hit this.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fss_created_at
    ON flow_step_snapshots(created_at);

-- 4. FlowStepSnapshot latency heatmap: GROUP BY step_type + hour requires step_type index.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fss_step_type
    ON flow_step_snapshots(step_type);

-- 5. Partner webhooks: active lookup (called per flow completion)
--    Partial index covers only active rows, already small after filtering.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pw_active
    ON partner_webhooks(active)
    WHERE active = true;

-- 6. Audit log range scans by trackId (Journey page loads, evidence report)
--    Already indexed on idx_audit_track_id but no covering columns.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_track_id_ts
    ON audit_logs(track_id, timestamp DESC)
    WHERE track_id IS NOT NULL;

-- 7. Flow executions looked up by status + date range (Observatory domain groups, recovery job)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fe_status_started
    ON flow_executions(status, started_at DESC);
