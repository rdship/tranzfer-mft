-- =============================================================================
-- V96 — platform_locks (Sprint 0 of R134-external-dep-retirement)
--
-- Replaces Redis SET NX EX for cross-pod distributed locks. Hosted by
-- storage-manager (logically — the StorageCoordinationService @Component).
-- Access is via POST /api/v1/coordination/locks/{key}/acquire to keep
-- callers on one side of a clean HTTP boundary (auth + rate limit +
-- observability) rather than direct DB access.
--
-- Primary use case: VFS path-level serialization for file uploads.
--   lock_key = "vfs:write:<account_id>:<normalized_path>"
--
-- Secondary use cases (future):
--   lock_key = "flow:step:<flow_execution_id>:<step_index>"  — step lease
--   lock_key = "tier:move:<sha256>"                          — tier transition
--
-- See docs/rd/2026-04-R134-external-dep-retirement/03-storage-manager-evolution.md
-- for the API design and the why-not-advisory-locks discussion.
-- =============================================================================

CREATE TABLE IF NOT EXISTS platform_locks (
    lock_key       VARCHAR(512) NOT NULL PRIMARY KEY,
    holder_id      VARCHAR(128) NOT NULL,
    acquired_at    TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    metadata       JSONB
);

CREATE INDEX IF NOT EXISTS idx_pl_expires_at ON platform_locks (expires_at);

-- Optional: index on holder_id for "show all locks I'm holding" admin queries.
CREATE INDEX IF NOT EXISTS idx_pl_holder_id ON platform_locks (holder_id);

COMMENT ON TABLE platform_locks IS
    'Cross-pod distributed lock leases. Owned by StorageCoordinationService '
    'in storage-manager. Reaper @Scheduled purges expires_at < now() every 30s. '
    'See docs/rd/2026-04-R134-external-dep-retirement/03-storage-manager-evolution.md';
