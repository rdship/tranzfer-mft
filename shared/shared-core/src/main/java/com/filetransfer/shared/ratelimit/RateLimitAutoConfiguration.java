package com.filetransfer.shared.ratelimit;

import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * Auto-configures the {@link ApiRateLimitFilter} bean.
 *
 * <p>Backend selection comes from {@code platform.rate-limit.backend}:
 * <ul>
 *   <li>{@code pg} — {@link PgRateLimitCoordinator} UPSERT+RETURNING (default, Sprint 2)</li>
 *   <li>{@code memory} — in-process token buckets (single-instance)</li>
 * </ul>
 * The legacy {@code redis} value was retired at R134AH and now degrades to memory.
 *
 * <p>The SPIFFE workload client is optional — when present, the filter
 * validates Bearer tokens that carry a {@code spiffe://} subject and exempts
 * them from rate limiting.
 */
@Configuration
@ConditionalOnProperty(name = "platform.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    @Bean
    public ApiRateLimitFilter apiRateLimitFilter(
            RateLimitProperties properties,
            @Autowired(required = false) @Nullable PgRateLimitCoordinator pg,
            @Autowired(required = false) @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        return new ApiRateLimitFilter(properties, pg, spiffeWorkloadClient);
    }
}
