---
title: "RabbitMQ Retirement — partial, event-shape-preserving"
status: design
depends_on: 01-redis-retirement.md
---

# RabbitMQ Retirement — Partial Path (keeping it only for file-upload)

The audit surfaced 5 distinct event classes on the `file-transfer.events` exchange. Each has a different traffic profile, ack semantics, and fanout shape. A blanket "move it all to Postgres" is wrong — file uploads are ~5k evt/s peak, which PG `LISTEN/NOTIFY` does not handle well without partitioning. A blanket "keep RabbitMQ" is also wrong — four of the five event classes are <100 evt/minute, for which a broker is overkill.

**Decision**: retire RabbitMQ as a generic broker; keep one slim RabbitMQ deployment exclusively for the file-upload event stream. Move the other four to an outbox-poller pattern backed by Postgres `LISTEN/NOTIFY` + table polling.

## Event classes (traffic profile)

| Event | Publisher | Consumers | Rate | Transport decision |
|---|---|---|---|---|
| `server.instance.*` | onboarding-api outbox | sftp/ftp/ftp-web/https/as2 services | ~5/min | **Postgres outbox + LISTEN/NOTIFY** |
| `account.*` | onboarding-api messaging | sftp/ftp/ftp-web (account cache invalidation) | ~100/min | **Postgres outbox + LISTEN/NOTIFY** |
| `flow.rule.updated` | config-service | sftp/ftp/ftp-web/gateway/as2 (hot-reload compiled rules) | ~10/min | **Postgres outbox + LISTEN/NOTIFY** |
| `keystore.key.rotated` | keystore-manager | sftp-service (rebind listeners w/ new cert) | ~0.1/day | **Postgres outbox + LISTEN/NOTIFY** |
| `file.uploaded` | routing engines (sftp/ftp/ftp-web/https/as2) | file-upload routing consumer | **~5k/s peak** | **Keep RabbitMQ** |

Plus fanout consumers of `file.uploaded`:
- ActivityMonitor SSE broadcast (~500/s)
- NotificationEventConsumer (~500/s)
- AI threat analysis async consumer (~500/s)

These piggyback on the same `file.uploaded` event. Keeping RabbitMQ for the one high-throughput class means the fanout still works.

## The outbox-poller design (for the 4 low-rate classes)

### Table: `event_outbox` (one table, shared by all 4 event classes)

```sql
CREATE TABLE IF NOT EXISTS event_outbox (
    id               BIGSERIAL    PRIMARY KEY,
    aggregate_type   VARCHAR(64)  NOT NULL,    -- "server_instance", "account", "flow_rule", "keystore_key"
    aggregate_id     VARCHAR(64)  NOT NULL,    -- the PK of the affected row (UUID as text)
    event_type       VARCHAR(64)  NOT NULL,    -- "CREATED", "UPDATED", "KEY_ROTATED"
    routing_key      VARCHAR(128) NOT NULL,    -- "server.instance.created"
    payload          JSONB        NOT NULL,    -- serialized event DTO
    published_at     TIMESTAMPTZ,              -- NULL until a consumer claims it
    consumed_by      JSONB,                    -- {"sftp-service": "2026-04-20T...", ...}
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_eo_unpublished ON event_outbox (id) WHERE published_at IS NULL;
CREATE INDEX idx_eo_recent      ON event_outbox (created_at) WHERE published_at IS NOT NULL;
```

### Publisher side (replaces `RabbitTemplate.convertAndSend`)

Already partially wired — `OutboxWriter` exists in shared-platform today for some events. Generalize it:

```java
@Component
@RequiredArgsConstructor
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    @Transactional(propagation = Propagation.MANDATORY)  // caller owns the tx
    public void write(String aggregateType, String aggregateId, String eventType,
                       String routingKey, Object payload) {
        jdbc.update("""
            INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, routing_key, payload)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, aggregateType, aggregateId, eventType, routingKey,
            toJson(payload));
        // Fire LISTEN/NOTIFY so subscribers wake without polling.
        jdbc.execute("NOTIFY event_outbox, '" + routingKey + "'");
    }
}
```

The `MANDATORY` propagation means callers already in a transaction (e.g., `ServerInstanceService.create` → `save()` + `publishChange()`) get the event durable-persisted in the same tx as the row. No "sent event but row roll-back" bug.

### Consumer side (replaces `@RabbitListener`)

```java
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Map<String, OutboxEventHandler> handlers;  // keyed by routing-key prefix
    private final String consumerName;   // "sftp-service", per-deployment

    @PostConstruct
    public void start() {
        // Background thread: LISTEN on "event_outbox" channel; wake on NOTIFY
        // or poll every 2s as a safety net.
        Thread.ofVirtual().start(this::listenLoop);
        ScheduledExecutors.newSingleThread().scheduleAtFixedRate(
                this::drainOnce, 0, 2, TimeUnit.SECONDS);
    }

    private void listenLoop() {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("LISTEN event_outbox");
            var pgConn = c.unwrap(PGConnection.class);
            while (!Thread.currentThread().isInterrupted()) {
                PGNotification[] notifs = pgConn.getNotifications(5_000);
                if (notifs != null) drainOnce();
            }
        } catch (Exception e) {
            log.error("LISTEN loop crashed; polling will cover", e);
        }
    }

    @Transactional
    private void drainOnce() {
        // FOR UPDATE SKIP LOCKED so multiple consumer instances of this service
        // don't double-process the same row. Each consumer marks itself in
        // consumed_by; published_at is set once the LAST consumer acks.
        List<OutboxRow> batch = jdbc.query("""
            SELECT * FROM event_outbox
            WHERE published_at IS NULL
              AND (consumed_by IS NULL OR NOT (consumed_by ? ?))
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT 100
            """, new OutboxRowMapper(), consumerName);

        for (OutboxRow row : batch) {
            handlers.get(routingKeyPrefix(row.routingKey)).handle(row);
            jdbc.update("""
                UPDATE event_outbox
                SET consumed_by = jsonb_set(COALESCE(consumed_by, '{}'::jsonb), ?, ?::jsonb, true)
                WHERE id = ?
                """, "{" + consumerName + "}", "\"" + Instant.now() + "\"", row.id);
        }
    }
}
```

### Retry semantics (R134t revision)

RabbitMQ's DLQ + 3-retry pattern is replaced with:

- **Per-consumer ack**: `consumed_by` JSONB tracks which consumer has processed the row. A row stays active until every registered consumer for its routing-key family has ack'd.
- **Per-consumer attempts**: `attempts` JSONB counts failures per consumer.
- **Per-consumer backoff** (V99, `defer_until` column): after failure N, the next retry is deferred by `min(2s × 2^(N-1), 5min)` with ±50% jitter. The poller's SELECT filters out rows whose `defer_until[consumer]` is still in the future, so a repeatedly-failing handler doesn't hammer PG + the target system every 2s.
- **DLQ**: after `platform.outbox.max-attempts` (default 10) failures for any consumer, the row is moved to `event_outbox_dlq`. An alert fires on non-empty DLQ.

Mechanical code transform per existing `@RabbitListener`: extract the body of `onChange()` into a method-reference, register it in the `handlers` map keyed by routing-key prefix. ~30 lines deleted per consumer; ~15 lines added.

### Idempotency contract — MANDATORY (R134t)

The outbox delivers each event **at least once** per consumer. Every handler must be idempotent — same observable effect whether invoked once or N times for the same `(rowId, consumerName)` pair. Retries happen on:

- Handler throw (rollback + backoff + re-drain)
- Pod crash mid-handle (tx rollback; peer pod's drain picks up)
- Ack tx rollback (extremely rare — the only window for a duplicate)

**Patterns that get this right:**
- `INSERT ... ON CONFLICT DO NOTHING` with a natural unique key (e.g. `aggregate_id + event_type`)
- UPSERT with last-write-wins when the event payload is fully state-reconstructing
- A small `processed_events (consumer, aggregate_id, event_type)` guard-table checked first by the handler

**Patterns that break under retries:**
- `count++` increments (doubles on every retry)
- Non-idempotent external API calls (use idempotency-key headers on the external call instead)
- Appending to a queue without dedup

This contract is documented on the `OutboxEventHandler` interface Javadoc so implementers can't miss it.

### Why transactional outbox, not CDC?

A common alternative is **Change Data Capture** (e.g. Debezium tailing the PG WAL, publishing every committed row change as an event). We evaluated and rejected CDC for this migration:

| Dimension | Transactional outbox (chosen) | CDC (rejected) |
|---|---|---|
| Explicit vs implicit events | Application decides what's an event | Every row change is an event (chatty, leaks schema) |
| Coupling to DB engine | Pure SQL; portable to any PG-compatible store | Tightly coupled to WAL format + Debezium connector |
| Operator setup | Zero — table + poller | Debezium + Kafka Connect + schema registry |
| Event shape control | Full — we pick routing_key + payload | Derived from row shape; schema changes become events |
| Migration cost from RabbitMQ | One poller bean; same DTOs | Full Debezium deployment + consumer rewrites |

CDC is a great fit for **exfiltration pipelines** (stream DB changes to analytics / a data lake / search indexes). For **service-to-service domain events**, transactional outbox gives the application control and keeps the transport boring.

If we ever need CDC for a different use case (e.g. streaming to a data warehouse), we can add Debezium alongside — it doesn't conflict with the outbox.

### Sharding event_outbox at scale (R134t)

Our V98 migration uses monthly range-partitioning on `created_at`. This scales well for RETENTION (drop old partitions) but NOT for WRITE CONCURRENCY at very high rates — every writer in the current month targets the same active partition.

When/if we exceed ~2k events/sec on the outbox (well beyond current load), the right move is **hash-sharding on `aggregate_id`** over an extra partition key:

```sql
-- Conceptual; actual implementation via composite partitioning:
-- PARTITION BY LIST (hash(aggregate_id) % 16) SUBPARTITION BY RANGE (created_at)
```

Benefits:
- 16× write concurrency (one shard per partition, independent heap + index)
- Same-aggregate events stay on the same shard → FIFO ordering for the aggregate is preserved by `ORDER BY id` within the shard

We don't implement this in Sprint 0 because it's premature for current volume. The decision is deferred to Sprint 8+ (post-Redis-retirement soak) gated on observed write-rate metrics.

### Throughput limit

`LISTEN/NOTIFY` with a 2s poll backoff handles ~2-5k evt/s sustained. The four retired event classes total ~105/min peak. Headroom: 3 orders of magnitude. Good.

## The surviving RabbitMQ deployment

**Single exchange**: `file-transfer.events`
**Single queue**: `file-upload-routing`
**Single routing key**: `file.uploaded`
**Single consumer class per service**: `FileUploadEventConsumer` (one per service with routing logic)

Other queues that today piggyback on this exchange (activity-stream, notification.events) — move to separate topology:
- Activity stream SSE: gets its data by querying the `file_transfer_records` table directly (the row is always written before the event fires). Drop the SSE-via-RabbitMQ consumer; have SSE poll at 1s for new rows. Cheap enough for admin UI traffic.
- NotificationEventConsumer: moves to the outbox poller pattern — low enough volume (~500/s burst, ~10/s sustained) to fit in a poll loop.

The surviving RabbitMQ container becomes:
- ~50 MB RAM (one queue, no management plugin in prod)
- One purpose (file-upload routing) — operators know why it's there

## Migration sequencing (within this dep)

1. **Phase 0** (3 days): Ship `event_outbox` table + `OutboxWriter` generalization + `OutboxPoller` as a parallel path. Zero behaviour change — no consumer is switched yet.
2. **Phase 1** (1 week): Migrate one event class per commit, shadow-publishing (outbox AND RabbitMQ) so consumers can be on either. Commit order: `keystore.key.rotated` (0.1/day, safest) → `flow.rule.updated` → `account.*` → `server.instance.*`.
3. **Phase 2** (3 days): Once every non-file-upload consumer reads from outbox, stop publishing those to RabbitMQ. Delete the obsolete `@RabbitListener` beans.
4. **Phase 3** (3 days): Slim down the RabbitMQ compose entry to just the one queue/exchange pair; remove the management plugin; lower memory limit.

**Total: ~2 weeks.** Lower risk than Redis retirement because the outbox pattern is already 60% in place.

## Invariant check

Against `project_proven_invariants.md`:
- **R64 (flow rules reload without service restart)**: preserved — the `flow.rule.updated` event still fires, just via the outbox. Consumer latency within 2s (the polling floor) vs RabbitMQ's <200ms. Acceptable for config changes.
- **R73 (listener bind/unbind via ServerInstance events)**: preserved — event shape unchanged; transport changed.
- **R91 (keystore rotation rebinds SFTP listeners)**: preserved; latency goes from <200ms to <5s (the listener container runs through its poll loop). Rotations happen rarely; operator doesn't notice.
- **R86 (file-upload routing at 5k/s)**: preserved — this is the one event we DON'T move.

## Acceptance criteria

- [ ] Only `file.uploaded` routing key on the surviving RabbitMQ exchange
- [ ] `RabbitTemplate` used in exactly one publisher class (the file-upload router)
- [ ] `@RabbitListener` used in exactly one consumer class per service (`FileUploadEventConsumer`)
- [ ] `event_outbox` table growing at its expected rate (<1000 rows/day on a small deployment)
- [ ] All 4 migrated event classes have outbox consumers logging WARN-clean for 24h
- [ ] R134j regression flow unchanged

Go to `03-storage-manager-evolution.md` next.
