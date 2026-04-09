-- Flow step snapshots: before/after CAS key per pipeline step
-- Populated asynchronously (fire-and-forget) — no impact on transfer latency.
-- Content is never copied here; only SHA-256 keys. Files served on-demand from storage-manager.
CREATE TABLE IF NOT EXISTS flow_step_snapshots (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id            VARCHAR(12) NOT NULL,
    flow_execution_id   UUID,
    step_index          INTEGER     NOT NULL,
    step_type           VARCHAR(50) NOT NULL,
    step_status         VARCHAR(30),             -- OK | OK_AFTER_RETRY_N | FAILED
    input_storage_key   VARCHAR(64),             -- SHA-256 before step
    output_storage_key  VARCHAR(64),             -- SHA-256 after step; NULL on failure
    input_virtual_path  VARCHAR(1024),
    output_virtual_path VARCHAR(1024),
    input_size_bytes    BIGINT,
    output_size_bytes   BIGINT,
    duration_ms         BIGINT,
    error_message       VARCHAR(1000),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_fss_track_step UNIQUE (track_id, step_index)
);

CREATE INDEX IF NOT EXISTS idx_fss_track_id ON flow_step_snapshots(track_id);
CREATE INDEX IF NOT EXISTS idx_fss_exec_id  ON flow_step_snapshots(flow_execution_id);
