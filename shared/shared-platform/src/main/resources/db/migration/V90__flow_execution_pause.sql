-- R106: admin-initiated pause/resume controls.
--
-- terminate() already sets a poll flag read by the engine between steps;
-- pause is the benign counterpart — agent exits cleanly with status=PAUSED
-- instead of CANCELLED, and an admin-provided resume point restarts from
-- currentStep without reset. pauseReason lets admins annotate why (SLA freeze,
-- upstream outage, manual review).

ALTER TABLE flow_executions
    ADD COLUMN IF NOT EXISTS pause_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS paused_by       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS paused_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS pause_reason    VARCHAR(500),
    ADD COLUMN IF NOT EXISTS resumed_by      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS resumed_at      TIMESTAMP WITH TIME ZONE;
