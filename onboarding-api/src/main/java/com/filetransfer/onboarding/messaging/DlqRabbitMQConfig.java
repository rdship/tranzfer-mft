package com.filetransfer.onboarding.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Dead-letter infrastructure for the TranzFer RabbitMQ footprint.
 *
 * <p><b>R134Y — Sprint 8:</b> the 5 Sprint-6 DLQ declarations
 * ({@code sftp.account.events.dlq}, {@code ftp.account.events.dlq},
 * {@code ftpweb.account.events.dlq}, {@code keystore.rotation.dlq},
 * {@code server.instance.dlq}) + their bindings + the
 * {@code DeadLetterConsumer} that drained them were deleted along with
 * their source queues in R134X / R134Y. The surviving RabbitMQ footprint
 * is now {@code file.uploaded} + the activity-stream + notification fanout
 * queues; these don't currently wire to a DLQ (dispatcher dedupe + graceful
 * degradation handle failures in-process).
 *
 * <p>The {@link #DLX_EXCHANGE} + the shared listener-container factory
 * stay in place so future event classes that want DLQ semantics can
 * re-use them without re-declaring retry / back-off / reject-and-don't-requeue
 * configuration.
 */
@Configuration
@ConditionalOnClass(ConnectionFactory.class)
public class DlqRabbitMQConfig {

    public static final String DLX_EXCHANGE = "file-transfer.events.dlx";

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    // ── Listener container with retry + DLQ routing ────────────────────

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false); // rejected -> DLX

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
