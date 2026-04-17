package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.server.FtpListenerRegistry;
import com.filetransfer.shared.dto.KeystoreKeyRotatedEvent;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Hot-reload affected FTPS listeners when keystore-manager rotates a TLS cert.
 * Rebinds every dynamic FTP listener owned by this node so they pick up the
 * new cert on next accept. Connected sessions are NOT dropped — new connections
 * get the new cert on their own TLS handshake.
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
            // Interested in cert rotations — accept TLS_CERT or any KM-managed
            // TLS-like key (defensive: unknown types still log).
            if (!"TLS_CERT".equals(event.keyType())) {
                log.debug("Ignoring key rotation of type '{}' — not TLS", event.keyType());
                return;
            }
            log.info("TLS cert rotated ({} → {}); refreshing dynamic FTP listeners",
                    event.oldAlias(), event.newAlias());
            List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.FTP);
            int refreshed = 0;
            for (ServerInstance si : listeners) {
                if (registry.snapshot().containsKey(si.getId())) {
                    registry.rebind(si);
                    refreshed++;
                }
            }
            log.info("Rotation refresh complete — {} FTP listeners rebound", refreshed);
        } catch (Exception e) {
            log.error("Failed to handle keystore rotation: {}", e.getMessage(), e);
            throw e;
        }
    }
}
