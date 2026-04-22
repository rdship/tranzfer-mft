package com.filetransfer.onboarding.messaging;

import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.dto.AccountUpdatedEvent;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Publishes account lifecycle events.
 *
 * <p><b>R134X — Sprint 7 Phase B:</b> OUTBOX-ONLY. The durable PG
 * {@code event_outbox} table is the sole inter-service transport for
 * {@code account.*} now that {@link com.filetransfer.shared.cache.PartnerCacheEvictionListener}
 * (shared, auto-activated in any service with PartnerCache) and the per-service
 * {@code AccountEventConsumer} classes (sftp / ftp / ftp-web) all co-register
 * on the {@code "account."} prefix via R134V's multi-handler cap.
 *
 * <p>The RabbitMQ publish branch was deleted in R134X; legacy broker queues
 * for {@code account.*} no longer receive traffic from this publisher.
 *
 * <p>Transport order:
 * <ol>
 *   <li>PG outbox (durable, tx-bound, LISTEN/NOTIFY wake, fans out to
 *       every registered {@code "account."} handler in every replica).</li>
 *   <li>EventFabricBridge (feature-flagged, additive, orthogonal).</li>
 * </ol>
 *
 * <p>Caller contract unchanged: {@link UnifiedOutboxWriter#write} is
 * {@code @Transactional(propagation=MANDATORY)}, so the caller
 * ({@code AccountService.createAccount / .updateAccount /
 * UnifiedOnboardService}) must be {@code @Transactional}. All current
 * callers are.
 */
@Slf4j
@Component
public class AccountEventPublisher {

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private UnifiedOutboxWriter outboxWriter;

    public void publishAccountCreated(AccountCreatedEvent event) {
        log.info("[R134X][AccountEventPublisher] publish account.created username={} accountId={}",
                event.getUsername(), event.getAccountId());

        // R134X Sprint 7 Phase B — OUTBOX-ONLY. The rabbitTemplate.convertAndSend
        // branch was removed in this commit. @Transactional(MANDATORY) throws if
        // no caller tx; every caller is @Transactional.
        if (outboxWriter != null) {
            String aggregateId = event.getAccountId() != null ? event.getAccountId().toString() : "null";
            outboxWriter.write("account", aggregateId, "CREATED", "account.created", event);
        } else {
            log.error("[R134X][AccountEventPublisher] UnifiedOutboxWriter missing — account.created "
                    + "NOT published. username={} accountId={}", event.getUsername(), event.getAccountId());
        }

        // Dual-publish to Fabric (additive, feature-flagged, orthogonal).
        if (eventFabricBridge != null) {
            eventFabricBridge.publishAccountEvent(event.getUsername(), event);
        }
    }

    public void publishAccountUpdated(AccountUpdatedEvent event) {
        log.info("[R134X][AccountEventPublisher] publish account.updated username={} accountId={}",
                event.getUsername(), event.getAccountId());

        if (outboxWriter != null) {
            String aggregateId = event.getAccountId() != null ? event.getAccountId().toString() : "null";
            outboxWriter.write("account", aggregateId, "UPDATED", "account.updated", event);
        } else {
            log.error("[R134X][AccountEventPublisher] UnifiedOutboxWriter missing — account.updated "
                    + "NOT published. username={} accountId={}", event.getUsername(), event.getAccountId());
        }

        if (eventFabricBridge != null) {
            eventFabricBridge.publishAccountEvent(event.getUsername(), event);
        }
    }
}
