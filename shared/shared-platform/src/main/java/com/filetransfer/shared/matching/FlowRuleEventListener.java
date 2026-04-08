package com.filetransfer.shared.matching;

import com.filetransfer.shared.dto.FlowRuleChangeEvent;
import com.filetransfer.shared.repository.FileFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Listens for flow rule change events and hot-reloads affected rules
 * in the in-memory registry. Every service instance gets its own
 * anonymous queue (auto-delete) so all pods receive every event.
 *
 * <p>Only activated in services that set {@code flow.rules.enabled=true}
 * (SFTP, FTP, FTP-Web, Gateway, AS2).</p>
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

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,  // anonymous, auto-delete, exclusive — each instance gets all events
            exchange = @Exchange(value = "${rabbitmq.exchange:file-transfer.events}", type = "topic"),
            key = "flow.rule.updated"
    ))
    public void onFlowRuleChange(FlowRuleChangeEvent event) {
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
}
