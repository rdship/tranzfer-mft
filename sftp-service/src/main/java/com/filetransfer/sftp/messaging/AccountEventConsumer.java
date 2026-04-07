package com.filetransfer.sftp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.sftp.service.CredentialService;
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
            log.info("Account event received: username={} homeDir={}", username, homeDir);

            // Evict cache so next auth picks up fresh DB data
            credentialService.evictFromCache(username);

            // Create home directories from template (carried in event) or default
            if (homeDir != null) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> folderPaths = (java.util.List<String>) event.get("folderPaths");
                    if (folderPaths == null || folderPaths.isEmpty()) {
                        folderPaths = java.util.List.of("inbox", "outbox", "archive", "sent");
                    }
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
