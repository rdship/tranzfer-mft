package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.server.SftpListenerRegistry;
import com.filetransfer.shared.dto.ServerInstanceChangeEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Reacts to ServerInstance CRUD events and drives the
 * {@link SftpListenerRegistry} to bind / unbind / rebind live listeners
 * without a service restart.
 *
 * <p>Events are published by onboarding-api's outbox poller to routing keys
 * {@code server.instance.created|updated|activated|deactivated|deleted}.</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private static final String QUEUE    = "sftp-server-instance-events";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "server.instance.*";

    private final SftpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * R134S Sprint 6 — register an OutboxEventHandler on routing-key prefix
     * "server.instance." so events produced by {@code UnifiedOutboxWriter}
     * in onboarding-api's ServerInstanceService are drained here directly,
     * parallel to the legacy RabbitMQ path. Shared {@link #onChange} is
     * idempotent (registry bind/unbind/rebind converge) — duplicate
     * delivery across transports is safe.
     */
    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.info("[SFTP][server-instance] boot — @RabbitListener only; UnifiedOutboxPoller not in context");
            return;
        }
        outboxPoller.registerHandler("server.instance.", row -> {
            log.info("[SFTP][server-instance][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            onChange(payload);
        });
        log.info("[SFTP][server-instance] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134S)");
    }

    @Bean
    public Queue sftpServerInstanceQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange sftpServerInstanceExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding sftpServerInstanceBinding(Queue sftpServerInstanceQueue, TopicExchange sftpServerInstanceExchange) {
        return BindingBuilder.bind(sftpServerInstanceQueue).to(sftpServerInstanceExchange).with(PATTERN);
    }

    @RabbitListener(queues = QUEUE)
    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.SFTP) return; // other protocol services handle their own

            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) { log.warn("ServerInstance {} not found in DB for {}", event.id(), event.changeType()); return; }
                    if (!si.isActive()) return;
                    if (registry.isPrimary(si)) {
                        log.info("Skipping bind for primary listener '{}' — managed by env-var bean, not registry", si.getInstanceId());
                        return;
                    }
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    // Primary is bound by the env-var-driven bean at boot — a runtime
                    // rebind via the registry would try to bind its port a second
                    // time (BindException → BIND_FAILED in DB). Admin must restart
                    // the container to pick up primary config changes.
                    if (registry.isPrimary(si)) {
                        log.info("Skipping rebind for primary listener '{}' — restart the container to apply primary config changes",
                                si.getInstanceId());
                        return;
                    }
                    // Port or key/algorithm settings may have changed — safest is rebind.
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("Failed to handle ServerInstance change event: {}", e.getMessage(), e);
            throw e; // let DLQ handle it
        }
    }
}
