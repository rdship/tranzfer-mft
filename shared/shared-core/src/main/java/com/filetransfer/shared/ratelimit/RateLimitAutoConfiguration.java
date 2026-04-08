package com.filetransfer.shared.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures the {@link ApiRateLimitFilter} bean when
 * {@code platform.rate-limit.enabled=true} (the default).
 *
 * Services that need custom SecurityFilterChain registration can inject
 * this bean and place it at the desired position in their filter chain.
 * Services that rely on the shared PlatformSecurityConfig get it automatically.
 */
@Configuration
@ConditionalOnProperty(name = "platform.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    @Bean
    public ApiRateLimitFilter apiRateLimitFilter(RateLimitProperties properties) {
        return new ApiRateLimitFilter(properties);
    }
}
