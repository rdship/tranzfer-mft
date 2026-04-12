package com.filetransfer.shared.routing;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ infrastructure for the step-level pipeline queue.
 *
 * <p>Architecture: Saga-pattern single queue with step routing.
 * One durable queue ({@code flow.step.pipeline}) holds all step messages.
 * Workers consume with manual ACK and prefetch=1 (backpressure).
 * Failed steps route to DLQ with retry metadata.
 *
 * <p>This is NOT per-step-type queues (which would need 15+ queues).
 * It's one queue where each message carries its step type and the
 * worker dispatches based on stepType field.
 *
 * <p>Activated by {@code flow.step-pipeline.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "flow.step-pipeline.enabled", havingValue = "true", matchIfMissing = false)
public class StepPipelineConfig {

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String mainExchange;

    /** Main pipeline queue — all step messages flow through here */
    @Bean
    public Queue stepPipelineQueue() {
        return QueueBuilder.durable("flow.step.pipeline")
                .withArgument("x-dead-letter-exchange", mainExchange + ".dlx")
                .withArgument("x-dead-letter-routing-key", "flow.step.dead")
                .withArgument("x-message-ttl", 600_000) // 10 min TTL per message
                .build();
    }

    /** Dead letter queue for failed steps — retryable with inspection */
    @Bean
    public Queue stepPipelineDlq() {
        return QueueBuilder.durable("flow.step.pipeline.dlq")
                .withArgument("x-message-ttl", 3_600_000) // 1 hour retention
                .build();
    }

    /** Bind pipeline queue to the main exchange with routing key */
    @Bean
    public Binding stepPipelineBinding(Queue stepPipelineQueue) {
        return BindingBuilder.bind(stepPipelineQueue)
                .to(new TopicExchange(mainExchange))
                .with("flow.step.execute");
    }

    /** Bind DLQ to the DLX exchange */
    @Bean
    public Binding stepPipelineDlqBinding(Queue stepPipelineDlq) {
        return BindingBuilder.bind(stepPipelineDlq)
                .to(new TopicExchange(mainExchange + ".dlx"))
                .with("flow.step.dead");
    }

    /**
     * Listener container factory for step pipeline workers.
     * Key settings for reliability + performance:
     * - prefetchCount=1: process one step at a time (backpressure)
     * - acknowledgeMode=AUTO: ACK after handler returns (NACK on exception)
     * - concurrency=2-8: scales with CPU cores
     * - defaultRequeueRejected=false: failed messages go to DLQ, not requeued infinitely
     */
    @Bean
    public SimpleRabbitListenerContainerFactory stepPipelineFactory(
            ConnectionFactory connectionFactory, Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(8);
        factory.setDefaultRequeueRejected(false); // failures → DLQ
        return factory;
    }
}
