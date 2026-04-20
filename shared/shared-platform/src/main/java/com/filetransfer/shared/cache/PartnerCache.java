package com.filetransfer.shared.cache;

import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.repository.core.PartnerRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Partner cache. Caffeine (W-TinyLFU) in-process, per-JVM, with DB fallback.
 *
 * <p>R134x Sprint 3 — Redis L2 removed. The design (doc 01, PartnerCache
 * replacement) called for Caffeine + a Postgres materialized view as the
 * shared tier; in practice the {@code partners} table is already a hot
 * in-memory tuple in PG, so a dedicated MV would buy nothing over a direct
 * {@code findById} on L1 miss. If profiling ever shows the PG query cost
 * mattering, {@code CREATE MATERIALIZED VIEW partner_cache AS SELECT …} +
 * a 30s refresh job is a single commit away.
 *
 * <p>Why Caffeine (W-TinyLFU), not ConcurrentHashMap:
 * <ul>
 *   <li>Bounded — won't leak memory as partners come and go</li>
 *   <li>W-TinyLFU admission policy keeps frequently-accessed partners
 *       pinned even under write bursts; plain LRU would evict hot-but-
 *       briefly-idle entries on the long tail of rarely-active ones.</li>
 * </ul>
 *
 * <p>Consistency: per-pod divergence after a partner update is bounded by
 * the eviction/refresh schedule. For harder consistency, {@link #evict}
 * is called from AccountEventConsumer on update events. Bulk refresh runs
 * every 60s as a belt-and-suspenders.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pipeline.cache.partner.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerCache {

    /**
     * Cap at 50k partners — sized generously; a realistic mid-size customer
     * runs 1–5k partners. Caffeine uses ~72 bytes/entry overhead → 50k ≈
     * 3.5 MB, trivial.
     */
    private static final long MAX_ENTRIES = 50_000L;

    /**
     * TTL for non-refresh entries. The bulk refresher repopulates every 60s,
     * so this acts as a safety net (e.g. on a partner freshly created
     * between refresh ticks — we still evict eventually).
     */
    private static final Duration ENTRY_TTL = Duration.ofMinutes(10);

    /** L1: Caffeine (W-TinyLFU admission, bounded, per-JVM). */
    private final Cache<UUID, PartnerSnapshot> l1 = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(ENTRY_TTL)
            .recordStats()
            .build();

    private final java.util.concurrent.atomic.AtomicLong l1Hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong dbMisses = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private PartnerRepository partnerRepository;

    /**
     * R134C — boot log so tester/ops can confirm the Caffeine-only path is
     * active (instead of the retired Caffeine-L1 + Redis-L2 pattern).
     */
    @jakarta.annotation.PostConstruct
    void boot() {
        log.info("[cache][PartnerCache] Caffeine W-TinyLFU active (maxEntries={}, ttl={}, recordStats=true; "
                + "R134x retired Redis L2)", MAX_ENTRIES, ENTRY_TTL);
    }

    /** Immutable snapshot of the fields needed in the hot path. */
    public record PartnerSnapshot(UUID id, String slug, String companyName) {}

    /**
     * Zero-cost lookup: L1 → DB.
     * L1 hit: sub-100ns. DB miss: ~1–5ms (first access; JPA cache warmed).
     */
    public PartnerSnapshot get(UUID partnerId) {
        if (partnerId == null) return null;

        PartnerSnapshot cached = l1.getIfPresent(partnerId);
        if (cached != null) {
            l1Hits.incrementAndGet();
            return cached;
        }

        Partner partner = partnerRepository.findById(partnerId).orElse(null);
        if (partner == null) return null;

        cached = new PartnerSnapshot(partner.getId(), partner.getSlug(), partner.getCompanyName());
        l1.put(partnerId, cached);
        dbMisses.incrementAndGet();
        return cached;
    }

    /**
     * Bulk refresh — keeps the cache warm. Per-pod, which is intentional —
     * each pod pays for its own warm-up but gains independence from Redis.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refreshAll() {
        try {
            partnerRepository.findAll().forEach(p ->
                    l1.put(p.getId(),
                           new PartnerSnapshot(p.getId(), p.getSlug(), p.getCompanyName())));
            log.debug("Partner cache refreshed: {} entries", l1.estimatedSize());
        } catch (Exception e) {
            log.debug("Partner cache refresh skipped: {}", e.getMessage());
        }
    }

    /** Evict a specific partner — call on update. */
    public void evict(UUID partnerId) {
        l1.invalidate(partnerId);
    }

    /** Evict everything — primarily for tests / forced reload. */
    public void evictAll() {
        l1.invalidateAll();
    }

    public long size() { return l1.estimatedSize(); }

    public java.util.Map<String, Object> getStats() {
        long total = l1Hits.get() + dbMisses.get();
        com.github.benmanes.caffeine.cache.stats.CacheStats cs = l1.stats();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("size", l1.estimatedSize());
        m.put("l1Hits", l1Hits.get());
        m.put("dbMisses", dbMisses.get());
        m.put("hitRate", total > 0 ? String.format("%.1f%%", l1Hits.get() * 100.0 / total) : "N/A");
        m.put("caffeineEvictions", cs.evictionCount());
        m.put("caffeineHitRate", String.format("%.3f", cs.hitRate()));
        return m;
    }
}
