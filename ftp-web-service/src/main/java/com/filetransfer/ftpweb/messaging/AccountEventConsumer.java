package com.filetransfer.ftpweb.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

    @RabbitListener(queues = "${rabbitmq.queue.ftpweb-events}")
    public void handleAccountEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");
        String homeDir = (String) event.get("homeDir");

        log.debug("FTP-Web received account event: type={} username={}", eventType, username);

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
