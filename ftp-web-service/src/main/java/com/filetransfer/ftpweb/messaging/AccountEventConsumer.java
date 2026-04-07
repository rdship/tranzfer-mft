package com.filetransfer.ftpweb.messaging;

import com.filetransfer.shared.repository.TransferAccountRepository;
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
        // FTP-Web uses DB-backed credential lookup, so no local cache to bust.
        // If a credential cache is added later, invalidate it here.
        log.debug("FTP-Web received account event: type={}", event.get("eventType"));
    }
}
