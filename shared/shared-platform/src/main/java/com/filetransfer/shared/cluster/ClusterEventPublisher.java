package com.filetransfer.shared.cluster;

import com.filetransfer.shared.enums.ServiceType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Publishes cluster JOIN/LEAVE events to the RabbitMQ
 * {@link ClusterEventsAmqpConfig#EXCHANGE} fanout exchange. Every service
 * subscribed via {@link ClusterEventSubscriber} receives every event.
 *
 * <p><b>R134AI — replaces {@code RedisServiceRegistry}.</b> The previous
 * Redis pub/sub transport is retired; RabbitMQ is already in the stack for
 * {@code file.upload.events} + {@code notification.events}, so cluster
 * events ride the same broker. The JSON payload shape is byte-identical
 * to the pre-R134AI format so {@link ClusterEventSubscriber#handleEvent}
 * continues to parse unchanged.
 *
 * <p>AOT-safe: activation is gated on {@code @ConditionalOnClass(RabbitTemplate)}
 * which the AOT processor evaluates at build time against the classpath
 * (stable, deterministic). {@link RabbitTemplate} is injected via
 * {@link ObjectProvider} so wiring survives if RabbitMQ autoconfig is
 * absent in a lightweight deployment; the publish methods no-op in that case.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class ClusterEventPublisher {

    private final ObjectProvider<RabbitTemplate> rabbitProvider;
    private final ClusterContext clusterContext;

    @Value("${cluster.service-type}")       private ServiceType serviceType;
    @Value("${cluster.host:localhost}")     private String host;
    @Value("${server.port:8080}")           private int port;
    @Value("${cluster.id:default-cluster}") private String clusterId;

    @PostConstruct
    void register() {
        RabbitTemplate rabbit = rabbitProvider.getIfAvailable();
        if (rabbit == null) {
            log.info("[R134AI][ClusterEventPublisher] RabbitTemplate not available — JOINED publish skipped");
            return;
        }
        publish(rabbit, "JOINED");
        log.info("[R134AI][ClusterEventPublisher] JOINED event published on exchange={} serviceType={}",
                ClusterEventsAmqpConfig.EXCHANGE, serviceType);
    }

    @PreDestroy
    void deregister() {
        RabbitTemplate rabbit = rabbitProvider.getIfAvailable();
        if (rabbit == null) return;
        publish(rabbit, "DEPARTED");
        log.info("[R134AI][ClusterEventPublisher] DEPARTED event published on exchange={}",
                ClusterEventsAmqpConfig.EXCHANGE);
    }

    private void publish(RabbitTemplate rabbit, String eventType) {
        try {
            // Fanout exchange ignores the routing key — pass "" so the
            // message is broadcast to all bound queues.
            MessagePostProcessor ephemeral = message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
                return message;
            };
            rabbit.convertAndSend(
                    ClusterEventsAmqpConfig.EXCHANGE, /*routingKey*/ "",
                    buildEvent(eventType), ephemeral);
        } catch (Exception e) {
            // A broker outage should not prevent a service from coming up;
            // when RabbitMQ returns the next heartbeat cycle will naturally
            // re-expose the JOINED signal via a fresh PostConstruct on restart.
            log.debug("[ClusterEventPublisher] Could not publish {} event: {}", eventType, e.getMessage());
        }
    }

    private String buildEvent(String type) {
        return "{\"type\":\"" + type + "\""
             + ",\"serviceType\":\"" + serviceType.name() + "\""
             + ",\"instanceId\":\"" + clusterContext.getServiceInstanceId() + "\""
             + ",\"url\":\"http://" + host + ":" + port + "\""
             + ",\"clusterId\":\"" + clusterId + "\""
             + ",\"timestamp\":\"" + Instant.now() + "\""
             + "}";
    }
}
