package com.filetransfer.shared.health;

import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.cache.StepSnapshotBatchWriter;
import com.filetransfer.shared.cache.TransferRecordBatchWriter;
import com.filetransfer.shared.flow.FlowStageManager;
import com.filetransfer.shared.matching.FlowRuleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified pipeline health endpoint — exposes every component's metrics in one call.
 * Auto-activates in every service. Components that aren't present (null) are omitted.
 *
 * <p>Consumed by Dashboard UI pipeline health card and Activity Monitor footer.
 *
 * <p>GET /api/pipeline/health returns:
 * <pre>{
 *   "timestamp": "2026-04-13T...",
 *   "service": "sftp-service",
 *   "ruleEngine": { ruleCount, bucketCount, totalMatches, totalUnmatched, ... },
 *   "seda": { intake: {queueSize, processed, rejected}, pipeline: {...}, delivery: {...} },
 *   "batchWriters": { records: {pending, flushed, syncFallback}, snapshots: {pending, flushed} },
 *   "partnerCache": { size, l1Enabled, l2Enabled },
 *   "matViewRefresh": { enabled, intervalMs, lastRefreshAt }
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/pipeline")
public class PipelineHealthController {

    @Autowired(required = false) @Nullable private FlowRuleRegistry ruleRegistry;
    @Autowired(required = false) @Nullable private FlowStageManager stageManager;
    @Autowired(required = false) @Nullable private TransferRecordBatchWriter recordWriter;
    @Autowired(required = false) @Nullable private StepSnapshotBatchWriter snapshotWriter;
    @Autowired(required = false) @Nullable private PartnerCache partnerCache;
    @Autowired(required = false) @Nullable private ActivityViewRefresher viewRefresher;

    @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}")
    private String serviceName;

    @org.springframework.beans.factory.annotation.Value("${platform.version:1.0.0-SNAPSHOT}")
    private String platformVersion;

    @org.springframework.beans.factory.annotation.Value("${platform.build.timestamp:#{T(java.time.Instant).now().toString()}}")
    private String buildTimestamp;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", platformVersion);
        result.put("build", buildTimestamp);
        result.put("timestamp", Instant.now().toString());
        result.put("service", serviceName);

        // Rule Engine
        if (ruleRegistry != null) {
            result.put("ruleEngine", ruleRegistry.getMetrics());
        }

        // SEDA Stages
        if (stageManager != null) {
            result.put("seda", stageManager.getStats());
        }

        // Batch Writers
        Map<String, Object> writers = new LinkedHashMap<>();
        if (recordWriter != null) {
            writers.put("records", Map.of(
                    "pending", recordWriter.pendingCount(),
                    "flushed", recordWriter.totalFlushed(),
                    "syncFallback", recordWriter.totalSyncFallback()
            ));
        }
        if (snapshotWriter != null) {
            writers.put("snapshots", Map.of(
                    "pending", snapshotWriter.pendingCount(),
                    "flushed", snapshotWriter.totalFlushed()
            ));
        }
        if (!writers.isEmpty()) result.put("batchWriters", writers);

        // Partner Cache — real hit/miss stats, not just size
        if (partnerCache != null) {
            result.put("partnerCache", partnerCache.getStats());
        }

        // Materialized View Refresher — real stats, not dummy
        if (viewRefresher != null) {
            result.put("matViewRefresh", viewRefresher.getStats());
        }

        return result;
    }
}
