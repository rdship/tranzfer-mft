-- =============================================================================
-- V97 — platform_pod_heartbeat (Sprint 0 of R134-external-dep-retirement)
--
-- R134E rename (from `cluster_nodes`) — the original V97 collided with the
-- pre-existing `cluster_nodes` table owned by the ClusterNode entity (multi-
-- tenant customer-cluster management). `CREATE TABLE IF NOT EXISTS` then
-- silently skipped and the follow-up indexes failed because the columns
-- they reference (service_type, status) don't exist on the pre-existing
-- table. Tester caught it in R134A-B-runtime-verification.md §V97.
--
-- This rename is safe because no reader has queried the pod-heartbeat table
-- yet (R134y's ClusterController reader + the R134C observability log both
-- go through this same migration).
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

CREATE TABLE IF NOT EXISTS platform_pod_heartbeat (
    node_id          VARCHAR(128) NOT NULL PRIMARY KEY,
    service_type     VARCHAR(64)  NOT NULL,   -- "sftp-service", "onboarding-api", "https-service"
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    url              VARCHAR(512),             -- "http://sftp-service:8081"
    spiffe_id        VARCHAR(255),             -- "spiffe://filetransfer.io/sftp-service"
    last_heartbeat   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE | DRAINING | DEAD
        CONSTRAINT platform_pod_heartbeat_status_chk CHECK (status IN ('ACTIVE', 'DRAINING', 'DEAD')),
    metadata         JSONB
);

CREATE INDEX IF NOT EXISTS idx_pph_service_type    ON platform_pod_heartbeat (service_type, status);
CREATE INDEX IF NOT EXISTS idx_pph_last_heartbeat  ON platform_pod_heartbeat (last_heartbeat);

COMMENT ON TABLE platform_pod_heartbeat IS
    'Per-pod cluster membership + heartbeat state. Replaces Redis SCAN '
    'of platform:services:*. Heartbeat every 10s; reaper marks DEAD '
    'when last_heartbeat > 30s old. R134E: renamed from cluster_nodes '
    'to avoid collision with the multi-tenant cluster_nodes table owned '
    'by ClusterNode entity.';
