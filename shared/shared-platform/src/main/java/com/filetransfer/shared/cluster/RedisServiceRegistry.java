package com.filetransfer.shared.cluster;

import com.filetransfer.shared.enums.ServiceType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Cluster-event publisher on the Redis pub/sub channel.
 *
 * <p><b>R134AD:</b> slimmed from its original "Redis service registry"
 * role. R134y made PG {@code platform_pod_heartbeat} the authoritative
 * registry, which retired the SETEX presence-key / discovery API of
 * this class. R134AD removes that dead code:
 * <ul>
 *   <li>{@code getInstances} / {@code getAllInstances} — deleted; no callers.</li>
 *   <li>{@code heartbeat} (@Scheduled SETEX refresh) — deleted; no readers.</li>
 *   <li>SETEX writes inside {@link #register} + DEL inside {@link #deregister}
 *       — deleted; only the pub/sub publishes remain.</li>
 * </ul>
 *
 * <p>What remains: on service startup/shutdown, publish a
 * {@code JOINED} / {@code DEPARTED} event on the
 * {@value #EVENTS_CHANNEL} Redis pub/sub channel so
 * {@link ClusterEventSubscriber} can track instance presence in real
 * time. R134AG will migrate that channel to RabbitMQ fanout and delete
 * this class entirely.
 *
 * <p>Only activates when a {@link RedisConnectionFactory} bean is present.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisServiceRegistry {

    public static final String EVENTS_CHANNEL = "platform:cluster:events";

    private final StringRedisTemplate redis;
    private final ClusterContext      clusterContext;

    @Value("${cluster.service-type}")   private ServiceType serviceType;
    @Value("${cluster.host:localhost}") private String host;
    @Value("${server.port:8080}")       private int port;
    @Value("${cluster.id:default-cluster}") private String clusterId;

    @PostConstruct
    void register() {
        publish("JOINED");
        log.info("[R134AD][ClusterRegistry] JOINED event published on {} (presence key writes removed; PG platform_pod_heartbeat is the registry)",
                EVENTS_CHANNEL);
    }

    @PreDestroy
    void deregister() {
        publish("DEPARTED");
        log.info("[R134AD][ClusterRegistry] DEPARTED event published on {}", EVENTS_CHANNEL);
    }

    private void publish(String eventType) {
        try {
            redis.convertAndSend(EVENTS_CHANNEL, buildEvent(eventType));
        } catch (Exception e) {
            log.debug("[ClusterRegistry] Could not publish {} event: {}", eventType, e.getMessage());
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
