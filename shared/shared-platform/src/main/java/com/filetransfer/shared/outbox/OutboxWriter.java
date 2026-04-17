package com.filetransfer.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.entity.core.ConfigEventOutbox;
import com.filetransfer.shared.repository.core.ConfigEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes config-change events to the outbox table inside the caller's
 * transaction. The actual RabbitMQ publish happens later, asynchronously,
 * in OutboxPoller.
 *
 * <p>Call this from inside a {@code @Transactional} method that also mutates
 * the aggregate. The DB commit and event record are atomic — crashes between
 * commit and publish are handled by the poller on next tick.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final ConfigEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue an event. Must be called within a @Transactional that also
     * mutates the aggregate; otherwise durability guarantees are lost.
     */
    public void write(String aggregateType, String aggregateId, String eventType,
                      String routingKey, Object payload) {
        Map<String, Object> payloadMap = objectMapper.convertValue(payload, Map.class);
        ConfigEventOutbox row = ConfigEventOutbox.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .routingKey(routingKey)
                .payload(payloadMap)
                .build();
        outboxRepository.save(row);
        log.debug("Outbox enqueued: type={} id={} routingKey={}", eventType, aggregateId, routingKey);
    }
}
