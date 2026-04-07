package com.filetransfer.shared.messaging;

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
 * Dead Letter Queue infrastructure for the TranzFer platform.
 *
 * Topology:
 *   Original queue  --x (rejected after 3 retries)--> DLX --> service.account.events.dlq
 *
 * The DLX is a fanout exchange so every DLQ bound to it receives rejected messages
 * with routing based on the original queue name.
 *
 * Retry policy: 3 attempts, exponential backoff (1s, 2s, 4s).
 * After exhaustion the message is rejected (not requeued) which triggers DLX routing.
 */
@Configuration
@ConditionalOnClass(ConnectionFactory.class)
public class DlqRabbitMQConfig {

    public static final String DLX_EXCHANGE = "file-transfer.events.dlx";

    public static final String SFTP_DLQ = "sftp.account.events.dlq";
    public static final String FTP_DLQ = "ftp.account.events.dlq";
    public static final String FTPWEB_DLQ = "ftpweb.account.events.dlq";

    // ── Dead Letter Exchange ───────────────────────────────────────────

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder.topicExchange(DLX_EXCHANGE).durable(true).build();
    }

    // ── Dead Letter Queues ─────────────────────────────────────────────

    @Bean
    public Queue sftpDlq() {
        return QueueBuilder.durable(SFTP_DLQ).build();
    }

    @Bean
    public Queue ftpDlq() {
        return QueueBuilder.durable(FTP_DLQ).build();
    }

    @Bean
    public Queue ftpWebDlq() {
        return QueueBuilder.durable(FTPWEB_DLQ).build();
    }

    // ── Bindings ───────────────────────────────────────────────────────

    @Bean
    public Binding sftpDlqBinding(Queue sftpDlq, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(sftpDlq).to(deadLetterExchange).with("sftp.account.events");
    }

    @Bean
    public Binding ftpDlqBinding(Queue ftpDlq, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(ftpDlq).to(deadLetterExchange).with("ftp.account.events");
    }

    @Bean
    public Binding ftpWebDlqBinding(Queue ftpWebDlq, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(ftpWebDlq).to(deadLetterExchange).with("ftpweb.account.events");
    }

    // ── Listener container with retry + DLQ routing ────────────────────

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new Jackson2JsonMessageConverter());
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
