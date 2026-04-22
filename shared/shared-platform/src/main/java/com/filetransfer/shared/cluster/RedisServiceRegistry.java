package com.filetransfer.shared.cluster;

import com.filetransfer.shared.enums.ServiceType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Cluster-event publisher on the Redis pub/sub channel.
 *
 * <p><b>R134AE — AOT-safety retrofit.</b> The class is now unconditional
 * (previously {@code @ConditionalOnBean(RedisConnectionFactory.class)} which
 * Spring AOT evaluated at build time and permanently excluded the bean —
 * see {@code docs/AOT-SAFETY.md}). Transport selection moved to a runtime
 * {@code cluster.events.transport} flag (default {@code redis-pubsub}).
 * {@link StringRedisTemplate} is injected via {@link ObjectProvider} so the
 * bean still wires when Redis is removed from the classpath at R134AI.
 *
 * <p><b>R134AD — background:</b> slimmed from its original "Redis service
 * registry" role. R134y made PG {@code platform_pod_heartbeat} the
 * authoritative registry, which retired the SETEX presence-key / discovery
 * API. R134AD removed the SETEX writes + DEL in deregister + unused
 * discovery methods. Only the pub/sub publishes remain — and they'll move
 * to RabbitMQ fanout in R134AG, after which this class is deleted.
 *
 * <p>On startup/shutdown: publish a {@code JOINED} / {@code DEPARTED}
 * event on the {@value #EVENTS_CHANNEL} Redis pub/sub channel so
 * {@link ClusterEventSubscriber} can track instance presence in real time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisServiceRegistry {

    public static final String EVENTS_CHANNEL = "platform:cluster:events";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ClusterContext clusterContext;

    @Value("${cluster.service-type}")       private ServiceType serviceType;
    @Value("${cluster.host:localhost}")     private String host;
    @Value("${server.port:8080}")           private int port;
    @Value("${cluster.id:default-cluster}") private String clusterId;

    @Value("${cluster.events.transport:redis-pubsub}")
    private String eventsTransport;

    @PostConstruct
    void register() {
        if (!isRedisTransport()) {
            log.info("[R134AE][ClusterRegistry] cluster.events.transport={} — register() no-op (JOINED not published)",
                    eventsTransport);
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("[R134AE][ClusterRegistry] StringRedisTemplate not available — JOINED publish skipped (transport={})",
                    eventsTransport);
            return;
        }
        publish(redis, "JOINED");
        log.info("[R134AE][ClusterRegistry] JOINED event published on {} (transport={})",
                EVENTS_CHANNEL, eventsTransport);
    }

    @PreDestroy
    void deregister() {
        if (!isRedisTransport()) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        publish(redis, "DEPARTED");
        log.info("[R134AE][ClusterRegistry] DEPARTED event published on {}", EVENTS_CHANNEL);
    }

    private boolean isRedisTransport() {
        return "redis-pubsub".equalsIgnoreCase(eventsTransport);
    }

    private void publish(StringRedisTemplate redis, String eventType) {
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
