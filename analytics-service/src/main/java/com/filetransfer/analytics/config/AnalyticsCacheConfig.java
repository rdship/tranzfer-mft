package com.filetransfer.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

/**
 * Redis-backed cache configuration for analytics-service.
 *
 * <p>Replaces the previous per-JVM Caffeine cache. Redis is:
 * <ul>
 *   <li><b>Shared</b>   — all analytics-service replicas read the same warmed cache</li>
 *   <li><b>Persistent</b> — survives pod restarts (AOF + RDB on the Redis volume)</li>
 *   <li><b>Evicting</b>  — TTL-based eviction prevents stale data</li>
 * </ul>
 *
 * <p>Per-cache TTLs:
 * <pre>
 *   dashboard     15 s — transfer KPI tiles, alerts
 *   observatory   30 s — service graph, 30-day heatmap, domain groups
 *   step-latency  60 s — step-type × hour latency grid (keyed by hours param)
 *   dedup-stats    5 m — 7-table JOIN aggregation; changes slowly
 * </pre>
 *
 * <p>Values serialized as JSON (GenericJackson2JsonRedisSerializer) — safe across
 * different JVM instances and class versions.
 */
@Configuration
public class AnalyticsCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {

        ObjectMapper redisMapper = new ObjectMapper();
        redisMapper.registerModule(new JavaTimeModule());
        redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        redisMapper.activateDefaultTyping(
                redisMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(redisMapper)))
                .disableCachingNullValues();

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(30)))
                .withInitialCacheConfigurations(Map.of(
                        "dashboard",   base.entryTtl(Duration.ofSeconds(15)),
                        "observatory", base.entryTtl(Duration.ofSeconds(30)),
                        "step-latency",base.entryTtl(Duration.ofSeconds(60)),
                        "dedup-stats", base.entryTtl(Duration.ofMinutes(5))
                ))
                .build();
    }
}
