package com.filetransfer.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

    @Value("${rabbitmq.queue.notification-events:notification.events}")
    private String queueName;

    // RabbitMQ JSON converter removed — centralized in shared-platform RabbitJsonConfig

    @Bean
    public Queue notificationEventsQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", exchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", "notification.dead")
                .build();
    }

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * Bind to '#' to receive ALL events from the platform exchange.
     * The notification service is a universal event consumer — it matches
     * events against rules to decide which ones trigger notifications.
     */
    @Bean
    public Binding notificationBinding(Queue notificationEventsQueue, TopicExchange notificationExchange) {
        return BindingBuilder.bind(notificationEventsQueue).to(notificationExchange).with("#");
    }

    // RabbitTemplate removed — centralized in shared-platform RabbitJsonConfig
}
