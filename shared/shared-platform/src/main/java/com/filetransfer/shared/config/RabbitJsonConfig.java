package com.filetransfer.shared.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Central RabbitMQ JSON serialization config — the ONLY place converters are defined.
 *
 * <p>Without this, Spring Boot 3.4 uses SimpleMessageConverter (JDK serialization)
 * which sends messages as {@code application/x-java-serialized-object}. The
 * receiving @RabbitListener can't deserialize them — Spring 3.4 tightened the
 * default to reject Java-serialized payloads for security.
 *
 * <p>{@code @Primary} ensures this converter wins when multiple beans of type
 * {@code MessageConverter} exist. The explicit {@code RabbitTemplate} bean
 * guarantees the auto-configured template uses JSON — not JDK serialization.
 *
 * <p>All message DTOs (FileUploadedEvent, FlowRuleChangeEvent, etc.) are
 * Jackson-serializable via Lombok @Data.
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class RabbitJsonConfig {

    @Bean
    @Primary
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    @Primary
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
