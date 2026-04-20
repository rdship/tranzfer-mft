package com.filetransfer.shared.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the API rate limiter.
 *
 * <pre>
 * platform:
 *   rate-limit:
 *     enabled: true
 *     default-limit: 100          # requests per window per IP
 *     user-limit: 200             # requests per window per authenticated user
 *     default-window-seconds: 60  # sliding window size
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "platform.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /** Whether API rate limiting is active. */
    private boolean enabled = true;

    /** Maximum requests per window per IP address. */
    private int defaultLimit = 100;

    /** Maximum requests per window per authenticated user. */
    private int userLimit = 200;

    /** Window duration in seconds. */
    private long defaultWindowSeconds = 60;

    /**
     * Rate-limit storage backend. R134w Sprint 2 — switches the counter
     * from Redis to Postgres behind a feature flag.
     *
     * <ul>
     *   <li>{@code redis} — pre-R134w default; Redis INCR+EXPIRE. Fast, but
     *       one of the 13 external deps we're retiring.</li>
     *   <li>{@code pg} — R134w target; PgRateLimitCoordinator UPSERT+RETURNING
     *       on the {@code rate_limit_buckets} monthly-partitioned table.</li>
     *   <li>{@code memory} — ConcurrentHashMap; single-instance only; test /
     *       fallback use.</li>
     * </ul>
     *
     * <p>Value is lowercase. Any unknown value falls back to {@code redis}
     * for safety.
     */
    private String backend = "redis";
}
