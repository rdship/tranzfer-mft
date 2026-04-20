-- =============================================================================
-- V98 — event_outbox (Sprint 0 of R134-external-dep-retirement)
--
-- Single outbox table that absorbs the 4 low-volume RabbitMQ event classes:
--   server.instance.*   (~5/min)
--   account.*           (~100/min)
--   flow.rule.updated   (~10/min)
--   keystore.key.rotated (~0.1/day)
--
-- The high-volume file.uploaded event class (~5k/s peak) STAYS on RabbitMQ.
-- See docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md
-- for the rationale.
--
-- Writers: publishers call OutboxWriter.write(...) inside an existing DB
--   transaction (Propagation.MANDATORY). Row is durable before commit;
--   on rollback nothing escapes. Solves the "published the event but the
--   row rolled back" inconsistency.
--
-- Readers: one OutboxPoller per consumer service. SELECT ... FOR UPDATE
--   SKIP LOCKED gives us per-consumer idempotent ack (tracked in
--   `consumed_by` JSONB) and cross-replica dedup. LISTEN/NOTIFY wakes
--   the poller on write; a 2s fallback poll handles startup + network
--   hiccups.
-- =============================================================================

CREATE TABLE IF NOT EXISTS event_outbox (
    id               BIGSERIAL    PRIMARY KEY,
    aggregate_type   VARCHAR(64)  NOT NULL,   -- "server_instance", "account", "flow_rule", "keystore_key"
    aggregate_id     VARCHAR(64)  NOT NULL,   -- PK of the affected row (UUID as text)
    event_type       VARCHAR(64)  NOT NULL,   -- "CREATED", "UPDATED", "KEY_ROTATED"
    routing_key      VARCHAR(128) NOT NULL,   -- "server.instance.created"
    payload          JSONB        NOT NULL,
    published_at     TIMESTAMPTZ,             -- NULL until every registered consumer has acked
    consumed_by      JSONB,                   -- {"sftp-service": "2026-04-20T05:01:22Z", ...}
    attempts         JSONB        NOT NULL DEFAULT '{}'::jsonb,
                                              -- {"sftp-service": 3} — per-consumer retry count
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_eo_unpublished ON event_outbox (id)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_eo_routing     ON event_outbox (routing_key, created_at);

-- Dead-letter table for events that exceeded max retries. Operator
-- dashboard surfaces this; alerts fire on non-empty.
CREATE TABLE IF NOT EXISTS event_outbox_dlq (
    id               BIGINT       PRIMARY KEY,  -- FK to event_outbox.id, logically
    aggregate_type   VARCHAR(64)  NOT NULL,
    aggregate_id     VARCHAR(64)  NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    routing_key      VARCHAR(128) NOT NULL,
    payload          JSONB        NOT NULL,
    failure_reason   TEXT,
    moved_to_dlq_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE event_outbox IS
    'Unified outbox for the 4 low-volume event classes. '
    'LISTEN/NOTIFY channel name = ''event_outbox'' (payload = routing_key). '
    'See docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md';
COMMENT ON TABLE event_outbox_dlq IS
    'Events that exceeded max-retry threshold. Alert fires on new rows. '
    'Operator can re-drive via an admin API (move back to event_outbox).';
