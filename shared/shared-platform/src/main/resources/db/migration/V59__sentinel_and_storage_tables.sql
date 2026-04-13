-- =============================================================================
-- V59 — Sentinel tables + Storage write_intents
--
-- These tables were previously in service-specific migrations (V54) but
-- db-migrate runs only shared migrations. Services have Flyway disabled
-- (-Dspring.flyway.enabled=false), so these must live in the shared path.
-- =============================================================================

-- ── Platform Sentinel ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sentinel_findings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analyzer VARCHAR(50) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,
    evidence TEXT,
    affected_service VARCHAR(50),
    affected_account VARCHAR(100),
    track_id VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    github_issue_url VARCHAR(500),
    correlation_group_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS sentinel_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analyzer VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT true,
    builtin BOOLEAN NOT NULL DEFAULT false,
    severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    threshold_value DOUBLE PRECISION,
    window_minutes INTEGER DEFAULT 60,
    cooldown_minutes INTEGER DEFAULT 30,
    config TEXT,
    last_triggered TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sentinel_health_scores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    overall_score INTEGER NOT NULL,
    infrastructure_score INTEGER NOT NULL,
    data_score INTEGER NOT NULL,
    security_score INTEGER NOT NULL,
    details TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sentinel_correlation_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500),
    root_cause VARCHAR(500),
    finding_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sf_status ON sentinel_findings(status);
CREATE INDEX IF NOT EXISTS idx_sf_severity ON sentinel_findings(severity);
CREATE INDEX IF NOT EXISTS idx_sf_created ON sentinel_findings(created_at);
CREATE INDEX IF NOT EXISTS idx_sf_analyzer ON sentinel_findings(analyzer);
CREATE INDEX IF NOT EXISTS idx_shs_recorded ON sentinel_health_scores(recorded_at);

-- ── Storage Manager — Write Intents ─────────────────────────────────────────

CREATE TABLE IF NOT EXISTS write_intents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    temp_path VARCHAR(1024) NOT NULL,
    dest_path VARCHAR(1024),
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    stripe_count INTEGER DEFAULT 0,
    expected_size_bytes BIGINT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wi_status ON write_intents(status);

-- Add moving tier columns to storage_objects (IF NOT EXISTS for idempotency)
ALTER TABLE storage_objects ADD COLUMN IF NOT EXISTS moving_to VARCHAR(10);
ALTER TABLE storage_objects ADD COLUMN IF NOT EXISTS move_started_at TIMESTAMPTZ;
