package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.server.SftpListenerRegistry;
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
            if (!"SSH_HOST_KEY".equals(event.keyType())) return;
            log.info("SSH host key rotated ({} → {}); refreshing dynamic SFTP listeners",
                    event.oldAlias(), event.newAlias());
            List<ServerInstance> listeners = repository.findByProtocolAndActiveTrue(Protocol.SFTP);
            int refreshed = 0;
            for (ServerInstance si : listeners) {
                if (registry.snapshot().containsKey(si.getId())) {
                    registry.rebind(si);
                    refreshed++;
                }
            }
            log.info("Rotation refresh complete — {} listeners rebound", refreshed);
        } catch (Exception e) {
            log.error("Failed to handle keystore rotation: {}", e.getMessage(), e);
            throw e;
        }
    }
}
