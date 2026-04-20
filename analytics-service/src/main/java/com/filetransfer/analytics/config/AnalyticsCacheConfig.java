package com.filetransfer.analytics.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * R134x Sprint 3 — swapped Redis cache backend for Caffeine (per-pod).
 *
 * <p>Previous Redis-backed config gave us a shared cache across replicas,
 * but the values are per-request dashboards where 15-60s per-pod divergence
 * is invisible to end users (no one compares dashboards between two browser
 * tabs within a single second). Caffeine removes one more external dep.
 *
 * <p>Per-cache TTLs (same numbers as the pre-R134x Redis config):
 * <pre>
 *   dashboard     15 s — transfer KPI tiles, alerts
 *   observatory   30 s — service graph, 30-day heatmap, domain groups
 *   step-latency  60 s — step-type × hour latency grid (keyed by hours param)
 *   dedup-stats    5 m — 7-table JOIN aggregation; changes slowly
 * </pre>
 *
 * <p>Spring Boot picks the first {@link CacheManager} bean on the
 * classpath. We mark this {@code @Primary} so the
 * auto-configured {@code RedisCacheManager} (still pulled in via the
 * Redis starter) doesn't win — once Sprint 7 removes the Redis starter,
 * the @Primary is redundant but harmless.
 */
@Configuration
public class AnalyticsCacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(
                "dashboard", "observatory", "step-latency", "dedup-stats");
        // Default: 30s — matches the old Redis cacheDefaults().entryTtl.
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000));
        // Per-cache specs override the default. Caffeine's CacheManager
        // supports registering per-cache builders via registerCustomCache.
        mgr.registerCustomCache("dashboard",
                Caffeine.newBuilder().expireAfterWrite(15, TimeUnit.SECONDS).maximumSize(1_000).build());
        mgr.registerCustomCache("observatory",
                Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(1_000).build());
        mgr.registerCustomCache("step-latency",
                Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(5_000).build());
        mgr.registerCustomCache("dedup-stats",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(500).build());
        return mgr;
    }
}
