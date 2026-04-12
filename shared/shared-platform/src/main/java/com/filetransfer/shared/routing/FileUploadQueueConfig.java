package com.filetransfer.shared.routing;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ queue and binding for file upload events.
 * Only active in services that process file uploads (flow.rules.enabled=true).
 *
 * <p>Backpressure: prefetch=1 ensures the consumer processes one file at a time.
 * RabbitMQ holds undelivered messages until the consumer ACKs, preventing
 * memory exhaustion during burst uploads.
 */
@Configuration
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class FileUploadQueueConfig {

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

    @Bean
    public Queue fileUploadQueue() {
        return QueueBuilder.durable("file.upload.events")
                .withArgument("x-dead-letter-exchange", exchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", "file.upload.dead")
                .build();
    }

    @Bean
    public Binding fileUploadBinding(Queue fileUploadQueue) {
        return BindingBuilder.bind(fileUploadQueue)
                .to(new TopicExchange(exchange))
                .with("file.uploaded");
    }
}
