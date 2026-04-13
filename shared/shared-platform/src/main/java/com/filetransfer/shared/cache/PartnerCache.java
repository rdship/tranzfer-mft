package com.filetransfer.shared.cache;

import com.filetransfer.shared.entity.Partner;
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
        if (cached != null) return cached;

        // L2: Redis (shared across pods)
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(PREFIX + partnerId);
                if (json != null) {
                    cached = deserialize(json);
                    if (cached != null) {
                        l1.put(partnerId, cached);
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

    // ── Serialization (simple pipe-delimited — no Jackson dependency in hot path) ──

    private void storeInRedis(UUID partnerId, PartnerSnapshot snap) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(PREFIX + partnerId, serialize(snap), REDIS_TTL);
        } catch (Exception e) {
            log.debug("Redis partner cache store failed: {}", e.getMessage());
        }
    }

    private String serialize(PartnerSnapshot snap) {
        // Format: id|slug|companyName
        return snap.id() + "|" + (snap.slug() != null ? snap.slug() : "") +
                "|" + (snap.companyName() != null ? snap.companyName() : "");
    }

    private PartnerSnapshot deserialize(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) return null;
        return new PartnerSnapshot(
                UUID.fromString(parts[0]),
                parts[1].isEmpty() ? null : parts[1],
                parts[2].isEmpty() ? null : parts[2]
        );
    }
}
