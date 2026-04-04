package com.filetransfer.sftp.messaging;

import com.filetransfer.sftp.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.queue.sftp-events}")
    private String queueName;

    @Bean
    public Queue sftpEventsQueue() {
        return new Queue(queueName, true);
    }

    @Bean
    public TopicExchange sftpExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Binding sftpBinding(Queue sftpEventsQueue, TopicExchange sftpExchange) {
        return BindingBuilder.bind(sftpEventsQueue).to(sftpExchange).with("account.*");
    }

    @RabbitListener(queues = "${rabbitmq.queue.sftp-events}")
    public void handleAccountEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");

        if (username == null) return;

        log.info("Received event type={} username={}", eventType, username);

        if ("account.updated".equals(eventType) || "account.created".equals(eventType)) {
            credentialService.evictFromCache(username);
        }
    }
}
