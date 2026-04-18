package com.filetransfer.shared.ratelimit;

import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
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
 *
 * <p>The SPIFFE workload client is optional — when present, the filter
 * validates Bearer tokens that carry a {@code spiffe://} subject and exempts
 * them from rate limiting (R93 fix for internal S2S traffic being limited
 * the same as external clients). When absent, the filter falls back to the
 * pre-R93 behavior (only the SecurityContext {@code ROLE_INTERNAL} bypass).
 */
@Configuration
@ConditionalOnProperty(name = "platform.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    @Bean
    public ApiRateLimitFilter apiRateLimitFilter(
            RateLimitProperties properties,
            @Autowired(required = false) @Nullable StringRedisTemplate redis,
            @Autowired(required = false) @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        return new ApiRateLimitFilter(properties, redis, spiffeWorkloadClient);
    }
}
