package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.server.FtpListenerRegistry;
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
 * Hot-reload affected FTPS listeners when keystore-manager rotates a TLS cert.
 * Rebinds every dynamic FTP listener owned by this node so they pick up the
 * new cert on next accept. Connected sessions are NOT dropped — new connections
 * get the new cert on their own TLS handshake.
 *
 * <p>R134D Sprint 6 — dual-consume: legacy @RabbitListener + OutboxEventHandler
 * (PG LISTEN/NOTIFY via UnifiedOutboxPoller). rebind() is idempotent, so a
 * row delivered on both transports rebuilds the listener twice — still
 * correct, just one redundant rebind. Sprint 7 removes the RabbitMQ path.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KeystoreRotationConsumer {

    private static final String QUEUE    = "ftp-keystore-rotation";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "keystore.key.rotated";

    private final FtpListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Nullable
    private UnifiedOutboxPoller outboxPoller;

    @PostConstruct
    void boot() {
        if (outboxPoller != null) {
            outboxPoller.registerHandler(PATTERN, row -> {
                log.info("[FTP][keystore-rotation][outbox] row id={} routingKey={} aggregateId={}",
                        row.id(), row.routingKey(), row.aggregateId());
                KeystoreKeyRotatedEvent ev = row.as(KeystoreKeyRotatedEvent.class, objectMapper);
                applyRotation(ev, "outbox");
            });
            log.info("[FTP][keystore-rotation] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134D)");
        } else {
            log.info("[FTP][keystore-rotation] boot — @RabbitListener only; UnifiedOutboxPoller not on classpath");
        }
    }

    @Bean
    public Queue ftpKeystoreRotationQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange ftpKeystoreRotationExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding ftpKeystoreRotationBinding(Queue ftpKeystoreRotationQueue, TopicExchange ftpKeystoreRotationExchange) {
        return BindingBuilder.bind(ftpKeystoreRotationQueue).to(ftpKeystoreRotationExchange).with(PATTERN);
    }

    @RabbitListener(queues = QUEUE)
    public void onRotation(Map<String, Object> payload) {
        try {
            KeystoreKeyRotatedEvent event = objectMapper.convertValue(payload, KeystoreKeyRotatedEvent.class);
            applyRotation(event, "rabbitmq");
        } catch (Exception e) {
            log.error("[FTP][keystore-rotation][rabbitmq] handler failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void applyRotation(KeystoreKeyRotatedEvent event, String source) {
        if (!"TLS_CERT".equals(event.keyType())) {
            log.debug("[FTP][keystore-rotation][{}] ignoring keyType={} — not TLS", source, event.keyType());
            return;
        }
        log.info("[FTP][keystore-rotation][{}] TLS cert rotated ({} → {}); refreshing dynamic FTP listeners",
                source, event.oldAlias(), event.newAlias());
        List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.FTP);
        int refreshed = 0;
        for (ServerInstance si : listeners) {
            if (registry.snapshot().containsKey(si.getId())) {
                registry.rebind(si);
                refreshed++;
            }
        }
        log.info("[FTP][keystore-rotation][{}] complete — {} FTP listeners rebound", source, refreshed);
    }
}
