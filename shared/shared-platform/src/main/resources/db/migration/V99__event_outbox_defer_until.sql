-- =============================================================================
-- V99 — event_outbox.defer_until (per-consumer backoff window)
--
-- Adds exponential-jitter retry backoff per consumer. Without this, a failing
-- handler re-ran every 2s (the fallback poll interval) regardless of how many
-- times it had already failed — hammering PG + the target system in a tight
-- loop. With this, the poller honours a defer_until timestamp per consumer so
-- each retry spaces out: 2s → 4s → 8s → … up to a 5-min cap, with ±50% jitter
-- to avoid herd retry at the same wall-clock second.
--
-- Shape: {"sftp-service": "2026-04-20T08:00:00Z", "ftp-service": "..."}
-- Empty / missing key = "retry immediately" (first attempt or post-reset).
--
-- See docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md
-- §"Retry semantics" (post-R134t revision).
-- =============================================================================

ALTER TABLE event_outbox
    ADD COLUMN IF NOT EXISTS defer_until JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN event_outbox.defer_until IS
    'Per-consumer retry backoff. Keys are consumer names; values are ISO-8601 '
    'timestamps before which the poller must NOT re-try the row for that '
    'consumer. Set by UnifiedOutboxPoller on handler failure; cleared when '
    'ack succeeds. Exponential jitter: 2s, 4s, 8s, … capped at 5 min.';
