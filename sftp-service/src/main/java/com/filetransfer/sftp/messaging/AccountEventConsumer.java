package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.session.ConnectionManager;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    @RabbitListener(queues = "${rabbitmq.queue.sftp-events:sftp.account.events}")
    public void handleAccountEvent(org.springframework.amqp.core.Message message) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> event = mapper.readValue(message.getBody(), Map.class);

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
        } catch (Exception e) {
            log.warn("Failed to process account event: {}", e.getMessage());
        }
    }
}
