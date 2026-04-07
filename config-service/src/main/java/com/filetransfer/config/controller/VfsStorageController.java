package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VfsIntent.IntentStatus;
import com.filetransfer.shared.repository.VfsChunkRepository;
import com.filetransfer.shared.repository.VfsIntentRepository;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VFS Storage Dashboard API — enterprise observability for the Write-Ahead Intent Protocol
 * and smart storage bucket routing across all SFTP/FTP/FTP-Web services.
 *
 * <p>Provides:
 * <ul>
 *   <li>Bucket distribution (INLINE / STANDARD / CHUNKED file counts and sizes)</li>
 *   <li>Intent protocol health (PENDING / COMMITTED / ABORTED / RECOVERING)</li>
 *   <li>Per-account VFS usage breakdown</li>
 *   <li>Recent intent audit trail</li>
 *   <li>Chunk manifest overview</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/vfs")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Tag(name = "VFS Storage", description = "Virtual Filesystem storage metrics, intent health, and bucket distribution")
public class VfsStorageController {

    private final VirtualEntryRepository entryRepository;
    private final VfsIntentRepository intentRepository;
    private final VfsChunkRepository chunkRepository;

    // ── Dashboard Overview ─────────────────────────────────────────────

    @GetMapping("/dashboard")
    @Operation(summary = "Full VFS dashboard: buckets, intents, accounts")
    public Map<String, Object> dashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", getBucketDistribution());
        result.put("intents", getIntentHealth());
        result.put("totals", getTotals());
        return result;
    }

    // ── Bucket Distribution ────────────────────────────────────────────

    @GetMapping("/buckets")
    @Operation(summary = "File count and size per storage bucket")
    public Map<String, Object> getBucketDistribution() {
        Map<String, Object> buckets = new LinkedHashMap<>();

        long inlineCount = entryRepository.countByStorageBucketAndDeletedFalse("INLINE");
        long standardCount = entryRepository.countByStorageBucketAndDeletedFalse("STANDARD");
        long chunkedCount = entryRepository.countByStorageBucketAndDeletedFalse("CHUNKED");
        long nullCount = entryRepository.countByStorageBucketIsNullAndDeletedFalse();

        long inlineSize = Optional.ofNullable(entryRepository.sumSizeByBucketAndDeletedFalse("INLINE")).orElse(0L);
        long standardSize = Optional.ofNullable(entryRepository.sumSizeByBucketAndDeletedFalse("STANDARD")).orElse(0L);
        long chunkedSize = Optional.ofNullable(entryRepository.sumSizeByBucketAndDeletedFalse("CHUNKED")).orElse(0L);

        buckets.put("inline", Map.of("count", inlineCount, "sizeBytes", inlineSize,
                "label", "INLINE", "description", "< 64KB, stored in DB row"));
        buckets.put("standard", Map.of("count", standardCount + nullCount, "sizeBytes", standardSize,
                "label", "STANDARD", "description", "64KB-64MB, content-addressed storage"));
        buckets.put("chunked", Map.of("count", chunkedCount, "sizeBytes", chunkedSize,
                "label", "CHUNKED", "description", "> 64MB, 4MB chunk streaming"));
        buckets.put("totalFiles", inlineCount + standardCount + chunkedCount + nullCount);

        long totalChunks = chunkRepository.count();
        buckets.put("totalChunks", totalChunks);

        return buckets;
    }

    // ── Intent Health ──────────────────────────────────────────────────

    @GetMapping("/intents/health")
    @Operation(summary = "Intent status breakdown and stale intent detection")
    public Map<String, Object> getIntentHealth() {
        Map<String, Object> health = new LinkedHashMap<>();

        long pending = intentRepository.countByStatus(IntentStatus.PENDING);
        long committed = intentRepository.countByStatus(IntentStatus.COMMITTED);
        long aborted = intentRepository.countByStatus(IntentStatus.ABORTED);
        long recovering = intentRepository.countByStatus(IntentStatus.RECOVERING);

        health.put("pending", pending);
        health.put("committed", committed);
        health.put("aborted", aborted);
        health.put("recovering", recovering);
        health.put("total", pending + committed + aborted + recovering);

        // Stale intents (PENDING > 5 min) — should be zero in healthy system
        Instant threshold = Instant.now().minus(Duration.ofMinutes(5));
        List<VfsIntent> stale = intentRepository.findByStatusAndCreatedAtBefore(IntentStatus.PENDING, threshold);
        health.put("staleCount", stale.size());
        health.put("stalePods", stale.stream().map(VfsIntent::getPodId).distinct().toList());
        health.put("healthy", stale.isEmpty() && recovering == 0);

        return health;
    }

    // ── Recent Intents (audit trail) ───────────────────────────────────

    @GetMapping("/intents/recent")
    @Operation(summary = "Recent VFS intents (last 100)")
    public List<Map<String, Object>> getRecentIntents(
            @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.min(limit, 500);
        return intentRepository.findTopNOrderByCreatedAtDesc(safeLimit).stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", i.getId());
                    m.put("accountId", i.getAccountId());
                    m.put("op", i.getOp());
                    m.put("path", i.getPath());
                    m.put("destPath", i.getDestPath());
                    m.put("status", i.getStatus());
                    m.put("podId", i.getPodId());
                    m.put("sizeBytes", i.getSizeBytes());
                    m.put("storageBucket", i.getStorageKey() == null ? "INLINE" : "STANDARD");
                    m.put("createdAt", i.getCreatedAt());
                    m.put("resolvedAt", i.getResolvedAt());
                    return m;
                }).toList();
    }

    // ── Per-Account Usage ──────────────────────────────────────────────

    @GetMapping("/accounts/{accountId}/usage")
    @Operation(summary = "VFS usage for a specific account")
    public Map<String, Object> getAccountUsage(@PathVariable UUID accountId) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("fileCount", entryRepository.countFilesByAccount(accountId));
        usage.put("dirCount", entryRepository.countDirsByAccount(accountId));
        usage.put("totalSizeBytes", entryRepository.sumSizeByAccount(accountId));

        long inlineCount = entryRepository.countByAccountIdAndStorageBucketAndDeletedFalse(accountId, "INLINE");
        long standardCount = entryRepository.countByAccountIdAndStorageBucketAndDeletedFalse(accountId, "STANDARD");
        long chunkedCount = entryRepository.countByAccountIdAndStorageBucketAndDeletedFalse(accountId, "CHUNKED");

        usage.put("bucketBreakdown", Map.of(
                "inline", inlineCount,
                "standard", standardCount,
                "chunked", chunkedCount
        ));
        return usage;
    }

    // ── Totals ─────────────────────────────────────────────────────────

    private Map<String, Object> getTotals() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalEntries", entryRepository.count());
        totals.put("totalIntents", intentRepository.count());
        totals.put("totalChunks", chunkRepository.count());
        return totals;
    }
}
