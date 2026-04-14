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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed service registry — the real-time complement to the PostgreSQL-based
 * {@link ClusterRegistrationService}.
 *
 * <h3>Design</h3>
 * <pre>
 * On startup:   SETEX platform:instance:{serviceType}:{instanceId} 30 {json}
 *               PUBLISH platform:cluster:events {type:JOINED, ...}
 *
 * Every 10 s:   SETEX platform:instance:{serviceType}:{instanceId} 30 {json}   (refresh TTL)
 *
 * On shutdown:  DEL platform:instance:{serviceType}:{instanceId}
 *               PUBLISH platform:cluster:events {type:DEPARTED, ...}
 *
 * Discovery:    SCAN 0 MATCH platform:instance:{serviceType}:*
 * </pre>
 *
 * <p>TTL ensures stale entries (crashed pod, no {@link PreDestroy}) disappear within 30 s —
 * the heartbeat refreshes every 10 s, so a healthy instance is always ≤10 s stale.
 *
 * <p>Only activates when a {@link RedisConnectionFactory} bean is present on the classpath.
 * Services without Redis skip this bean silently and rely on the PostgreSQL registry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisServiceRegistry {

    public static final String INSTANCE_KEY_PREFIX = "platform:instance:";
    public static final String EVENTS_CHANNEL      = "platform:cluster:events";
    public static final int    PRESENCE_TTL_SECONDS = 30;

    private final StringRedisTemplate redis;
    private final ClusterContext      clusterContext;

    @Value("${cluster.service-type}")   private ServiceType serviceType;
    @Value("${cluster.host:localhost}") private String host;
    @Value("${server.port:8080}")       private int port;
    @Value("${cluster.id:default-cluster}") private String clusterId;

    /** Set once on startup. */
    private String instanceKey;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    void register() {
        String instanceId = clusterContext.getServiceInstanceId();
        instanceKey = INSTANCE_KEY_PREFIX + serviceType.name() + ":" + instanceId;

        try {
            redis.opsForValue().set(instanceKey, buildPayload(), Duration.ofSeconds(PRESENCE_TTL_SECONDS));
            publish("JOINED");
            log.info("[ClusterRegistry] Registered in Redis: key={} url=http://{}:{}", instanceKey, host, port);
        } catch (Exception e) {
            // Don't block boot — heartbeat will register on next tick (10s)
            log.warn("[ClusterRegistry] Redis registration deferred (will retry via heartbeat): {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 10_000, initialDelay = 10_000)
    void heartbeat() {
        if (instanceKey != null) {
            redis.opsForValue().set(instanceKey, buildPayload(), Duration.ofSeconds(PRESENCE_TTL_SECONDS));
        }
    }

    @PreDestroy
    void deregister() {
        if (instanceKey != null) {
            redis.delete(instanceKey);
            publish("DEPARTED");
            log.info("[ClusterRegistry] Deregistered from Redis: {}", instanceKey);
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Return all live instances of the given service type by scanning Redis presence keys.
     * Only keys with a live TTL are returned — stale/crashed instances auto-expire.
     */
    public List<ServiceInstance> getInstances(String serviceTypeName) {
        String pattern = INSTANCE_KEY_PREFIX + serviceTypeName + ":*";
        Set<String> keys = redis.keys(pattern);   // fine for small replica counts
        List<ServiceInstance> result = new ArrayList<>();
        if (keys == null) return result;

        for (String key : keys) {
            String payload = redis.opsForValue().get(key);
            if (payload != null) {
                result.add(parsePayload(payload, serviceTypeName, key));
            }
        }
        return result;
    }

    /** All registered instances across ALL service types. */
    public List<ServiceInstance> getAllInstances() {
        Set<String> keys = redis.keys(INSTANCE_KEY_PREFIX + "*");
        List<ServiceInstance> result = new ArrayList<>();
        if (keys == null) return result;

        for (String key : keys) {
            String payload = redis.opsForValue().get(key);
            if (payload != null) {
                // key format: platform:instance:{serviceType}:{instanceId}
                String[] parts = key.split(":");
                String svcType = parts.length >= 3 ? parts[2] : "UNKNOWN";
                result.add(parsePayload(payload, svcType, key));
            }
        }
        return result;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void publish(String eventType) {
        try {
            redis.convertAndSend(EVENTS_CHANNEL, buildEvent(eventType));
        } catch (Exception e) {
            log.debug("[ClusterRegistry] Could not publish {} event: {}", eventType, e.getMessage());
        }
    }

    private String buildPayload() {
        return "{\"instanceId\":\"" + clusterContext.getServiceInstanceId() + "\""
             + ",\"serviceType\":\"" + serviceType.name() + "\""
             + ",\"clusterId\":\"" + clusterId + "\""
             + ",\"host\":\"" + host + "\""
             + ",\"port\":" + port
             + ",\"url\":\"http://" + host + ":" + port + "\""
             + ",\"startedAt\":\"" + Instant.now() + "\""
             + "}";
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

    private ServiceInstance parsePayload(String json, String svcType, String key) {
        // Minimal JSON parse (avoids ObjectMapper dependency in this class)
        return ServiceInstance.builder()
                .serviceType(svcType)
                .instanceId(extract(json, "instanceId"))
                .host(extract(json, "host"))
                .port(extractInt(json, "port"))
                .url(extract(json, "url"))
                .clusterId(extract(json, "clusterId"))
                .lastSeen(Instant.now())
                .build();
    }

    private static String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static int extractInt(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) return 0;
        start += marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }
}
