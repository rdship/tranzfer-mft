package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.session.ConnectionManager;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;
    private final BandwidthThrottleManager bandwidthThrottleManager;
    private final ConnectionManager connectionManager;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper injectedObjectMapper;

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

    @Value("${rabbitmq.queue.sftp-events:sftp.account.events}")
    private String queueName;

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(new ObjectMapper());
    }

    @Bean
    public Queue sftpEventsQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .withArgument("x-dead-letter-routing-key", "sftp.account.events")
                .build();
    }

    @Bean
    public TopicExchange sftpExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Binding sftpBinding(Queue sftpEventsQueue, TopicExchange sftpExchange) {
        return BindingBuilder.bind(sftpEventsQueue).to(sftpExchange).with("account.*");
    }

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || injectedObjectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "sftp-service");
        try {
            eventFabricBridge.subscribeAccountEvents(serviceName, event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(injectedObjectMapper);
                    if (payload != null) {
                        handleEvent(payload);
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric account event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[SFTP] Failed to subscribe to fabric account events: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.sftp-events:sftp.account.events}")
    public void handleAccountEvent(org.springframework.amqp.core.Message message) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> event = mapper.readValue(message.getBody(), Map.class);
            handleEvent(event);
        } catch (Exception e) {
            log.warn("Failed to process account event: {}", e.getMessage());
        }
    }

    /**
     * Idempotent event handler shared by RabbitMQ and Fabric paths.
     * Duplicate delivery (once from each bus) is safe: cache eviction,
     * QoS updates, and directory creation are all idempotent.
     */
    private void handleEvent(Map<String, Object> event) {
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        if (username == null) return;
        String eventType = (String) event.get("eventType");
        log.info("Account event received: type={} username={} homeDir={}", eventType, username, homeDir);

        // Evict cache so next auth picks up fresh DB data
        credentialService.evictFromCache(username);

        // Live QoS update: re-register bandwidth and session limits for active sessions
        if ("account.updated".equals(eventType) && bandwidthThrottleManager.hasUserLimits(username)) {
            credentialService.findAccount(username).ifPresent(account -> {
                bandwidthThrottleManager.registerUserLimits(username,
                    account.getQosUploadBytesPerSecond(),
                    account.getQosDownloadBytesPerSecond(),
                    account.getQosBurstAllowancePercent());
                connectionManager.registerQosSessionLimit(username,
                    account.getQosMaxConcurrentSessions());
                log.info("Live QoS updated for active user={}", username);
            });
        }

        // Create home directories from template (carried in event) or default
        if ("account.created".equals(eventType) && homeDir != null) {
            try {
                @SuppressWarnings("unchecked")
                java.util.List<String> folderPaths = (java.util.List<String>) event.get("folderPaths");
                if (folderPaths == null || folderPaths.isEmpty()) return;
                for (String folder : folderPaths) {
                    Files.createDirectories(Paths.get(homeDir, folder));
                }
                log.info("Created {} directories for {}: {}", folderPaths.size(), username, homeDir);
            } catch (Exception e) {
                log.warn("Could not create directories for {}: {}", username, e.getMessage());
            }
        }
    }
}
