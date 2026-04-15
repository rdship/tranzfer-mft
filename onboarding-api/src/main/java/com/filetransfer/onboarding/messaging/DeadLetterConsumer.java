package com.filetransfer.onboarding.messaging;

import com.filetransfer.shared.entity.transfer.DeadLetterMessage;
import com.filetransfer.shared.repository.transfer.DeadLetterMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Listens on all DLQ queues and persists dead-lettered messages to the database
 * for later inspection, retry, or discard via the DLQ Management API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final DeadLetterMessageRepository repository;

    @RabbitListener(queues = {
        DlqRabbitMQConfig.SFTP_DLQ,
        DlqRabbitMQConfig.FTP_DLQ,
        DlqRabbitMQConfig.FTPWEB_DLQ
    })
    public void handleDeadLetter(Message message) {
        try {
            Map<String, Object> headers = message.getMessageProperties().getHeaders();

            // x-death header contains original queue/exchange info
            String originalQueue = extractOriginalQueue(headers);
            String originalExchange = extractOriginalExchange(headers);
            String routingKey = extractRoutingKey(headers);
            String errorMessage = extractErrorMessage(headers);
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);

            DeadLetterMessage dlm = DeadLetterMessage.builder()
                    .originalQueue(originalQueue)
                    .originalExchange(originalExchange)
                    .routingKey(routingKey)
                    .payload(payload)
                    .errorMessage(errorMessage)
                    .maxRetries(3)
                    .build();

            repository.save(dlm);
            log.warn("Dead-lettered message persisted: queue={} exchange={} routingKey={}",
                    originalQueue, originalExchange, routingKey);
        } catch (Exception e) {
            log.error("Failed to persist dead-lettered message: {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractOriginalQueue(Map<String, Object> headers) {
        try {
            var xDeath = (java.util.List<Map<String, Object>>) headers.get("x-death");
            if (xDeath != null && !xDeath.isEmpty()) {
                return (String) xDeath.get(0).get("queue");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private String extractOriginalExchange(Map<String, Object> headers) {
        try {
            var xDeath = (java.util.List<Map<String, Object>>) headers.get("x-death");
            if (xDeath != null && !xDeath.isEmpty()) {
                return (String) xDeath.get(0).get("exchange");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private String extractRoutingKey(Map<String, Object> headers) {
        try {
            var xDeath = (java.util.List<Map<String, Object>>) headers.get("x-death");
            if (xDeath != null && !xDeath.isEmpty()) {
                var keys = (java.util.List<String>) xDeath.get(0).get("routing-keys");
                if (keys != null && !keys.isEmpty()) {
                    return keys.get(0);
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private String extractErrorMessage(Map<String, Object> headers) {
        Object reason = headers.get("x-exception-message");
        return reason != null ? reason.toString() : null;
    }
}
