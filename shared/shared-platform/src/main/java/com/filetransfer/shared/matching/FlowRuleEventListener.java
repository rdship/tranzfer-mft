package com.filetransfer.shared.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.dto.FlowRuleChangeEvent;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.repository.FileFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Listens for flow rule change events and hot-reloads affected rules
 * in the in-memory registry. Every service instance gets its own
 * anonymous queue (auto-delete) so all pods receive every event.
 *
 * <p>Only activated in services that set {@code flow.rules.enabled=true}
 * (SFTP, FTP, FTP-Web, Gateway, AS2).</p>
 *
 * <p>Dual-subscribes to Fabric (events.flow-rule topic) as well when
 * EventFabricBridge is available. Duplicate delivery is safe because
 * the registry operations are idempotent.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class FlowRuleEventListener {

    private final FlowRuleRegistry registry;
    private final FileFlowRepository flowRepository;
    private final FlowRuleCompiler compiler;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "flow-rule-listener");
        try {
            // Each instance needs all events — use hostname to ensure unique consumer group
            String groupId = serviceName + "-" + System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString().substring(0, 8));
            eventFabricBridge.subscribeFlowRuleEvents(groupId, event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload == null) return;
                    FlowRuleChangeEvent change = toChangeEvent(payload);
                    if (change != null) {
                        handleEvent(change);
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric flow rule event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[FlowRuleEventListener] Failed to subscribe to fabric flow-rule events: {}", e.getMessage());
        }
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,  // anonymous, auto-delete, exclusive — each instance gets all events
            exchange = @Exchange(value = "${rabbitmq.exchange:file-transfer.events}", type = "topic"),
            key = "flow.rule.updated"
    ))
    public void onFlowRuleChange(FlowRuleChangeEvent event) {
        handleEvent(event);
    }

    /**
     * Idempotent handler shared between RabbitMQ and Fabric paths.
     * Duplicate delivery is safe: register/unregister are idempotent on the registry.
     */
    private void handleEvent(FlowRuleChangeEvent event) {
        // Gate: ignore events until initial bulk load completes (prevents event-before-init wipe race)
        if (!registry.isInitialized()) {
            log.debug("Flow rule event ignored (registry not yet initialized): flowId={}", event.flowId());
            return;
        }
        log.info("Flow rule change received: flowId={} type={}", event.flowId(), event.changeType());

        switch (event.changeType()) {
            case CREATED, UPDATED -> flowRepository.findById(event.flowId()).ifPresentOrElse(
                    flow -> {
                        if (flow.isActive()) {
                            try {
                                CompiledFlowRule compiled = compiler.compile(flow);
                                registry.register(flow.getId(), flow.getName(), compiled);
                            } catch (Exception e) {
                                log.error("Failed to compile flow rule: {} (id={})", flow.getName(), flow.getId(), e);
                            }
                        } else {
                            registry.unregister(flow.getId());
                        }
                    },
                    () -> registry.unregister(event.flowId())
            );
            case DELETED -> registry.unregister(event.flowId());
        }
    }

    /**
     * Convert a map payload (from fabric JSON) to a typed FlowRuleChangeEvent.
     */
    private FlowRuleChangeEvent toChangeEvent(Map<String, Object> payload) {
        try {
            Object flowIdObj = payload.get("flowId");
            Object typeObj = payload.get("changeType");
            if (flowIdObj == null || typeObj == null) return null;
            UUID flowId = UUID.fromString(flowIdObj.toString());
            FlowRuleChangeEvent.ChangeType type =
                    FlowRuleChangeEvent.ChangeType.valueOf(typeObj.toString());
            return new FlowRuleChangeEvent(flowId, type);
        } catch (Exception e) {
            log.warn("Failed to parse flow rule change event payload: {}", e.getMessage());
            return null;
        }
    }
}
