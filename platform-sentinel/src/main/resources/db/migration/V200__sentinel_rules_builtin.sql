-- =============================================================================
-- V38 — Sentinel rules: builtin flag + analyzer+enabled composite index
-- =============================================================================

-- Mark built-in (seeded) rules so they cannot be accidentally deleted
ALTER TABLE sentinel_rules ADD COLUMN IF NOT EXISTS builtin BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill existing seeded rules
UPDATE sentinel_rules SET builtin = TRUE WHERE name IN (
    'login_failure_spike', 'account_lockout', 'config_change_burst', 'failed_transfer_spike',
    'integrity_mismatch', 'quarantine_surge', 'dlq_growth', 'screening_hit',
    'latency_degradation', 'error_rate_spike', 'throughput_drop', 'service_unhealthy',
    'disk_usage_high', 'connection_saturation'
);

-- Composite index used by analyzers on every 5-min cycle
CREATE INDEX IF NOT EXISTS idx_sr_analyzer_enabled ON sentinel_rules(analyzer, enabled);
