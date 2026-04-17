package com.filetransfer.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Configures Redis cache to use Jackson JSON serialization.
 *
 * <p>Historical context: N37 fix moved from JDK serialization to Jackson.
 * Subsequent bug: the cache used a custom ObjectMapper with
 * {@code activateDefaultTyping(NON_FINAL, PROPERTY)}. This clashed with
 * {@link GenericJackson2JsonRedisSerializer}'s own type-id injection
 * strategy — cached values serialized OK but failed on readback with
 * "Unexpected token (START_OBJECT), expected VALUE_STRING" for collections
 * that contained polymorphic values (e.g. {@code List<FileFlowDto>} whose
 * steps contained {@code Map<String, String>}). The 500 surfaced on
 * {@code GET /api/flows}.
 *
 * <p>Fix: let {@code GenericJackson2JsonRedisSerializer} manage typing via
 * its own {@code @class} property scheme. Our only customization is
 * {@link JavaTimeModule} for {@code Instant} + disabling numeric timestamps.
 * Type information is added by the serializer itself — no double-registration.
 */
@Configuration
@EnableCaching
@ConditionalOnClass(RedisConnectionFactory.class)
public class RedisCacheConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // IMPORTANT: do NOT call mapper.activateDefaultTyping(...) here.
        // GenericJackson2JsonRedisSerializer below will inject the "@class"
        // type id property itself; calling activateDefaultTyping in addition
        // produces conflicting type hints on polymorphic collections and
        // breaks readback. This is the P1-3 regression from the R77-R79 run.

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();
    }
}
