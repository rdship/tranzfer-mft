package com.filetransfer.shared.routing;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ queue, binding, and listener container factory for file upload events.
 * Only active in services that process file uploads (flow.rules.enabled=true).
 *
 * <p><b>Phase 2 — Concurrency unlocking:</b>
 * <ul>
 *   <li>Prefetch configurable via {@code upload.consumer.prefetch} (default=10, was hardcoded 1)</li>
 *   <li>Concurrency configurable via {@code upload.consumer.concurrency} (default=4-16, was 2-4)</li>
 *   <li>Higher prefetch allows RabbitMQ to deliver multiple messages upfront,
 *       keeping consumer threads busy instead of idle-waiting between ACKs</li>
 * </ul>
 */
@Configuration
@org.springframework.context.annotation.Lazy(false)  // Must be eager — declares RabbitMQ queues at startup
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class FileUploadQueueConfig {

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

    @Value("${upload.consumer.prefetch:10}")
    private int prefetch;

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

    /**
     * Phase 2: Configurable listener container factory for file upload consumers.
     * Prefetch controls how many messages RabbitMQ delivers before waiting for ACK.
     * Higher prefetch = higher throughput (more in-flight messages per consumer thread).
     *
     * <p>At prefetch=10 with 16 consumers: 160 messages in-flight max per pod.
     * If a consumer crashes: 10 messages redelivered (acceptable — idempotent processing).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory uploadListenerFactory(
            ConnectionFactory connectionFactory,
            @Autowired(required = false) Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        if (messageConverter != null) factory.setMessageConverter(messageConverter);
        factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false); // failures → DLQ
        return factory;
    }
}
