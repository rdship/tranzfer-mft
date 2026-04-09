-- Admin-scheduled retry: allows an operator to say "retry this failed execution at 02:00 AM".
-- The scheduler in onboarding-api polls every minute for due retries, clears the columns
-- atomically (UPDATE … WHERE scheduled_retry_at IS NOT NULL), then fires restartFromBeginning.
-- Double-trigger is prevented by the conditional UPDATE — only one instance wins the row.

ALTER TABLE flow_executions
    ADD COLUMN IF NOT EXISTS scheduled_retry_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_retry_by  VARCHAR(255);

-- Partial index for efficient polling (only rows that actually have a scheduled retry)
CREATE INDEX IF NOT EXISTS idx_fe_scheduled_retry
    ON flow_executions(scheduled_retry_at)
    WHERE scheduled_retry_at IS NOT NULL;
