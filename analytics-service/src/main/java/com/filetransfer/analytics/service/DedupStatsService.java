package com.filetransfer.analytics.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Calculates CAS (Content Addressable Storage) deduplication savings by running
 * native SQL across the shared PostgreSQL database.
 *
 * <p>Deduplication works by keying storage on SHA-256: if the same file is uploaded
 * N times, it occupies disk space only once. The savings are:
 * <pre>
 *   bytes_saved = SUM(size × (ref_count − 1)) over all objects with ref_count > 1
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DedupStatsService {

    private final EntityManager em;

    /**
     * Full deduplication statistics report.
     */
    @Cacheable("dedup-stats")
    public Map<String, Object> getDedupStats() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // ── 1. Unique bytes actually stored on disk ──
            Object uniqueBytes = em.createNativeQuery(
                    "SELECT COALESCE(SUM(size_bytes), 0) FROM storage_objects WHERE deleted = false")
                    .getSingleResult();

            // ── 2. Total bytes referenced across VFS entries (without dedup = what it would cost) ──
            Object totalRefBytes = em.createNativeQuery(
                    "SELECT COALESCE(SUM(ve.size_bytes), 0) FROM virtual_entries ve " +
                    "WHERE ve.deleted = false AND ve.storage_key IS NOT NULL AND ve.type = 'FILE'")
                    .getSingleResult();

            // ── 3. Count of unique objects ──
            Object uniqueObjects = em.createNativeQuery(
                    "SELECT COUNT(*) FROM storage_objects WHERE deleted = false")
                    .getSingleResult();

            // ── 4. Count of total VFS file references ──
            Object totalRefs = em.createNativeQuery(
                    "SELECT COUNT(*) FROM virtual_entries WHERE deleted = false " +
                    "AND storage_key IS NOT NULL AND type = 'FILE'")
                    .getSingleResult();

            // ── 5. Count of deduplicated objects (referenced more than once) ──
            Object dedupedObjects = em.createNativeQuery(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT ve.storage_key FROM virtual_entries ve " +
                    "  WHERE ve.deleted = false AND ve.storage_key IS NOT NULL " +
                    "  GROUP BY ve.storage_key HAVING COUNT(*) > 1" +
                    ") x")
                    .getSingleResult();

            // ── 6. Tier breakdown ──
            @SuppressWarnings("unchecked")
            List<Object[]> tierRows = em.createNativeQuery(
                    "SELECT tier, COUNT(*), COALESCE(SUM(size_bytes), 0) " +
                    "FROM storage_objects WHERE deleted = false " +
                    "GROUP BY tier ORDER BY tier")
                    .getResultList();

            // ── 7. Top 10 most deduplicated files (most savings first) ──
            @SuppressWarnings("unchecked")
            List<Object[]> topRows = em.createNativeQuery(
                    "SELECT so.sha256, so.size_bytes, COUNT(ve.id) AS ref_count, " +
                    "       so.size_bytes * (COUNT(ve.id) - 1) AS bytes_saved, so.tier " +
                    "FROM storage_objects so " +
                    "JOIN virtual_entries ve ON ve.storage_key = so.sha256 AND ve.deleted = false " +
                    "WHERE so.deleted = false " +
                    "GROUP BY so.sha256, so.size_bytes, so.tier " +
                    "HAVING COUNT(ve.id) > 1 " +
                    "ORDER BY bytes_saved DESC LIMIT 10")
                    .getResultList();

            // ── Build response ──
            long storedBytes   = toLong(uniqueBytes);
            long referencedBytes = toLong(totalRefBytes);
            long savedBytes    = Math.max(0, referencedBytes - storedBytes);
            double ratio       = storedBytes > 0 ? (double) referencedBytes / storedBytes : 1.0;

            result.put("uniqueBytesStored",    storedBytes);
            result.put("totalBytesReferenced", referencedBytes);
            result.put("bytesSaved",           savedBytes);
            result.put("deduplicationRatio",   Math.round(ratio * 100.0) / 100.0);
            result.put("savingsPercent",        storedBytes > 0
                    ? Math.round((double) savedBytes / referencedBytes * 100.0 * 10) / 10.0 : 0.0);
            result.put("uniqueObjects",         toLong(uniqueObjects));
            result.put("totalReferences",       toLong(totalRefs));
            result.put("dedupedObjects",        toLong(dedupedObjects));

            // Tier breakdown
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (Object[] row : tierRows) {
                Map<String, Object> tier = new LinkedHashMap<>();
                tier.put("tier",      row[0]);
                tier.put("count",     toLong(row[1]));
                tier.put("sizeBytes", toLong(row[2]));
                tiers.add(tier);
            }
            result.put("tierBreakdown", tiers);

            // Top deduplicated files
            List<Map<String, Object>> top = new ArrayList<>();
            for (Object[] row : topRows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                String sha256 = (String) row[0];
                entry.put("sha256Short", sha256 != null ? sha256.substring(0, Math.min(12, sha256.length())) + "…" : "—");
                entry.put("sizeBytes",   toLong(row[1]));
                entry.put("refCount",    toLong(row[2]));
                entry.put("bytesSaved",  toLong(row[3]));
                entry.put("tier",        row[4]);
                top.add(entry);
            }
            result.put("topDeduplicated", top);
            result.put("generatedAt", java.time.Instant.now().toString());

        } catch (Exception e) {
            log.warn("DedupStatsService: query failed (storage_objects or virtual_entries may not exist): {}", e.getMessage());
            // Return empty/zero stats — storage-manager may not be running
            result.put("uniqueBytesStored",    0L);
            result.put("totalBytesReferenced", 0L);
            result.put("bytesSaved",           0L);
            result.put("deduplicationRatio",   1.0);
            result.put("savingsPercent",        0.0);
            result.put("uniqueObjects",         0L);
            result.put("totalReferences",       0L);
            result.put("dedupedObjects",        0L);
            result.put("tierBreakdown",         List.of());
            result.put("topDeduplicated",       List.of());
            result.put("generatedAt",           java.time.Instant.now().toString());
            result.put("error",                 "Storage tables unavailable — is storage-manager running?");
        }

        return result;
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof BigDecimal bd) return bd.longValue();
        return 0L;
    }
}
