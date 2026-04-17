package com.filetransfer.shared.messaging;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Shared dead-letter exchange + reject-without-requeue listener factory.
 *
 * <p>Any service that declares a queue with
 * {@code x-dead-letter-exchange: file-transfer.events.dlx} needs this
 * exchange to exist, otherwise rejected messages have nowhere to go and
 * the broker silently drops them (or requeues forever, depending on
 * config).</p>
 *
 * <p>onboarding-api owns the DLQs themselves
 * ({@code sftp.account.events.dlq} etc.) and its own more-specific
 * {@code DlqRabbitMQConfig} takes precedence via
 * {@link ConditionalOnMissingBean}. Every other protocol service gets
 * the minimum topology to dead-letter poison messages cleanly.</p>
 */
@Configuration
@ConditionalOnClass(ConnectionFactory.class)
public class SharedDlxConfig {

    public static final String DLX_EXCHANGE = "file-transfer.events.dlx";

    /** Declared in every service so queues with x-dead-letter-exchange resolve. */
    @Bean("sharedDlxExchange")
    @ConditionalOnMissingBean(name = {"deadLetterExchange", "sharedDlxExchange"})
    public TopicExchange sharedDlxExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    /**
     * Overrides Spring Boot's default listener container factory so that
     * rejected messages are NOT requeued — they go to the DLX instead.
     * Without this, a poison message loops forever (consumer rejects →
     * broker requeues → consumer rejects → ...), burning CPU.
     *
     * <p>Onboarding-api provides its own factory with retry semantics; this
     * bean only wires into services that don't already have one.</p>
     */
    @Bean("rabbitListenerContainerFactory")
    @ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false); // poison → DLX, not hot loop

        RetryTemplate retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        retryTemplate.setBackOffPolicy(backOff);

        factory.setAdviceChain(
                org.springframework.amqp.rabbit.config.RetryInterceptorBuilder
                        .stateless()
                        .retryOperations(retryTemplate)
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build()
        );
        return factory;
    }
}
