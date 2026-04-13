package com.filetransfer.shared.cache;

import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.repository.PartnerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-tier partner cache: L1 (ConcurrentHashMap, per-JVM, ~10ns) + L2 (Redis, shared, ~0.5ms).
 * Eliminates the synchronous partner slug DB lookup from the file routing hot path.
 *
 * <p>L1 is bulk-refreshed every 60s. L2 has a 5-minute TTL per entry.
 * On partner update, call {@link #evict(UUID)} to invalidate both tiers.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pipeline.cache.partner.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerCache {

    private static final Duration REDIS_TTL = Duration.ofMinutes(5);
    private static final String PREFIX = "partner:snap:";

    /** L1: in-process, per-pod, zero-latency. */
    private final ConcurrentHashMap<UUID, PartnerSnapshot> l1 = new ConcurrentHashMap<>();

    // Phase 6: hit/miss counters for observability
    private final java.util.concurrent.atomic.AtomicLong l1Hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong l2Hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong dbMisses = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private PartnerRepository partnerRepository;

    @Autowired(required = false)
    @Nullable
    private StringRedisTemplate redis;

    /** Immutable snapshot of the fields needed in the hot path. */
    public record PartnerSnapshot(UUID id, String slug, String companyName) {}

    /**
     * Zero-cost lookup: L1 → L2 (Redis) → DB.
     * L1 hit: ~10ns. L2 hit: ~0.5ms. DB miss: ~5ms (first access only).
     */
    public PartnerSnapshot get(UUID partnerId) {
        if (partnerId == null) return null;

        // L1: per-JVM ConcurrentHashMap
        PartnerSnapshot cached = l1.get(partnerId);
        if (cached != null) { l1Hits.incrementAndGet(); return cached; }

        // L2: Redis (shared across pods)
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(PREFIX + partnerId);
                if (json != null) {
                    cached = deserialize(json);
                    if (cached != null) {
                        l1.put(partnerId, cached);
                        l2Hits.incrementAndGet();
                        return cached;
                    }
                }
            } catch (Exception e) {
                log.debug("Redis partner cache miss: {}", e.getMessage());
            }
        }

        // L3: Database (only on first access or after TTL expiry)
        Partner partner = partnerRepository.findById(partnerId).orElse(null);
        if (partner == null) return null;

        cached = new PartnerSnapshot(partner.getId(), partner.getSlug(), partner.getCompanyName());
        l1.put(partnerId, cached);
        storeInRedis(partnerId, cached);
        dbMisses.incrementAndGet();
        return cached;
    }

    /**
     * Bulk refresh — keeps L1 warm, L2 fresh.
     * Runs every 60s. At 10,000 partners: ~50ms DB query + 10,000 Redis SETs.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refreshAll() {
        try {
            partnerRepository.findAll().forEach(p -> {
                PartnerSnapshot snap = new PartnerSnapshot(p.getId(), p.getSlug(), p.getCompanyName());
                l1.put(p.getId(), snap);
                storeInRedis(p.getId(), snap);
            });
            log.debug("Partner cache refreshed: {} entries", l1.size());
        } catch (Exception e) {
            log.debug("Partner cache refresh skipped: {}", e.getMessage());
        }
    }

    /**
     * Evict a specific partner from both tiers.
     * Call this from AccountEventConsumer or partner update APIs.
     */
    public void evict(UUID partnerId) {
        l1.remove(partnerId);
        if (redis != null) {
            try { redis.delete(PREFIX + partnerId); } catch (Exception ignored) {}
        }
    }

    /** Evict all entries (useful for testing or full reload). */
    public void evictAll() {
        l1.clear();
    }

    public int size() { return l1.size(); }

    /** Phase 6: cache stats for pipeline health endpoint. */
    public java.util.Map<String, Object> getStats() {
        long total = l1Hits.get() + l2Hits.get() + dbMisses.get();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("size", l1.size());
        m.put("l1Hits", l1Hits.get());
        m.put("l2Hits", l2Hits.get());
        m.put("dbMisses", dbMisses.get());
        m.put("hitRate", total > 0 ? String.format("%.1f%%", (l1Hits.get() + l2Hits.get()) * 100.0 / total) : "N/A");
        return m;
    }

    // ── Serialization (simple pipe-delimited — no Jackson dependency in hot path) ──

    private void storeInRedis(UUID partnerId, PartnerSnapshot snap) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(PREFIX + partnerId, serialize(snap), REDIS_TTL);
        } catch (Exception e) {
            log.debug("Redis partner cache store failed: {}", e.getMessage());
        }
    }

    /**
     * JSON serialization — safe for any characters in slug/companyName.
     * No Jackson dependency in hot path — manual JSON for speed.
     */
    private String serialize(PartnerSnapshot snap) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"id\":\"").append(snap.id()).append('"');
        sb.append(",\"slug\":"); appendJsonString(sb, snap.slug());
        sb.append(",\"company\":"); appendJsonString(sb, snap.companyName());
        sb.append('}');
        return sb.toString();
    }

    private void appendJsonString(StringBuilder sb, String value) {
        if (value == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private PartnerSnapshot deserialize(String raw) {
        try {
            // Minimal JSON parse — extract 3 fields between quotes
            String id = extractJsonField(raw, "id");
            String slug = extractJsonField(raw, "slug");
            String company = extractJsonField(raw, "company");
            if (id == null) return null;
            return new PartnerSnapshot(UUID.fromString(id), slug, company);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        if (start >= json.length()) return null;
        if (json.charAt(start) == 'n') return null; // null
        if (json.charAt(start) != '"') return null;
        start++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { sb.append(json.charAt(++i)); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }
}
