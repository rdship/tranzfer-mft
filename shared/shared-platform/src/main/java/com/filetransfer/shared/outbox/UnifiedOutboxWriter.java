package com.filetransfer.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes events to the unified {@code event_outbox} table (V98) for
 * the 4 low-volume event classes retired from RabbitMQ per
 * {@code docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md}:
 *
 * <ul>
 *   <li>{@code server.instance.*}</li>
 *   <li>{@code account.*}</li>
 *   <li>{@code flow.rule.updated}</li>
 *   <li>{@code keystore.key.rotated}</li>
 * </ul>
 *
 * <p><b>Transactional semantics:</b> {@code @Transactional(propagation =
 * MANDATORY)} — this writer MUST be called from within an existing
 * transaction that's also mutating the aggregate row. PG durability
 * guarantees the event row and the domain row commit or roll back
 * together — no "row persisted, event lost" or "event published, row
 * rolled back" inconsistencies.
 *
 * <p><b>Wake-up signal:</b> after the INSERT, the writer fires {@code NOTIFY
 * event_outbox, '<routing_key>'}. Pollers that have a PG {@code LISTEN}
 * connection open wake immediately (sub-second) instead of waiting for
 * the 2s fallback poll. This is the low-latency path for low-volume
 * events; the high-volume path still uses RabbitMQ (file-upload only).
 *
 * <p>Sprint 0 scope: bean exists alongside the legacy {@code OutboxWriter}
 * that writes to {@code config_event_outbox}. Callers migrate one at a
 * time per Sprint 6. Legacy writer is deleted in Sprint 7.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedOutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue an event. Must be called within a {@code @Transactional}
     * that also mutates the aggregate — otherwise durability guarantees
     * are broken. The {@code MANDATORY} propagation enforces this at
     * runtime: if no transaction is active, Spring throws immediately
     * instead of silently creating a standalone one.
     *
     * @param aggregateType short identifier like "server_instance", "account"
     * @param aggregateId   PK of the affected row (UUID as text)
     * @param eventType     verb like "CREATED", "UPDATED", "KEY_ROTATED"
     * @param routingKey    "server.instance.created", "flow.rule.updated", …
     * @param payload       event DTO — serialized to JSONB via Jackson
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, String aggregateId, String eventType,
                       String routingKey, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot serialize outbox payload for " + routingKey + ": " + e.getMessage(), e);
        }
        jdbc.update("""
            INSERT INTO event_outbox (aggregate_type, aggregate_id, event_type, routing_key, payload)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, aggregateType, aggregateId, eventType, routingKey, json);

        // Wake LISTENing pollers. Fire-and-forget — the fallback 2s poll
        // catches any NOTIFY that misses an idle connection.
        // NOTIFY payload must not contain quote/semicolon chars — routing
        // keys are alphanumeric + dot, so quoting is safe with single quotes.
        try {
            jdbc.execute("NOTIFY event_outbox, '" + routingKey.replace("'", "''") + "'");
        } catch (Exception e) {
            log.warn("[Outbox] NOTIFY failed (pollers will still pick up via fallback poll): {}",
                    e.getMessage());
        }

        log.debug("[Outbox] enqueued aggregateType={} id={} routingKey={}",
                aggregateType, aggregateId, routingKey);
    }
}
