package com.filetransfer.shared.cluster;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for cluster JOIN/LEAVE events.
 *
 * <p><b>R134AI — replaces the Redis pub/sub transport</b> that R134AE had
 * retrofitted to be AOT-safe. The Redis channel {@code platform:cluster:events}
 * was a historical accident — cluster membership events were glued onto the
 * Redis we were already using for caching. With Redis retired, we move the
 * fanout onto RabbitMQ which is already in the stack for the remaining two
 * design-doc-02 queues (file.upload.events + notification.events).
 *
 * <ul>
 *   <li><b>Exchange</b> {@value #EXCHANGE} — fanout, non-durable (events are ephemeral).</li>
 *   <li><b>Queue</b> — anonymous, auto-delete, exclusive. One per service instance.
 *     Auto-deleted when the consumer disconnects so dead pods don't accumulate
 *     queues in the broker.</li>
 *   <li><b>Binding</b> — queue bound to the fanout with no routing key. Every
 *     service receives every JOIN/LEAVE event from every other service.</li>
 * </ul>
 *
 * <p>Only activates when the AMQP classes are on the classpath
 * ({@link ConditionalOnClass} is AOT-safe — evaluated at build time against
 * the classpath, not a runtime environment).
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class ClusterEventsAmqpConfig {

    /** Fanout exchange name. Shared constant so publisher + subscriber agree. */
    public static final String EXCHANGE = "platform.cluster.events";

    @Bean
    public FanoutExchange clusterEventsExchange() {
        // Non-durable: cluster events are ephemeral — a broker restart losing
        // the exchange just means the next heartbeat re-declares it. Durability
        // would waste broker resources without any recovery benefit.
        return new FanoutExchange(EXCHANGE, /*durable*/ false, /*autoDelete*/ false);
    }

    @Bean
    public Queue clusterEventsQueue() {
        // AnonymousQueue → random name, non-durable, auto-delete, exclusive.
        // Each service instance gets its own queue which vanishes cleanly on
        // disconnect — no manual cleanup required.
        return new AnonymousQueue();
    }

    @Bean
    public Binding clusterEventsBinding(FanoutExchange clusterEventsExchange,
                                        Queue clusterEventsQueue) {
        return BindingBuilder.bind(clusterEventsQueue).to(clusterEventsExchange);
    }
}
