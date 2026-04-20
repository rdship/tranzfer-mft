-- =============================================================================
-- V97 — cluster_nodes (Sprint 0 of R134-external-dep-retirement)
--
-- Replaces RedisServiceRegistry. Each service pod heartbeats every 10s;
-- a reaper marks nodes DEAD when last_heartbeat > 30s ago. Admin UI +
-- Platform Sentinel read from this table for cluster state.
--
-- For real-time JOIN/LEAVE notifications, the reaper publishes to the
-- RabbitMQ `platform:cluster:events` fanout (which is retained for this
-- single purpose — see doc 02 for the rationale on keeping RabbitMQ for
-- high-throughput + real-time signals).
--
-- See docs/rd/2026-04-R134-external-dep-retirement/01-redis-retirement.md
-- "Hard consumer 3 — Service registry" for the full design.
-- =============================================================================

CREATE TABLE IF NOT EXISTS cluster_nodes (
    node_id          VARCHAR(128) NOT NULL PRIMARY KEY,
    service_type     VARCHAR(64)  NOT NULL,   -- "sftp-service", "onboarding-api", "https-service"
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    url              VARCHAR(512),             -- "http://sftp-service:8081"
    spiffe_id        VARCHAR(255),             -- "spiffe://filetransfer.io/sftp-service"
    last_heartbeat   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE | DRAINING | DEAD
        CONSTRAINT cluster_nodes_status_chk CHECK (status IN ('ACTIVE', 'DRAINING', 'DEAD')),
    metadata         JSONB
);

CREATE INDEX IF NOT EXISTS idx_cn_service_type    ON cluster_nodes (service_type, status);
CREATE INDEX IF NOT EXISTS idx_cn_last_heartbeat  ON cluster_nodes (last_heartbeat);

COMMENT ON TABLE cluster_nodes IS
    'Per-pod cluster membership + heartbeat state. Replaces Redis SCAN '
    'of platform:services:*. Heartbeat every 10s; reaper marks DEAD '
    'when last_heartbeat > 30s old.';
