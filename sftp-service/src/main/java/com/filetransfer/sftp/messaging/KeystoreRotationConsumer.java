package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.server.SftpListenerRegistry;
import com.filetransfer.shared.dto.KeystoreKeyRotatedEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PostConstruct;
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
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Hot-reload affected SFTP listeners when keystore-manager rotates an
 * SSH_HOST_KEY. Coarse refresh today: rebinds every dynamic listener owned by
 * this node so they pick up the new key from the provider chain. A
 * finer-grained per-listener alias mapping can come in a later iteration.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KeystoreRotationConsumer {

    private static final String QUEUE    = "sftp-keystore-rotation";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "keystore.key.rotated";

    private final SftpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * R134D Sprint 6 — when the unified outbox poller is on the classpath,
     * we register an OutboxEventHandler for `keystore.key.rotated` rows.
     * The @RabbitListener path below stays active (dual-consume) until
     * Sprint 7 removes the legacy RabbitMQ path. rebind() is idempotent,
     * so the worst case of a row reaching both paths is one redundant
     * rebind — not a correctness problem.
     */
    @Autowired(required = false)
    @Nullable
    private UnifiedOutboxPoller outboxPoller;

    @PostConstruct
    void boot() {
        if (outboxPoller != null) {
            outboxPoller.registerHandler(PATTERN, row -> {
                log.info("[SFTP][keystore-rotation][outbox] row id={} routingKey={} aggregateId={}",
                        row.id(), row.routingKey(), row.aggregateId());
                KeystoreKeyRotatedEvent ev = row.as(KeystoreKeyRotatedEvent.class, objectMapper);
                applyRotation(ev, "outbox");
            });
            log.info("[SFTP][keystore-rotation] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134D)");
        } else {
            log.info("[SFTP][keystore-rotation] boot — @RabbitListener only; UnifiedOutboxPoller not on classpath");
        }
    }

    @Bean
    public Queue sftpKeystoreRotationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange sftpKeystoreRotationExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding sftpKeystoreRotationBinding(Queue sftpKeystoreRotationQueue, TopicExchange sftpKeystoreRotationExchange) {
        return BindingBuilder.bind(sftpKeystoreRotationQueue).to(sftpKeystoreRotationExchange).with(PATTERN);
    }

    @RabbitListener(queues = QUEUE)
    public void onRotation(Map<String, Object> payload) {
        try {
            KeystoreKeyRotatedEvent event = objectMapper.convertValue(payload, KeystoreKeyRotatedEvent.class);
            applyRotation(event, "rabbitmq");
        } catch (Exception e) {
            log.error("[SFTP][keystore-rotation][rabbitmq] handler failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Shared handler invoked by both transports. Idempotent — if SSH_HOST_KEY
     * rotation fires twice (once via RabbitMQ, once via outbox) each rebind
     * lands the identical new key, so the observable effect is unchanged.
     */
    private void applyRotation(KeystoreKeyRotatedEvent event, String source) {
        if (!"SSH_HOST_KEY".equals(event.keyType())) {
            log.debug("[SFTP][keystore-rotation][{}] ignoring keyType={}", source, event.keyType());
            return;
        }
        log.info("[SFTP][keystore-rotation][{}] SSH host key rotated ({} → {}); refreshing dynamic SFTP listeners",
                source, event.oldAlias(), event.newAlias());
        List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.SFTP);
        int refreshed = 0;
        for (ServerInstance si : listeners) {
            if (registry.snapshot().containsKey(si.getId())) {
                registry.rebind(si);
                refreshed++;
            }
        }
        log.info("[SFTP][keystore-rotation][{}] complete — {} listeners rebound", source, refreshed);
    }
}
