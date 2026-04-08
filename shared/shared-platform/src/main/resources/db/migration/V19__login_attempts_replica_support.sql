-- =============================================================================
-- V19: Login attempts tracking (DB-backed for multi-replica consistency)
-- =============================================================================

CREATE TABLE IF NOT EXISTS login_attempts (
    id              UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    failure_count   INT NOT NULL DEFAULT 0,
    locked_until    TIMESTAMP WITH TIME ZONE,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_login_attempts_locked ON login_attempts(locked_until)
    WHERE locked_until IS NOT NULL;
