package com.filetransfer.analytics.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine cache configuration for analytics-service.
 *
 * <p>Each cache has a TTL tuned to its data-freshness requirements and
 * a maxSize=1 (all callers share the same aggregated result) except step-latency
 * which is keyed by window size (hours param):
 *
 * <ul>
 *   <li><b>dashboard</b>     — 15 s — transfer KPI tiles, alerts, predictions</li>
 *   <li><b>observatory</b>   — 30 s — service graph, 30-day heatmap, domain groups</li>
 *   <li><b>step-latency</b>  — 60 s — step-type × hour-of-day latency grid</li>
 *   <li><b>dedup-stats</b>   —  5 m — CAS dedup savings (storage_objects JOIN
 *                                      virtual_entries — expensive 7-query aggregation)</li>
 * </ul>
 *
 * <p>Using CaffeineCache instead of SimpleCache eliminates the unbounded in-memory growth
 * of SimpleCache and provides proper TTL eviction to prevent stale data.
 */
@Configuration
public class AnalyticsCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();

        mgr.registerCustomCache("dashboard",
                Caffeine.newBuilder()
                        .expireAfterWrite(15, TimeUnit.SECONDS)
                        .maximumSize(1)
                        .recordStats()
                        .build());

        mgr.registerCustomCache("observatory",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .maximumSize(1)
                        .recordStats()
                        .build());

        mgr.registerCustomCache("step-latency",
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(10)  // keyed by hours param: 6/24/48/168
                        .recordStats()
                        .build());

        mgr.registerCustomCache("dedup-stats",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(1)
                        .recordStats()
                        .build());

        return mgr;
    }
}
