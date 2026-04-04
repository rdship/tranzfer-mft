package com.filetransfer.onboarding.messaging;

import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.dto.AccountUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public void publishAccountCreated(AccountCreatedEvent event) {
        log.info("Publishing account.created for username={}", event.getUsername());
        rabbitTemplate.convertAndSend(exchange, "account.created", event);
    }

    public void publishAccountUpdated(AccountUpdatedEvent event) {
        log.info("Publishing account.updated for accountId={}", event.getAccountId());
        rabbitTemplate.convertAndSend(exchange, "account.updated", event);
    }
}
