package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.server.FtpListenerRegistry;
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
 * Reacts to ServerInstance CRUD events and drives {@link FtpListenerRegistry}
 * to bind / unbind / rebind FTP listeners at runtime.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private static final String QUEUE    = "ftp-server-instance-events";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "server.instance.*";

    private final FtpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    /** R134S — outbox dual-consume (see sftp-service ServerInstanceEventConsumer Javadoc). */
    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null) {
            log.info("[FTP][server-instance] boot — @RabbitListener only; UnifiedOutboxPoller not in context");
            return;
        }
        outboxPoller.registerHandler("server.instance.", row -> {
            log.info("[FTP][server-instance][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            onChange(payload);
        });
        log.info("[FTP][server-instance] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134S)");
    }

    @Bean
    public Queue ftpServerInstanceQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange ftpServerInstanceExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding ftpServerInstanceBinding(Queue ftpServerInstanceQueue, TopicExchange ftpServerInstanceExchange) {
        return BindingBuilder.bind(ftpServerInstanceQueue).to(ftpServerInstanceExchange).with(PATTERN);
    }

    @RabbitListener(queues = QUEUE)
    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.FTP) return;
            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null || !si.isActive()) return;
                    if (registry.isPrimary(si)) {
                        log.info("Skipping bind for primary FTP listener '{}' — managed by env-var bean", si.getInstanceId());
                        return;
                    }
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    if (registry.isPrimary(si)) {
                        log.info("Skipping rebind for primary FTP listener '{}' — restart the container to apply primary config changes",
                                si.getInstanceId());
                        return;
                    }
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("Failed to handle ServerInstance change event: {}", e.getMessage(), e);
            throw e;
        }
    }
}
