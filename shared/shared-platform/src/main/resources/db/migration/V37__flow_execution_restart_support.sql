-- Adds restart, terminate, and attempt-history support to flow_executions.
-- All new columns are nullable with defaults — fully backwards compatible.

ALTER TABLE flow_executions
    ADD COLUMN IF NOT EXISTS initial_storage_key   VARCHAR(64),
    ADD COLUMN IF NOT EXISTS attempt_number        INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS attempt_history       JSONB,
    ADD COLUMN IF NOT EXISTS restarted_by          VARCHAR(255),
    ADD COLUMN IF NOT EXISTS restarted_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS terminated_by         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS terminated_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS termination_requested BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS current_storage_key   VARCHAR(64);

-- CANCELLED is a new valid status value (stored as VARCHAR — no enum migration needed).

CREATE INDEX IF NOT EXISTS idx_fe_status_created
    ON flow_executions(status, started_at DESC);
