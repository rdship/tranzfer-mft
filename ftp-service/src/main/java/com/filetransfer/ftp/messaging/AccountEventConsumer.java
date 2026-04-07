package com.filetransfer.ftp.messaging;

import com.filetransfer.ftp.service.CredentialService;
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

import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final CredentialService credentialService;

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

    @RabbitListener(queues = "${rabbitmq.queue.ftp-events}")
    public void handleAccountEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String username = (String) event.get("username");

        if (username == null) return;

        log.info("FTP received event type={} username={}", eventType, username);

        if ("account.updated".equals(eventType) || "account.created".equals(eventType)) {
            credentialService.evictFromCache(username);
        }
    }
}
