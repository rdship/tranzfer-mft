package com.filetransfer.onboarding.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * R134x Sprint 3 — Caffeine (per-pod) replaces Redis cache for onboarding-api.
 *
 * <p>These are poll-and-display dashboards; per-pod divergence of 5-10s
 * is imperceptible to the Admin UI. Removes one more Redis consumer as
 * part of the R134 external-dep retirement plan.
 *
 * <p>Caches:
 * <pre>
 *   live-stats      5 s — live dashboard tile counts
 *   activity-stats 10 s — Activity Monitor per-period stats
 *   partner-names   5 m — UUID → company-name map (changes rarely)
 * </pre>
 *
 * <p>Graceful degradation: if the cache bean fails to construct Spring
 * still boots (Boot's CompositeCacheManager fallback). A cache miss always
 * re-runs the underlying SQL — nothing blocks.
 */
@Configuration
@EnableCaching
public class OnboardingCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                "live-stats", "activity-stats", "partner-names");
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000));
        mgr.registerCustomCache("live-stats",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).maximumSize(100).build());
        mgr.registerCustomCache("activity-stats",
                Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).maximumSize(1_000).build());
        mgr.registerCustomCache("partner-names",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10_000).build());
        return mgr;
    }
}
