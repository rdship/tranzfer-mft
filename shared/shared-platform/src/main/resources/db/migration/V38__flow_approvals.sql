-- Flow Approval Gates: admin sign-off required before a flow continues past an APPROVE step.
-- An APPROVE step pauses execution (status=PAUSED) and creates one row here.
-- Admin approves → flow resumes from the next step. Admin rejects → flow is cancelled.

CREATE TABLE IF NOT EXISTS flow_approvals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id        UUID    NOT NULL,
    track_id            VARCHAR(12) NOT NULL,
    flow_name           VARCHAR(255),
    original_filename   VARCHAR(512),
    step_index          INTEGER NOT NULL,

    -- CAS keys captured at pause time (VIRTUAL-mode only; null for PHYSICAL-mode flows)
    paused_storage_key  VARCHAR(64),
    paused_virtual_path VARCHAR(1024),
    paused_size_bytes   BIGINT,

    -- Informational: usernames/emails shown in the UI as "who should review this"
    required_approvers  TEXT,

    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMPTZ,
    reviewed_by         VARCHAR(255),
    review_note         TEXT,

    CONSTRAINT uk_fa_track_step UNIQUE (track_id, step_index)
);

CREATE INDEX IF NOT EXISTS idx_fa_status     ON flow_approvals(status);
CREATE INDEX IF NOT EXISTS idx_fa_track_id   ON flow_approvals(track_id);
CREATE INDEX IF NOT EXISTS idx_fa_requested  ON flow_approvals(requested_at);
