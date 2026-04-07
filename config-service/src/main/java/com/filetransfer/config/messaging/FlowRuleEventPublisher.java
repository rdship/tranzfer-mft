package com.filetransfer.config.messaging;

import com.filetransfer.shared.dto.FlowRuleChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes flow rule change events to RabbitMQ so all service instances
 * hot-reload their in-memory compiled rule registries.
 */
@Slf4j
@Component
public class FlowRuleEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public FlowRuleEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${rabbitmq.exchange:file-transfer.events}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    public void publishCreated(UUID flowId) {
        publish(new FlowRuleChangeEvent(flowId, FlowRuleChangeEvent.ChangeType.CREATED));
    }

    public void publishUpdated(UUID flowId) {
        publish(new FlowRuleChangeEvent(flowId, FlowRuleChangeEvent.ChangeType.UPDATED));
    }

    public void publishDeleted(UUID flowId) {
        publish(new FlowRuleChangeEvent(flowId, FlowRuleChangeEvent.ChangeType.DELETED));
    }

    private void publish(FlowRuleChangeEvent event) {
        try {
            rabbitTemplate.convertAndSend(exchange, "flow.rule.updated", event);
            log.info("Published flow rule change: flowId={} type={}", event.flowId(), event.changeType());
        } catch (Exception e) {
            log.error("Failed to publish flow rule change event: {}", e.getMessage());
        }
    }
}
