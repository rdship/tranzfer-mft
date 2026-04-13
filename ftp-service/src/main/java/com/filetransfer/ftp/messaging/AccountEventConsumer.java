package com.filetransfer.ftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ftp.service.CredentialService;
import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.fabric.EventFabricBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private PartnerCache partnerCache;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue.ftp-events}")
    private String queueName;

    @Bean
    public Queue ftpEventsQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .withArgument("x-dead-letter-routing-key", "ftp.account.events")
                .build();
    }

    @Bean
    public TopicExchange ftpExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Binding ftpBinding(Queue ftpEventsQueue, TopicExchange ftpExchange) {
        return BindingBuilder.bind(ftpEventsQueue).to(ftpExchange).with("account.*");
    }

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "ftp-service");
        try {
            eventFabricBridge.subscribeAccountEvents(
                com.filetransfer.shared.fabric.FabricGroupIds.shared(serviceName, "events.account"),
                event -> {
                try {
                    Map<String, Object> payload = event.payloadAsMap(objectMapper);
                    if (payload != null) {
                        handleEvent(payload);
                    }
                } catch (Exception e) {
                    log.error("Failed to process fabric account event: {}", e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.warn("[FTP] Failed to subscribe to fabric account events: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = "${rabbitmq.queue.ftp-events}")
    public void handleAccountEvent(Map<String, Object> event) {
        handleEvent(event);
    }

    /**
     * Idempotent event handler shared by RabbitMQ and Fabric paths.
     * Duplicate delivery is safe: cache eviction and directory creation are idempotent.
     */
    private void handleEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        if (username == null) return;

        log.info("FTP received event type={} username={}", eventType, username);

        if ("account.updated".equals(eventType) || "account.created".equals(eventType)) {
            credentialService.evictFromCache(username);
        }

        // Phase 1: evict partner cache on account events
        Object partnerIdObj = event.get("partnerId");
        if (partnerCache != null && partnerIdObj != null) {
            try {
                partnerCache.evict(java.util.UUID.fromString(partnerIdObj.toString()));
            } catch (Exception ignored) {}
        }

        // Create home directories from template (carried in event) or defaults
        if ("account.created".equals(eventType) && homeDir != null) {
            try {
                @SuppressWarnings("unchecked")
                List<String> folderPaths = (List<String>) event.get("folderPaths");
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
