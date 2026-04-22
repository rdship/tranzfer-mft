-- R134X — Sprint 7 Phase B subtractive cleanup.
--
-- Drops the legacy config_event_outbox table + its index. This table was
-- introduced in V64 as a transactional outbox for ServerInstance change
-- events; it was drained by OutboxPoller → RabbitMQ for @RabbitListener
-- consumers.
--
-- As of R134X:
--   - All 4 dual-path events (keystore.key.rotated, flow.rule.updated,
--     account.*, server.instance.*) publish to event_outbox (V98) only.
--   - Every consumer drains via UnifiedOutboxPoller (R134V multi-handler
--     registry allows multiple consumers per prefix).
--   - The OutboxWriter / OutboxPoller / ConfigEventOutbox entity +
--     repository Java classes are deleted in the same commit.
--
-- config_event_outbox should be empty at R134X deploy time (publisher was
-- flipped in R134U — any remaining rows are >24h old and already drained).
-- IF NOT EMPTY, the DROP will still succeed; we accept losing historical
-- rows since they've already been delivered by the legacy poller.
--
-- IF EXISTS makes this migration idempotent and safe on clusters that
-- never had V64 (hypothetically new deployments, though all current
-- clusters do).

DROP INDEX IF EXISTS idx_config_event_outbox_unpublished;
DROP TABLE IF EXISTS config_event_outbox;
