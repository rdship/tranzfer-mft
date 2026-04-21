package com.filetransfer.onboarding.messaging;

import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.dto.AccountUpdatedEvent;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes account lifecycle events.
 *
 * <p><b>R134R — Sprint 6 (event 3 / 4):</b> dual-writes to the PG
 * {@code event_outbox} table ahead of the legacy {@code RabbitTemplate}
 * publish. The outbox write uses {@code @Transactional(propagation=MANDATORY)},
 * so the caller (e.g. {@code AccountService.createAccount} /
 * {@code .updateAccount} / {@code UnifiedOnboardService}) must be inside
 * a transaction — the account-row save and the outbox row commit
 * atomically. All current callers are already {@code @Transactional}.
 *
 * <p>Transport order:
 * <ol>
 *   <li>PG outbox (durable, tx-bound, LISTEN/NOTIFY wake).</li>
 *   <li>RabbitMQ (legacy, still published until Sprint 7 removes it).</li>
 *   <li>EventFabricBridge (feature-flagged, additive).</li>
 * </ol>
 *
 * <p>Consumer-side registration lives on the per-service
 * {@code AccountEventConsumer} classes in sftp-service, ftp-service, and
 * ftp-web-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private UnifiedOutboxWriter outboxWriter;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    public void publishAccountCreated(AccountCreatedEvent event) {
        log.info("Publishing account.created for username={}", event.getUsername());

        // R134R — durable PG outbox (same tx as the account-row save in the caller).
        // Throws if no tx is active (MANDATORY propagation); every caller is @Transactional.
        if (outboxWriter != null) {
            String aggregateId = event.getAccountId() != null ? event.getAccountId().toString() : "null";
            outboxWriter.write("account", aggregateId, "CREATED", "account.created", event);
        }

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

        if (outboxWriter != null) {
            String aggregateId = event.getAccountId() != null ? event.getAccountId().toString() : "null";
            outboxWriter.write("account", aggregateId, "UPDATED", "account.updated", event);
        }

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
