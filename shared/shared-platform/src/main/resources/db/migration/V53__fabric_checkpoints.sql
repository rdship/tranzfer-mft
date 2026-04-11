-- V53: Dynamic Flow Fabric — checkpoint and instance registry tables
-- Phase 1 of the Flow Fabric rollout. Adds per-step checkpointing for
-- observability and crash recovery, plus a pod/instance registry for
-- multi-instance coordination.

-- =========================================================================
-- fabric_checkpoints: one row per step execution across the platform
-- Queried for "where is file X right now?" and crash recovery
-- =========================================================================
CREATE TABLE IF NOT EXISTS fabric_checkpoints (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id               VARCHAR(32) NOT NULL,
    step_index             INT NOT NULL,
    step_type              VARCHAR(64) NOT NULL,

    status                 VARCHAR(24) NOT NULL,   -- PENDING, IN_PROGRESS, COMPLETED, FAILED, ABANDONED
    input_storage_key      CHAR(64),               -- SHA-256 at step start
    output_storage_key     CHAR(64),               -- SHA-256 at step end
    input_size_bytes       BIGINT,
    output_size_bytes      BIGINT,

    processing_instance    VARCHAR(128),           -- e.g., "onboarding-api-pod-7"
    claimed_at             TIMESTAMPTZ,
    lease_expires_at       TIMESTAMPTZ,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    duration_ms            BIGINT,

    attempt_number         INT DEFAULT 1,
    error_category         VARCHAR(32),            -- AUTH, NETWORK, KEY_EXPIRED, FORMAT, UNKNOWN
    error_message          TEXT,

    fabric_offset          BIGINT,                 -- Kafka/Redpanda offset
    fabric_partition       INT,
    metadata               JSONB,                  -- step-specific data

    created_at             TIMESTAMPTZ DEFAULT NOW()
);

-- Primary lookup: full timeline for a trackId
CREATE INDEX IF NOT EXISTS idx_fabric_ckpt_track
    ON fabric_checkpoints (track_id, step_index);

-- Stuck work detection (lease reaper)
CREATE INDEX IF NOT EXISTS idx_fabric_ckpt_stuck
    ON fabric_checkpoints (status, lease_expires_at)
    WHERE status = 'IN_PROGRESS';

-- Instance dashboard: what is each pod doing?
CREATE INDEX IF NOT EXISTS idx_fabric_ckpt_instance
    ON fabric_checkpoints (processing_instance, status);

-- Recent activity queries
CREATE INDEX IF NOT EXISTS idx_fabric_ckpt_created
    ON fabric_checkpoints (created_at DESC);

-- =========================================================================
-- fabric_instances: heartbeat-based registry of running pods
-- =========================================================================
CREATE TABLE IF NOT EXISTS fabric_instances (
    instance_id            VARCHAR(128) PRIMARY KEY,
    service_name           VARCHAR(64) NOT NULL,
    host                   VARCHAR(200),
    started_at             TIMESTAMPTZ NOT NULL,
    last_heartbeat         TIMESTAMPTZ NOT NULL,
    status                 VARCHAR(16) NOT NULL,   -- HEALTHY, DEGRADED, DRAINING
    consumed_topics        TEXT,                   -- JSON array: ["flow.intake","flow.pipeline"]
    current_partitions     TEXT,                   -- JSON array: [0,3,6]
    in_flight_count        INT DEFAULT 0,
    metadata               JSONB
);

CREATE INDEX IF NOT EXISTS idx_fabric_inst_heartbeat
    ON fabric_instances (last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_fabric_inst_service
    ON fabric_instances (service_name, status);
