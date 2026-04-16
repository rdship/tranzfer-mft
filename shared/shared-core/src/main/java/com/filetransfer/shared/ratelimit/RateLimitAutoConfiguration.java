package com.filetransfer.shared.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

/**
 * Auto-configures the {@link ApiRateLimitFilter} bean.
 *
 * <p>When Redis is available, rate limiting is distributed across all replicas.
 * When Redis is unavailable, falls back to in-memory (single-instance only).
 */
@Configuration
@ConditionalOnProperty(name = "platform.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    @Bean
    public ApiRateLimitFilter apiRateLimitFilter(RateLimitProperties properties,
                                                  @Autowired(required = false) @Nullable StringRedisTemplate redis) {
        return new ApiRateLimitFilter(properties, redis);
    }
}
