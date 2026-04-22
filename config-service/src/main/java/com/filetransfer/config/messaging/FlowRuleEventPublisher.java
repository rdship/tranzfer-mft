package com.filetransfer.config.messaging;

import com.filetransfer.shared.dto.FlowRuleChangeEvent;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes flow rule change events so every service hot-reloads its
 * compiled rule registry.
 *
 * <p><b>R134P — Sprint 6 (event 2 / 4):</b> dual-writes to the PG
 * {@code event_outbox} table ahead of the legacy {@code RabbitTemplate}
 * publish. The outbox write is {@code @Transactional(propagation=MANDATORY)},
 * so the caller (e.g. {@link com.filetransfer.config.controller.FileFlowController})
 * must be inside a transaction — the flow-row save and the outbox row
 * commit atomically. Outbox failure rolls back the flow save (durability
 * {@literal >} availability for configuration).
 *
 * <p>Order of transports:
 * <ol>
 *   <li>PG outbox (durable, tx-bound, LISTEN/NOTIFY wake).</li>
 *   <li>RabbitMQ (legacy, still published until Sprint 7 removes it — keeps
 *       services that haven't picked up the outbox poller yet working).</li>
 *   <li>EventFabricBridge (feature-flagged, additive).</li>
 * </ol>
 *
 * <p>Consumer registration for the outbox half is in
 * {@code com.filetransfer.shared.matching.FlowRuleEventListener}.
 */
@Slf4j
@Component
public class FlowRuleEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private UnifiedOutboxWriter outboxWriter;

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
        // R134P Sprint 6 / R134U Sprint 7 Phase A — OUTBOX-ONLY.
        // Durable PG outbox is the sole inter-service transport for this
        // event class now that FlowRuleEventListener.subscribeOutboxEvents
        // is runtime-verified across the 5 consumer services (R134P +
        // R134S-stability). The RabbitMQ publish is removed here; the
        // dormant @RabbitListener binding on FlowRuleEventListener will
        // be deleted in Sprint 7 Phase B.
        //
        // @Transactional(MANDATORY) inside outboxWriter.write means the
        // caller (FileFlowController.createFlow / updateFlow / etc.) must
        // be in a tx — all 6 mutation methods already are.
        if (outboxWriter != null) {
            String aggregateId = event.flowId() != null ? event.flowId().toString() : "null";
            outboxWriter.write(
                    "flow_rule",
                    aggregateId,
                    event.changeType().name(),
                    "flow.rule.updated",
                    event);
            log.info("Published flow rule change: flowId={} type={}", event.flowId(), event.changeType());
        }

        // Dual-publish to Fabric (additive, feature-flagged — orthogonal
        // to the RabbitMQ / outbox decision; keep as-is).
        if (eventFabricBridge != null) {
            eventFabricBridge.publishFlowRuleEvent(
                    event.flowId() != null ? event.flowId().toString() : "null", event);
        }
    }
}
