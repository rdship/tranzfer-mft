package com.filetransfer.onboarding.config;

import org.springframework.cache.annotation.EnableCaching;
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
 * Redis-backed cache for onboarding-api.
 *
 * <p><b>live-stats</b> (5 s TTL) — the four flow-execution status counts polled every 10 s
 * by the Dashboard live-activity strip. Redis makes this:
 * <ul>
 *   <li>Shared across all onboarding-api replicas (one warm cache, not N cold per-JVM maps)</li>
 *   <li>Persistent across pod restarts (Redis AOF volume)</li>
 *   <li>Cheap — cache miss triggers one GROUP BY query; hits are sub-millisecond</li>
 * </ul>
 *
 * <p>Graceful degradation: if Redis is unreachable the cache is bypassed and the
 * DB query runs normally — the service stays up.
 */
@Configuration
@EnableCaching
public class OnboardingCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(cf)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(30)))
                .withInitialCacheConfigurations(Map.of(
                        "live-stats", base.entryTtl(Duration.ofSeconds(5)),
                        // Phase 5.2: Activity Monitor stats cached for 10s
                        "activity-stats", base.entryTtl(Duration.ofSeconds(10))
                ))
                .build();
    }
}
