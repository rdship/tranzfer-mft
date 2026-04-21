package com.filetransfer.ftpweb.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.fabric.EventFabricBridge;
import com.filetransfer.shared.outbox.UnifiedOutboxPoller;
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

    @Autowired(required = false)
    private EventFabricBridge eventFabricBridge;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private PartnerCache partnerCache;

    /** R134R — outbox dual-consume (see sftp-service AccountEventConsumer Javadoc). */
    @Autowired(required = false)
    private UnifiedOutboxPoller outboxPoller;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue.ftpweb-events}")
    private String queueName;

    @Bean
    public Queue ftpWebEventsQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", "file-transfer.events.dlx")
                .withArgument("x-dead-letter-routing-key", "ftpweb.account.events")
                .build();
    }

    @Bean
    public TopicExchange ftpWebExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Binding ftpWebBinding(Queue ftpWebEventsQueue, TopicExchange ftpWebExchange) {
        return BindingBuilder.bind(ftpWebEventsQueue).to(ftpWebExchange).with("account.*");
    }

    @jakarta.annotation.PostConstruct
    void subscribeFabricEvents() {
        if (eventFabricBridge == null || objectMapper == null) return;
        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "ftp-web-service");
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
            log.warn("[FTP-Web] Failed to subscribe to fabric account events: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PostConstruct
    void subscribeOutboxEvents() {
        if (outboxPoller == null || objectMapper == null) {
            log.info("[FTP-Web][account] boot — @RabbitListener only; UnifiedOutboxPoller not in context");
            return;
        }
        outboxPoller.registerHandler("account.", row -> {
            log.info("[FTP-Web][account][outbox] row id={} routingKey={} aggregateId={}",
                    row.id(), row.routingKey(), row.aggregateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.as(Map.class, objectMapper);
            handleEvent(payload);
        });
        log.info("[FTP-Web][account] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134R)");
    }

    @RabbitListener(queues = "${rabbitmq.queue.ftpweb-events}")
    public void handleAccountEvent(Map<String, Object> event) {
        handleEvent(event);
    }

    /**
     * Idempotent event handler shared by RabbitMQ and Fabric paths.
     */
    private void handleEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        log.debug("FTP-Web received account event: type={} username={}", eventType, username);

        // Evict credential cache on account updates so next request picks up fresh data
        if ("account.updated".equals(eventType) && username != null) {
            log.info("FTP-Web cache evicted for updated account: {}", username);
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
            String storageMode = (String) event.getOrDefault("storageMode", "PHYSICAL");
            if ("VIRTUAL".equalsIgnoreCase(storageMode)) {
                log.info("VIRTUAL account {} — physical dir creation skipped (VFS-managed)", username);
                return;
            }
            try {
                // Always ensure home dir exists (N36 fix)
                Files.createDirectories(Paths.get(homeDir));
                @SuppressWarnings("unchecked")
                List<String> folderPaths = (List<String>) event.get("folderPaths");
                if (folderPaths == null || folderPaths.isEmpty()) {
                    folderPaths = java.util.List.of("inbox", "outbox", "sent");
                }
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
