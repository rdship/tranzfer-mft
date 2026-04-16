package com.filetransfer.shared.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Jackson JSON as the default RabbitMQ message converter.
 *
 * <p>Without this, Spring Boot 3.4 uses SimpleMessageConverter (JDK serialization)
 * which sends messages as {@code application/x-java-serialized-object}. The
 * receiving @RabbitListener can't deserialize them — Spring 3.4 tightened the
 * default to reject Java-serialized payloads for security.
 *
 * <p>With this bean, both RabbitTemplate (sender) and @RabbitListener (receiver)
 * use JSON automatically. All message DTOs (FileUploadedEvent, FlowRuleChangeEvent,
 * etc.) are Jackson-serializable via Lombok @Data.
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class RabbitJsonConfig {

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
