package com.filetransfer.shared.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-service poller that drains the unified {@code event_outbox} table
 * (V98). Replaces {@code @RabbitListener} for the 4 low-volume event
 * classes retired from RabbitMQ per
 * {@code docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md}.
 *
 * <p><b>Concurrency model:</b>
 * <ul>
 *   <li>One virtual-thread-backed {@code LISTEN} loop waits on PG
 *       notifications with a 5s server-side timeout. On NOTIFY arrival
 *       (from {@link UnifiedOutboxWriter#write}), wakes the drain.</li>
 *   <li>One scheduled thread runs the drain every 2s as a fallback so a
 *       dropped NOTIFY (network hiccup, idle-connection timeout) doesn't
 *       stall event delivery more than 2s.</li>
 *   <li>The drain uses {@code SELECT ... FOR UPDATE SKIP LOCKED} so
 *       multiple replicas of the same consumer service don't double-process
 *       a row. Per-consumer ack tracked in {@code consumed_by JSONB}.</li>
 * </ul>
 *
 * <p><b>Handler registration:</b> services register one or more
 * {@link OutboxEventHandler} instances per routing-key prefix via
 * {@link #registerHandler}. The poller dispatches rows to the matching
 * handlers based on the row's {@code routing_key}; unmatched rows are
 * skipped (so a service that doesn't care about {@code account.*} just
 * doesn't register a handler for it). <b>R134V:</b> multiple handlers
 * registered at the same prefix within one service are all invoked on
 * every matching row — enables co-existing consumers like a per-service
 * {@code AccountEventConsumer} and a cross-cutting
 * {@code PartnerCacheEvictionListener} on the same {@code "account."}
 * prefix without {@code Map.put} clobbering one of them.
 *
 * <p><b>Retry semantics:</b> if a handler throws, the row stays
 * unconsumed by this service (ack doesn't get written). Next drain picks
 * it up again. After {@code maxAttempts} failures per consumer, the row
 * moves to {@code event_outbox_dlq} with the last exception message.
 *
 * <p>Sprint 0 scope: framework exists, no service registers handlers
 * yet. Services migrate one at a time per Sprint 6 of the retirement
 * plan. When zero handlers are registered, this poller is a no-op (the
 * drain runs but always selects zero relevant rows).
 */
@Slf4j
@Component
public class UnifiedOutboxPoller {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate txRequiresNew;

    @Value("${spring.application.name:unknown}")
    private String consumerName;

    @Value("${platform.outbox.max-attempts:10}")
    private int maxAttempts;

    @Value("${platform.outbox.fallback-poll-seconds:2}")
    private int fallbackPollSeconds;

    @Value("${platform.outbox.batch-size:100}")
    private int batchSize;

    public UnifiedOutboxPoller(JdbcTemplate jdbc, DataSource dataSource,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        // Each per-row unit of work is its own short transaction. Row-level
        // FOR UPDATE SKIP LOCKED is held only while we process ONE row, not
        // while we work through the batch. Minimises cross-replica contention.
        this.txRequiresNew = new TransactionTemplate(txManager);
        this.txRequiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Failure-accounting (attempts bump + DLQ move) runs in an
        // INDEPENDENT transaction so that when the main row's tx rolls back
        // (to undo the handler's partial work) the accounting writes still
        // commit. Without REQUIRES_NEW, the bumpAttempts would also be
        // rolled back and the DLQ threshold would never fire.
    }

    /**
     * Routing-key prefix → list of handlers registered for that prefix.
     *
     * <p><b>R134V:</b> multi-handler support. Previously this was a
     * {@code Map<String, OutboxEventHandler>} which meant two beans in the
     * same service registering the same prefix clobbered each other via
     * {@code put} (init-order fragile). With two PartnerCacheEvictionListener
     * + AccountEventConsumer both needing {@code "account."} in sftp/ftp/
     * ftp-web, the clobber was a real correctness risk. List-valued entries
     * let both fire on the same row; {@link CopyOnWriteArrayList} is safe
     * for register-at-bootstrap / read-on-every-drain access pattern.
     *
     * <p>Prefix-match semantics unchanged: longest matching prefix wins for
     * any given routing key — {@code "flow.rule.updated"} still beats
     * {@code "flow."}. The change only lets multiple handlers coexist
     * <em>at</em> the winning prefix.
     */
    private final Map<String, List<OutboxEventHandler>> handlers = new ConcurrentHashMap<>();

    /** true while the LISTEN loop should keep running. */
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ScheduledExecutorService fallbackScheduler;
    private Thread listenThread;

    /**
     * Register a handler for events whose routing key starts with
     * {@code routingKeyPrefix}. Call from a {@code @Configuration} class
     * during application bootstrap.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "server.instance."} — matches {@code server.instance.created}, …</li>
     *   <li>{@code "account."} — matches {@code account.created}, {@code account.updated}</li>
     *   <li>{@code "flow.rule.updated"} — matches only that exact key</li>
     * </ul>
     *
     * <p><b>R134V — multi-handler:</b> multiple registrations on the same
     * prefix within one service are all retained and invoked on every
     * matching row (in registration order). Enables e.g.
     * {@code AccountEventConsumer} + {@code PartnerCacheEvictionListener}
     * to co-exist on {@code "account."} without the init-order clobber
     * that {@code Map.put} previously inflicted. Handler contract
     * (idempotency, throw-to-retry) is unchanged; the poller just fans
     * the row out to every handler at the matched prefix.
     */
    public void registerHandler(String routingKeyPrefix, OutboxEventHandler handler) {
        handlers.computeIfAbsent(routingKeyPrefix, k -> new CopyOnWriteArrayList<>())
                .add(handler);
        log.info("[Outbox/{}] Registered handler for routing key prefix '{}' (total at prefix: {})",
                consumerName, routingKeyPrefix, handlers.get(routingKeyPrefix).size());
    }

    @PostConstruct
    public void start() {
        // Fallback scheduled poll — runs even when LISTEN is down.
        fallbackScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().name("outbox-fallback-poll").unstarted(r));
        fallbackScheduler.scheduleAtFixedRate(
                this::drainOnceSafely, fallbackPollSeconds, fallbackPollSeconds, TimeUnit.SECONDS);

        // LISTEN loop — virtual thread; blocks on PG notifications.
        listenThread = Thread.ofVirtual()
                .name("outbox-listen-" + consumerName)
                .start(this::listenLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (listenThread != null) listenThread.interrupt();
        if (fallbackScheduler != null) fallbackScheduler.shutdownNow();
    }

    private void listenLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try (Connection c = dataSource.getConnection()) {
                c.createStatement().execute("LISTEN event_outbox");
                PGConnection pg = c.unwrap(PGConnection.class);
                // Drain once on startup in case rows exist from before we
                // started listening.
                drainOnceSafely();
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    PGNotification[] notifs = pg.getNotifications(5_000);
                    if (notifs != null && notifs.length > 0) {
                        drainOnceSafely();
                    }
                    // Timeout (no notifs) still falls through — the fallback
                    // scheduler separately triggers a drain every 2s.
                }
            } catch (Exception e) {
                log.warn("[Outbox/{}] LISTEN connection crashed (will reconnect in 5s): {}",
                        consumerName, e.getMessage());
                sleep(5_000);
            }
        }
    }

    private void drainOnceSafely() {
        try {
            drainOnce();
        } catch (Exception e) {
            // Never propagate out of the scheduled thread — next tick
            // retries. Log once loudly so the issue is visible.
            log.error("[Outbox/{}] drain threw: {}", consumerName, e.getMessage(), e);
        }
    }

    /**
     * Drain one batch. Each row is processed inside its OWN transaction so
     * the SKIP LOCKED lock is released immediately after that row's work is
     * done — minimises cross-replica contention and keeps long-running
     * handlers from starving peers.
     *
     * <p>The outer loop pulls up to {@code batchSize} rows per drain; in
     * practice the fallback scheduler + NOTIFY wake-up make re-draining
     * fast enough that batching is almost irrelevant. We keep it because
     * PG prefers a single plan over many individual one-row SELECTs.
     */
    protected void drainOnce() {
        if (handlers.isEmpty()) return; // service doesn't consume any events

        // LIKE ANY patterns for the routing-key prefixes we care about.
        String[] likePatterns = handlers.keySet().stream()
                .map(p -> p + "%")
                .toArray(String[]::new);

        // Claim a batch of rows for this drain. Each row is processed in
        // its own transaction below so this SELECT closes its own tx fast.
        //
        // R134t: added defer_until filter. Rows whose defer_until[consumer]
        // is in the future (exponential-jitter backoff window hasn't elapsed
        // since the last failed attempt) are skipped — the next drain after
        // the backoff expires picks them up. Without this, a repeatedly-
        // failing handler retried every 2s forever, hammering PG + the
        // target system. See docs/rd/.../02-rabbitmq-retirement.md "Retry
        // semantics" (R134t revision).
        // R134F: use jsonb_exists() function form instead of the `?` operator.
        // PG JSONB has a `?` operator meaning "does key exist" — but JDBC's
        // PreparedStatement treats every `?` in the SQL as a positional
        // parameter. So `(consumed_by ? ?)` looked like TWO JDBC placeholders
        // to the driver; it expected 7 args, we supplied 5, and every drain
        // tick crashed with "No value specified for parameter 6". Caught in
        // tester R134C-E-runtime-verification.md — 1,176 occurrences per
        // ~10 min across the stack, which killed the outbox consumer on every
        // registered service (R134D's keystore.key.rotated, R134y's pod-
        // heartbeat readers, etc.). jsonb_exists(col, ?) is semantically
        // identical and leaves JDBC alone.
        List<Long> candidateIds = tx.execute(status ->
            jdbc.queryForList(
                """
                SELECT id FROM event_outbox
                 WHERE published_at IS NULL
                   AND (consumed_by IS NULL OR NOT jsonb_exists(consumed_by, ?))
                   AND routing_key LIKE ANY (?)
                   AND (defer_until IS NULL
                        OR NOT jsonb_exists(defer_until, ?)
                        OR (defer_until ->> ?)::timestamptz <= now())
                 ORDER BY id
                 LIMIT ?
                """,
                Long.class,
                consumerName, likePatterns, consumerName, consumerName, batchSize)
        );
        if (candidateIds == null || candidateIds.isEmpty()) return;

        for (Long id : candidateIds) {
            processOneRow(id);
        }
    }

    /**
     * One row, one transaction. Re-fetches the row WITH FOR UPDATE SKIP
     * LOCKED so another replica that grabbed it between our batch SELECT
     * and this fetch is honoured (row-level race handled cleanly).
     */
    private void processOneRow(long rowId) {
        tx.executeWithoutResult(status -> {
            // R134F: `?` → jsonb_exists() — see drainOnce() for the rationale.
            List<OutboxRow> rows = jdbc.query(
                """
                SELECT id, aggregate_type, aggregate_id, event_type, routing_key,
                       payload, created_at
                  FROM event_outbox
                 WHERE id = ?
                   AND published_at IS NULL
                   AND (consumed_by IS NULL OR NOT jsonb_exists(consumed_by, ?))
                 FOR UPDATE SKIP LOCKED
                """,
                new OutboxRowMapper(),
                rowId, consumerName);
            if (rows.isEmpty()) return; // another replica got it

            OutboxRow row = rows.get(0);
            List<OutboxEventHandler> matched = findHandlers(row.routingKey);
            if (matched.isEmpty()) {
                // Should never happen — we pre-filtered via LIKE ANY — but
                // if the handler map mutated between SELECTs, skip cleanly.
                return;
            }

            // R134V multi-handler: invoke every handler at the matched
            // prefix within this row's single transaction. If ANY handler
            // throws, the whole tx rolls back (including any partial work
            // done by earlier handlers in this iteration) and the row
            // stays un-ack'd for retry. All handlers must be idempotent
            // per the OutboxEventHandler contract (R134t).
            try {
                for (OutboxEventHandler handler : matched) {
                    handler.handle(row);
                }
                markConsumed(row.id);
            } catch (Exception e) {
                log.warn("[Outbox/{}] handler threw on row id={} routing={}: {} — row will retry",
                        consumerName, row.id, row.routingKey, e.getMessage());
                // Rollback undoes any partial work handlers did INSIDE
                // this tx (handlers often write to other tables — those
                // changes must not land if we'll retry). markConsumed was
                // never called so the row stays un-ack'd from our view.
                // Failure accounting runs in the separate REQUIRES_NEW tx
                // below.
                status.setRollbackOnly();
            }
        });

        // Failure accounting — runs OUTSIDE the row's tx. If the handler
        // threw, the row's tx rolled back including any partial work but
        // did NOT roll back this tx (REQUIRES_NEW). If the handler succeeded
        // and markConsumed committed, the probe query below returns false
        // (row ack'd) and this whole block is a no-op.
        // R134F: `?` → jsonb_exists() — see drainOnce() for the rationale.
        Boolean rowStillUnacked = txRequiresNew.execute(status ->
            jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM event_outbox
                     WHERE id = ?
                       AND published_at IS NULL
                       AND (consumed_by IS NULL OR NOT jsonb_exists(consumed_by, ?))
                )
                """, Boolean.class, rowId, consumerName));

        if (Boolean.TRUE.equals(rowStillUnacked)) {
            txRequiresNew.executeWithoutResult(status -> {
                int attempts = bumpAttempts(rowId);
                if (attempts >= maxAttempts) {
                    List<OutboxRow> rs = jdbc.query(
                        """
                        SELECT id, aggregate_type, aggregate_id, event_type, routing_key,
                               payload, created_at
                          FROM event_outbox WHERE id = ?
                        """,
                        new OutboxRowMapper(), rowId);
                    if (!rs.isEmpty()) {
                        moveToDlq(rs.get(0), "max-attempts exceeded (" + attempts + ")");
                    }
                }
            });
        }
    }

    private List<OutboxEventHandler> findHandlers(String routingKey) {
        // Longest-prefix-wins match so "flow.rule.updated" beats "flow." if
        // both are registered. At the matched prefix, returns ALL handlers
        // registered there (R134V multi-handler support) — caller invokes
        // every one on the same row.
        String best = null;
        for (String prefix : handlers.keySet()) {
            if (routingKey.startsWith(prefix)
                    && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        if (best == null) return Collections.emptyList();
        List<OutboxEventHandler> matched = handlers.get(best);
        return matched != null ? matched : Collections.emptyList();
    }

    private void markConsumed(long rowId) {
        String ackValue = "\"" + Instant.now() + "\"";
        jdbc.update("""
            UPDATE event_outbox
               SET consumed_by = jsonb_set(COALESCE(consumed_by, '{}'::jsonb),
                                             ARRAY[?], ?::jsonb, true)
             WHERE id = ?
            """, consumerName, ackValue, rowId);
    }

    private int bumpAttempts(long rowId) {
        // Read-modify-write — serialized by row lock in our transaction.
        Integer attempts = jdbc.queryForObject("""
            UPDATE event_outbox
               SET attempts = jsonb_set(COALESCE(attempts, '{}'::jsonb),
                                        ARRAY[?],
                                        to_jsonb(COALESCE((attempts -> ?)::int, 0) + 1),
                                        true)
             WHERE id = ?
         RETURNING (attempts -> ?)::int
            """, Integer.class, consumerName, consumerName, rowId, consumerName);
        int count = attempts == null ? 0 : attempts;

        // R134t — exponential-jitter backoff: 2s * 2^(count-1) with ±50%
        // jitter, capped at 5 min. Jitter prevents herd-retry at the same
        // wall-clock second when many rows failed together. The 2s base
        // matches the fallback-poll cadence so the FIRST retry lands on the
        // next drain; subsequent retries progressively space out.
        Instant deferUntil = computeBackoff(count);
        String deferValue = "\"" + deferUntil + "\"";
        jdbc.update("""
            UPDATE event_outbox
               SET defer_until = jsonb_set(COALESCE(defer_until, '{}'::jsonb),
                                             ARRAY[?], ?::jsonb, true)
             WHERE id = ?
            """, consumerName, deferValue, rowId);
        return count;
    }

    /**
     * Exponential-jitter backoff. 2s × 2^(attempts-1) base, ±50% jitter,
     * 5-minute cap. attempts=1 → ~1–3s. attempts=2 → ~2–6s. attempts=3 →
     * ~4–12s. … attempts=8 → ~2.5–7.5min (clamped to 5min). Jitter is
     * multiplicative so rows with identical attempt counts still land on
     * different retry times.
     */
    static Instant computeBackoff(int attempts) {
        if (attempts <= 0) return Instant.now();
        long baseMs = 2_000L * (1L << Math.min(attempts - 1, 20));  // 2^20 guards against overflow
        baseMs = Math.min(baseMs, 300_000L);                         // cap at 5 min
        double jitter = 0.5 + Math.random();                          // 0.5× .. 1.5×
        long jitteredMs = (long) (baseMs * jitter);
        return Instant.now().plusMillis(Math.min(jitteredMs, 300_000L));
    }

    private void moveToDlq(OutboxRow row, String failureReason) {
        try {
            jdbc.update("""
                INSERT INTO event_outbox_dlq
                    (id, aggregate_type, aggregate_id, event_type, routing_key, payload, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                row.id, row.aggregateType, row.aggregateId, row.eventType,
                row.routingKey, row.payload, failureReason);
            // Mark DONE so the main poller stops picking it up. Other
            // consumers that haven't reached max-attempts still process it.
            markConsumed(row.id);
            log.error("[Outbox/{}] moved row id={} routing={} to DLQ after {} attempts: {}",
                    consumerName, row.id, row.routingKey, maxAttempts, failureReason);
        } catch (Exception e) {
            log.error("[Outbox/{}] DLQ insert failed for row id={}: {}",
                    consumerName, row.id, e.getMessage(), e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Row POJO passed into handlers. */
    public record OutboxRow(long id, String aggregateType, String aggregateId,
                            String eventType, String routingKey, String payload,
                            Map<String, Integer> attempts, Instant createdAt) {
        /** Convenience: deserialize payload to a DTO class. */
        public <T> T as(Class<T> type, ObjectMapper om) {
            try {
                return om.readValue(payload, type);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot deserialize outbox row " + id + " to " + type.getSimpleName(), e);
            }
        }
    }

    /**
     * Handler contract — services implement one per routing-key family.
     *
     * <p><b>IDEMPOTENCY IS MANDATORY.</b> The outbox delivers each event
     * <em>at least once</em> per consumer. Retries happen on:
     * <ul>
     *   <li>Handler throwing (rollback + backoff + re-drain)</li>
     *   <li>Pod crash mid-handle (tx rollback; next pod drain picks up)</li>
     *   <li>LISTEN/NOTIFY miss followed by fallback poll (extremely rare
     *       duplicate if the row was being processed right as the crash hit
     *       and the ack tx rolled back)</li>
     * </ul>
     *
     * <p>Implementations MUST produce the same observable effect whether
     * invoked once or N times for the same row. Patterns that get this right:
     * <ul>
     *   <li><b>INSERT ... ON CONFLICT DO NOTHING</b> — natural idempotency
     *       via a unique business key (e.g. {@code aggregate_id + event_type}).</li>
     *   <li><b>UPSERT with last-write-wins</b> — safe when the event payload
     *       is fully state-reconstructing.</li>
     *   <li><b>Explicit "already processed" check</b> — a small table keyed
     *       by {@code (consumer, aggregate_id, event_type)} that the handler
     *       tests first; skip if already present.</li>
     * </ul>
     *
     * <p>Patterns that break under retries:
     * <ul>
     *   <li>{@code count++} increments — doubles on every retry</li>
     *   <li>Non-idempotent external API calls (e.g. unconditional
     *       "create user" or "charge card") — use idempotency keys on the
     *       external call</li>
     *   <li>Appending to a queue without dedup — downstream sees duplicates</li>
     * </ul>
     *
     * <p>On exception, the row is NOT acked for this consumer and will be
     * re-delivered after the exponential-jitter backoff window elapses.
     * Rows that fail {@code platform.outbox.max-attempts} times land in
     * {@code event_outbox_dlq} and operator intervention is required.
     */
    @FunctionalInterface
    public interface OutboxEventHandler {
        void handle(OutboxRow row) throws Exception;
    }

    /** Jackson-aware row mapper; stores payload as raw JSON string. */
    private static class OutboxRowMapper implements RowMapper<OutboxRow> {
        @Override public OutboxRow mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
            Timestamp created = rs.getTimestamp("created_at");
            return new OutboxRow(
                rs.getLong("id"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("routing_key"),
                rs.getString("payload"),   // JSONB comes back as String from JdbcTemplate
                Map.of(),                   // attempts parsing omitted for now; DLQ threshold handled via bumpAttempts
                created != null ? created.toInstant() : Instant.now()
            );
        }
    }
}
