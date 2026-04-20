package com.filetransfer.https.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.https.server.HttpsListenerRegistry;
import com.filetransfer.shared.dto.ServerInstanceChangeEvent;
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

import java.util.Map;

/**
 * Reacts to {@link ServerInstanceChangeEvent} on the platform event bus and
 * drives the {@link HttpsListenerRegistry} to bind / rebind / unbind HTTPS
 * listeners without a service restart.
 *
 * <p>Mirrors {@code sftp-service/.../ServerInstanceEventConsumer} and
 * {@code ftp-service/.../ServerInstanceEventConsumer}. Filters non-HTTPS
 * events so we don't react to SFTP / FTP / AS2 changes.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ServerInstanceEventConsumer {

    private static final String QUEUE    = "https-server-instance-events";
    private static final String EXCHANGE = "file-transfer.events";
    private static final String PATTERN  = "server.instance.*";

    private final HttpsListenerRegistry registry;
    private final ServerInstanceRepository repository;
    private final ObjectMapper objectMapper;

    @Bean
    public Queue httpsServerInstanceQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .build();
    }

    @Bean
    public TopicExchange httpsServerInstanceExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding httpsServerInstanceBinding(Queue httpsServerInstanceQueue,
                                               TopicExchange httpsServerInstanceExchange) {
        return BindingBuilder.bind(httpsServerInstanceQueue)
                .to(httpsServerInstanceExchange).with(PATTERN);
    }

    @RabbitListener(queues = QUEUE)
    public void onChange(Map<String, Object> payload) {
        try {
            ServerInstanceChangeEvent event = objectMapper.convertValue(
                    payload, ServerInstanceChangeEvent.class);
            if (event.protocol() != Protocol.HTTPS) return;

            switch (event.changeType()) {
                case CREATED, ACTIVATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) {
                        log.warn("HTTPS ServerInstance {} not found in DB for {}",
                                event.id(), event.changeType());
                        return;
                    }
                    if (!si.isActive()) return;
                    registry.bind(si);
                }
                case UPDATED -> {
                    ServerInstance si = repository.findById(event.id()).orElse(null);
                    if (si == null) return;
                    if (!si.isActive()) { registry.unbind(event.id()); return; }
                    registry.rebind(si);
                }
                case DEACTIVATED, DELETED -> registry.unbind(event.id());
            }
        } catch (Exception e) {
            log.error("HTTPS ServerInstance change event handling failed: {}", e.getMessage(), e);
            throw e; // let DLQ handle it
        }
    }
}
