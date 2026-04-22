package com.filetransfer.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.notification.service.NotificationDispatcher;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Universal platform-event consumer for the notification rule engine.
 *
 * <p><b>R134Y — Sprint 8:</b> now dual-transport:
 * <ul>
 *   <li><b>Outbox</b> — registers one {@link com.filetransfer.shared.outbox.OutboxEventHandler}
 *       per migrated routing-key prefix ({@code keystore.}, {@code flow.rule.},
 *       {@code account.}, {@code server.instance.}) via R134V's multi-handler cap.
 *       These are the 4 event classes retired from RabbitMQ in Sprint 6 / 7.</li>
 *   <li><b>RabbitMQ</b> — retained {@code @RabbitListener} on the
 *       {@code notification.events} queue (bound to {@code #} on the
 *       {@code file-transfer.events} exchange) still receives the surviving
 *       RabbitMQ traffic: {@code file.uploaded} + {@code transfer.*}.
 *       With publishers for the 4 migrated classes no longer emitting to
 *       RabbitMQ, this listener's effective traffic is just file-upload
 *       lifecycle.</li>
 *   <li><b>Fabric</b> — feature-flagged additive path, unchanged.</li>
 * </ul>
 *
 * <p>Graceful degradation: if processing fails the event is logged but
 * never blocks the originating service. Dispatcher dedupes by
 * {@code trackId + eventType} so duplicate delivery across transports is
 * safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    /** R134Y — universal consumer over outbox for the 4 migrated event classes. */
    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.error("[R134Y][NotificationEventConsumer][boot] UnifiedOutboxPoller missing — "
                    + "outbox-migrated events (keystore/flow.rule/account/server.instance) will NOT reach the notification dispatcher");
            return;
        }
        for (String prefix : new String[] {
                "keystore.", "flow.rule.", "account.", "server.instance." }) {
            outboxPoller.registerHandler(prefix, row -> {
                log.info("[R134Y][NotificationEventConsumer][outbox] row id={} routingKey={} aggregateId={}",
                        row.id(), row.routingKey(), row.aggregateId());
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = row.as(Map.class, objectMapper);
                if (payload != null) {
                    handleEvent(payload, row.routingKey());
                }
            });
        }
        log.info("[R134Y][NotificationEventConsumer][boot] outbox handlers registered on "
                + "[keystore., flow.rule., account., server.instance.]; @RabbitListener still active for file.uploaded + transfer.*");
    }

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "notification-service");
        try {
            eventFabricBridge.subscribeNotificationEvents(
                com.filetransfer.shared.fabric.FabricGroupIds.shared(serviceName, "events.notification"),
                event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload != null) {
                        handleEvent(payload, event.getKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric notification event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[Notification] Failed to subscribe to fabric notification events: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.notification-events:notification.events}")
    public void handleEvent(Message message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.readValue(message.getBody(), Map.class);

            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            handleEvent(eventData, routingKey);

        } catch (Exception e) {
            // Graceful degradation: log and continue, never block the producer
            log.warn("Failed to process notification event: {}", e.getMessage());
        }
    }

    /**
     * Idempotent handler shared between RabbitMQ and Fabric paths.
     * For the fabric path, the "routing key fallback" is the partition key
     * (which the publisher sets to the event type).
     */
    private void handleEvent(Map<String, Object> eventData, String routingKeyOrFallback) {
        try {
            String eventType = extractEventType(eventData, routingKeyOrFallback);

            if (eventType == null || eventType.isBlank()) {
                log.debug("Ignoring event with no identifiable type, fallback: {}", routingKeyOrFallback);
                return;
            }

            NotificationEvent event = NotificationEvent.builder()
                    .eventType(eventType)
                    .trackId(extractString(eventData, "trackId"))
                    .account(extractString(eventData, "account", "username"))
                    .filename(extractString(eventData, "filename"))
                    .protocol(extractString(eventData, "protocol"))
                    .service(extractString(eventData, "service", "serverInstance"))
                    .severity(extractString(eventData, "severity"))
                    .payload(eventData)
                    .timestamp(Instant.now())
                    .build();

            log.info("Received event: type={} trackId={} routingKey={}",
                    eventType, event.getTrackId(), routingKeyOrFallback);

            dispatcher.processEvent(event);

        } catch (Exception e) {
            log.warn("Failed to process notification event: {}", e.getMessage());
        }
    }

    /**
     * Extract event type from the message payload or routing key.
     */
    private String extractEventType(Map<String, Object> eventData, String routingKey) {
        // First try explicit eventType field
        String eventType = extractString(eventData, "eventType", "event_type", "type");
        if (eventType != null && !eventType.isBlank()) {
            return eventType;
        }
        // Fall back to routing key
        return routingKey;
    }

    /**
     * Extract a string value trying multiple possible field names.
     */
    private String extractString(Map<String, Object> data, String... fieldNames) {
        for (String field : fieldNames) {
            Object value = data.get(field);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
