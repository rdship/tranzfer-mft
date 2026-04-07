package com.filetransfer.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.notification.service.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = "${rabbitmq.queue.notification-events:notification.events}")
    public void handleEvent(Message message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventData = objectMapper.readValue(message.getBody(), Map.class);

            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            String eventType = extractEventType(eventData, routingKey);

            if (eventType == null || eventType.isBlank()) {
                log.debug("Ignoring event with no identifiable type, routing key: {}", routingKey);
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
                    eventType, event.getTrackId(), routingKey);

            dispatcher.processEvent(event);

        } catch (Exception e) {
            // Graceful degradation: log and continue, never block the producer
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
