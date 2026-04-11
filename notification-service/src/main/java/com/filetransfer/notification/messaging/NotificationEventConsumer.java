package com.filetransfer.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.notification.service.NotificationDispatcher;
import com.filetransfer.shared.fabric.EventFabricBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * RabbitMQ consumer that receives all platform events and forwards them
 * to the notification dispatcher for rule matching and dispatch.
 *
 * Graceful degradation: if processing fails, the event is logged but
 * never blocks the originating service. Failed notifications are retried
 * by the scheduled retry mechanism.
 *
 * Dual-subscribes to Fabric (events.notification topic) when
 * EventFabricBridge is available. Duplicate delivery is safe because
 * the dispatcher dedupes by trackId + eventType.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

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
