-- ─────────────────────────────────────────────────────────────────────────────
-- V64: Dynamic listener lifecycle
--
-- 1. config_event_outbox: transactional outbox for ServerInstance change events.
--    Ensures DB write + event publish are atomic. An OutboxPoller drains the
--    table and publishes to RabbitMQ; unsent events survive crashes.
--
-- 2. server_instances runtime state columns: track actual bind state so the
--    UI / Sentinel can see when a configured listener failed to bind.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS config_event_outbox (
    id               BIGSERIAL    PRIMARY KEY,
    aggregate_type   VARCHAR(64)  NOT NULL,          -- e.g. "server_instance"
    aggregate_id     VARCHAR(64)  NOT NULL,          -- UUID string
    event_type       VARCHAR(64)  NOT NULL,          -- created | updated | deleted | activated | deactivated
    routing_key      VARCHAR(128) NOT NULL,          -- RabbitMQ routing key
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    last_error       TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON config_event_outbox (created_at)
    WHERE published_at IS NULL;

-- Runtime bind state per ServerInstance ──────────────────────────────────────
ALTER TABLE server_instances
    ADD COLUMN IF NOT EXISTS bind_state            VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS bind_error            TEXT,
    ADD COLUMN IF NOT EXISTS last_bind_attempt_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS bound_node            VARCHAR(128);

-- Port uniqueness — prevent two active instances from claiming the same
-- internal port on the same host. Partial unique index: only when active.
CREATE UNIQUE INDEX IF NOT EXISTS uk_server_instance_host_port_active
    ON server_instances (internal_host, internal_port)
    WHERE active = true;
