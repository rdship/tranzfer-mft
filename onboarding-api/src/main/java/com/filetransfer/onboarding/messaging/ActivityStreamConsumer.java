package com.filetransfer.onboarding.messaging;

import com.filetransfer.onboarding.controller.ActivityMonitorController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes file transfer lifecycle events from RabbitMQ and broadcasts
 * to connected SSE clients via ActivityMonitorController.
 *
 * Events: file.uploaded, transfer.completed, transfer.failed, transfer.started
 * Each connected browser tab receives real-time updates without polling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class ActivityStreamConsumer {

    private final ActivityMonitorController activityMonitor;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "activity-stream", durable = "true"),
            exchange = @Exchange(value = "${rabbitmq.exchange:file-transfer.events}", type = "topic"),
            key = {"file.uploaded", "transfer.#"}
    ))
    public void onTransferEvent(Map<String, Object> event) {
        try {
            String eventType = (String) event.getOrDefault("eventType",
                    event.getOrDefault("type", "transfer-update"));
            String sseEvent = switch (eventType) {
                case "FILE_UPLOADED", "file.uploaded" -> "transfer-new";
                case "COMPLETED", "transfer.completed" -> "transfer-completed";
                case "FAILED", "transfer.failed" -> "transfer-failed";
                default -> "transfer-update";
            };
            activityMonitor.broadcastActivityEvent(sseEvent, event);
        } catch (Exception e) {
            log.debug("Activity stream broadcast skipped: {}", e.getMessage());
        }
    }
}
