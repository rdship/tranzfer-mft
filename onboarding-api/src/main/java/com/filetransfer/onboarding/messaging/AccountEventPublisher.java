package com.filetransfer.onboarding.messaging;

import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.dto.AccountUpdatedEvent;
import com.filetransfer.shared.fabric.EventFabricBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public void publishAccountCreated(AccountCreatedEvent event) {
        log.info("Publishing account.created for username={}", event.getUsername());
        try {
            rabbitTemplate.convertAndSend(exchange, "account.created", event);
        } catch (Exception e) {
            log.warn("Failed to publish account.created to RabbitMQ: {}", e.getMessage());
        }

        // Dual-publish to Fabric (additive, feature-flagged)
        if (eventFabricBridge != null) {
            eventFabricBridge.publishAccountEvent(event.getUsername(), event);
        }
    }

    public void publishAccountUpdated(AccountUpdatedEvent event) {
        log.info("Publishing account.updated for accountId={}", event.getAccountId());
        try {
            rabbitTemplate.convertAndSend(exchange, "account.updated", event);
        } catch (Exception e) {
            log.warn("Failed to publish account.updated to RabbitMQ: {}", e.getMessage());
        }

        // Dual-publish to Fabric (additive, feature-flagged)
        if (eventFabricBridge != null) {
            eventFabricBridge.publishAccountEvent(event.getUsername(), event);
        }
    }
}
