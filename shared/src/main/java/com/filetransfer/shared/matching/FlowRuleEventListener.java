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
import org.springframework.stereotype.Component;

/**
 * Listens for flow rule change events and hot-reloads affected rules
 * in the in-memory registry. Every service instance gets its own
 * anonymous queue (auto-delete) so all pods receive every event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class FlowRuleEventListener {

    private final FlowRuleRegistry registry;
    private final FileFlowRepository flowRepository;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,  // anonymous, auto-delete, exclusive — each instance gets all events
            exchange = @Exchange(value = "${rabbitmq.exchange:file-transfer.events}", type = "topic"),
            key = "flow.rule.updated"
    ))
    public void onFlowRuleChange(FlowRuleChangeEvent event) {
        log.info("Flow rule change received: flowId={} type={}", event.flowId(), event.changeType());

        switch (event.changeType()) {
            case CREATED, UPDATED -> flowRepository.findById(event.flowId()).ifPresentOrElse(
                    flow -> {
                        if (flow.isActive()) {
                            registry.register(flow);
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
