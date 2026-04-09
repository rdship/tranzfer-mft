package com.filetransfer.dmz.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Registers this DMZ proxy instance in Redis under its configured group.
 *
 * <p>Key pattern: {@code platform:proxy-group:{groupName}:{instanceId}}
 * TTL: 30 s, refreshed every 10 s. Stale entries auto-expire if the proxy
 * crashes without a graceful shutdown.
 *
 * <p>This class is deliberately <strong>free of shared-platform and JPA</strong>.
 * It only uses {@code spring-boot-starter-data-redis} and plain Spring Boot
 * annotations — the DMZ proxy's DB-free invariant is preserved.
 *
 * <p>Only activates when a {@link RedisConnectionFactory} bean is present.
 * If Redis is not configured, the proxy still works — it just won't appear in
 * the live registry on the admin UI.
 */
@Slf4j
@Component
@ConditionalOnBean(RedisConnectionFactory.class)
public class ProxyGroupRegistrar {

    private static final String KEY_PREFIX     = "platform:proxy-group:";
    private static final int    PRESENCE_TTL_S = 30;

    private final StringRedisTemplate redis;

    @Value("${proxy.group.name:internal}")       private String groupName;
    @Value("${proxy.group.type:INTERNAL}")        private String groupType;
    @Value("${proxy.group.instance-id:}")         private String configuredId;
    @Value("${cluster.host:localhost}")           private String host;
    @Value("${server.port:8088}")                 private int    port;
    @Value("${PROXY_GROUP_NAME:${proxy.group.name:internal}}")
    private String effectiveGroupName;

    private String instanceId;
    private String presenceKey;
    private Instant startedAt;

    @Autowired
    public ProxyGroupRegistrar(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void register() {
        if (redis == null) {
            log.warn("[ProxyGroup] Redis not available — instance will not appear in live registry");
            return;
        }
        instanceId  = (configuredId != null && !configuredId.isBlank())
                      ? configuredId
                      : UUID.randomUUID().toString();
        presenceKey = KEY_PREFIX + groupName + ":" + instanceId;
        startedAt   = Instant.now();

        writePresence();
        log.info("[ProxyGroup] Registered in Redis → group='{}' type='{}' url='http://{}:{}' key={}",
                groupName, groupType, host, port, presenceKey);
    }

    @Scheduled(fixedRate = 10_000, initialDelay = 10_000)
    void heartbeat() {
        if (redis != null && presenceKey != null) writePresence();
    }

    @PreDestroy
    void deregister() {
        if (redis != null && presenceKey != null) {
            redis.delete(presenceKey);
            log.info("[ProxyGroup] Deregistered from Redis: {}", presenceKey);
        }
    }

    // ── Accessors for /api/proxy/info ─────────────────────────────────────────

    public String getGroupName()  { return groupName; }
    public String getGroupType()  { return groupType; }
    public String getInstanceId() { return instanceId != null ? instanceId : "unregistered"; }
    public Instant getStartedAt() { return startedAt; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void writePresence() {
        String payload = "{"
            + "\"instanceId\":\"" + instanceId + "\","
            + "\"groupName\":\"" + groupName + "\","
            + "\"groupType\":\"" + groupType + "\","
            + "\"host\":\"" + host + "\","
            + "\"port\":" + port + ","
            + "\"url\":\"http://" + host + ":" + port + "\","
            + "\"startedAt\":\"" + startedAt + "\","
            + "\"managementUrl\":\"http://" + host + ":" + port + "/api/proxy\""
            + "}";
        try {
            redis.opsForValue().set(presenceKey, payload, Duration.ofSeconds(PRESENCE_TTL_S));
        } catch (Exception e) {
            log.debug("[ProxyGroup] Redis write failed (proxy still operational): {}", e.getMessage());
        }
    }
}
