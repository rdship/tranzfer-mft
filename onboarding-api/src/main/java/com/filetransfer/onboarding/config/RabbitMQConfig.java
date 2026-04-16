package com.filetransfer.onboarding.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Bean
    public TopicExchange fileTransferExchange() {
        return ExchangeBuilder.topicExchange(exchange).durable(true).build();
    }

    // RabbitMQ JSON converter + RabbitTemplate removed — centralized in shared-platform RabbitJsonConfig
}
