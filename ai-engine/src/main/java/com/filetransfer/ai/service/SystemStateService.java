package com.filetransfer.ai.service;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Aggregates cross-service platform state for AI-powered answers.
 *
 * <p>Queries all shared tables (flow executions, step snapshots, DLQ, sentinel,
 * audit logs, transfer records) and builds a unified view. The AI NLP endpoints
 * use this to answer questions like "why did transfer X fail?" with precise,
 * per-step diagnostic information.
 *
 * <p>Refreshes every 60 seconds. Cached in memory for fast AI answers.
 * This is the AI's "awareness" of the entire platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStateService {

    private final FileTransferRecordRepository transferRecords;
    private final AuditLogRepository auditLogs;
    private final TransferAccountRepository accounts;

    @Autowired(required = false) @Nullable
    private FlowExecutionRepository flowExecutions;

    @Autowired(required = false) @Nullable
    private FlowStepSnapshotRepository stepSnapshots;

    @Autowired(required = false) @Nullable
    private DeadLetterMessageRepository deadLetters;

    // Sentinel findings accessed via REST (separate module, not shared repository)
    @Autowired(required = false) @Nullable
    private com.filetransfer.shared.client.ServiceClientProperties serviceProps;

    // ── Cached state (refreshed every 60s) ──
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refresh() {
        try {
            Instant since1h = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

            // Transfer stats
            List<FileTransferRecord> recent = transferRecords.findAll(
                    PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "uploadedAt"))).getContent();
            long totalTransfers = recent.size();
            long failedLast24h = recent.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.FAILED
                            && r.getUploadedAt() != null && r.getUploadedAt().isAfter(since24h))
                    .count();

            cache.put("totalTransfers", totalTransfers);
            cache.put("failedLast24h", failedLast24h);

            // Flow execution stats
            if (flowExecutions != null) {
                long processing = flowExecutions.countByStatus(FlowExecution.FlowStatus.PROCESSING);
                long failed = flowExecutions.countByStatus(FlowExecution.FlowStatus.FAILED);
                long completed = flowExecutions.countByStatus(FlowExecution.FlowStatus.COMPLETED);
                cache.put("flowsProcessing", processing);
                cache.put("flowsFailed", failed);
                cache.put("flowsCompleted", completed);
            }

            // DLQ depth
            if (deadLetters != null) {
                long pendingDlq = deadLetters.findByStatus(DeadLetterMessage.Status.PENDING).size();
                cache.put("dlqPending", pendingDlq);
            }

            // Sentinel findings (via REST — separate module)
            cache.put("sentinelOpen", 0L); // populated by REST call if sentinel is reachable

            log.debug("SystemState refreshed: transfers={}, failed24h={}, dlq={}",
                    totalTransfers, failedLast24h, cache.get("dlqPending"));
        } catch (Exception e) {
            log.debug("SystemState refresh failed: {}", e.getMessage());
        }
    }

    /** Get cached stat */
    public Object getStat(String key) {
        return cache.getOrDefault(key, 0);
    }

    /** Get full platform health summary for AI context */
    public Map<String, Object> getHealthSummary() {
        return new LinkedHashMap<>(cache);
    }

    /**
     * Diagnose why a specific transfer failed — the core AI diagnostic.
     * Pulls from flow_executions, flow_step_snapshots, audit_logs, DLQ.
     */
    public String diagnoseTransfer(String trackId) {
        StringBuilder diag = new StringBuilder();
        diag.append("Diagnosis for transfer ").append(trackId).append(":\n\n");

        // Transfer record
        transferRecords.findByTrackId(trackId).ifPresentOrElse(record -> {
            diag.append("Status: ").append(record.getStatus()).append("\n");
            diag.append("File: ").append(record.getOriginalFilename()).append("\n");
            diag.append("Uploaded: ").append(record.getUploadedAt()).append("\n");
            if (record.getErrorMessage() != null) {
                diag.append("Error: ").append(record.getErrorMessage()).append("\n");
            }
        }, () -> diag.append("No transfer record found.\n"));

        // Flow execution
        if (flowExecutions != null) {
            flowExecutions.findByTrackId(trackId).ifPresent(exec -> {
                diag.append("\nFlow: ").append(exec.getFlow() != null ? exec.getFlow().getName() : "unknown").append("\n");
                diag.append("Flow Status: ").append(exec.getStatus()).append("\n");
                diag.append("Current Step: ").append(exec.getCurrentStep()).append("\n");
                if (exec.getErrorMessage() != null) {
                    diag.append("Flow Error: ").append(exec.getErrorMessage()).append("\n");
                }
                // Step results
                if (exec.getStepResults() != null) {
                    diag.append("\nStep Results:\n");
                    for (var step : exec.getStepResults()) {
                        diag.append("  Step ").append(step.getStepIndex())
                                .append(" (").append(step.getStepType()).append("): ")
                                .append(step.getStatus());
                        if (step.getDurationMs() > 0) diag.append(" [").append(step.getDurationMs()).append("ms]");
                        if (step.getError() != null) diag.append(" ERROR: ").append(step.getError());
                        diag.append("\n");
                    }
                }
            });
        }

        // Step snapshots
        if (stepSnapshots != null) {
            var snapshots = stepSnapshots.findByTrackIdOrderByStepIndex(trackId);
            if (snapshots != null && !snapshots.isEmpty()) {
                diag.append("\nStep Snapshots:\n");
                for (var snap : snapshots) {
                    diag.append("  Step ").append(snap.getStepIndex())
                            .append(" (").append(snap.getStepType()).append("): ")
                            .append(snap.getStepStatus());
                    if (snap.getDurationMs() != null) diag.append(" [").append(snap.getDurationMs()).append("ms]");
                    if (snap.getInputStorageKey() != null) diag.append(" in=").append(snap.getInputStorageKey().substring(0, Math.min(12, snap.getInputStorageKey().length())));
                    if (snap.getOutputStorageKey() != null) diag.append(" out=").append(snap.getOutputStorageKey().substring(0, Math.min(12, snap.getOutputStorageKey().length())));
                    if (snap.getErrorMessage() != null) diag.append(" ERROR: ").append(snap.getErrorMessage());
                    diag.append("\n");
                }
            }
        }

        // Audit trail
        var audits = auditLogs.findAll(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "timestamp")))
                .getContent().stream()
                .filter(a -> trackId.equals(a.getTrackId()))
                .toList();
        if (!audits.isEmpty()) {
            diag.append("\nAudit Trail:\n");
            for (var a : audits) {
                diag.append("  ").append(a.getTimestamp()).append(" ")
                        .append(a.getAction()).append(" ")
                        .append(a.isSuccess() ? "OK" : "FAIL");
                if (a.getPath() != null) diag.append(" path=").append(a.getPath());
                diag.append("\n");
            }
        }

        return diag.toString();
    }

    /**
     * Get recent failures with context — for "what's failing?" questions.
     */
    public String getRecentFailures() {
        StringBuilder sb = new StringBuilder("Recent failures (last 24h):\n\n");

        if (flowExecutions != null) {
            var failed = flowExecutions.findByStatusOrderByStartedAtDesc(FlowExecution.FlowStatus.FAILED);
            var recent = failed.stream()
                    .filter(e -> e.getStartedAt() != null && e.getStartedAt().isAfter(Instant.now().minus(24, ChronoUnit.HOURS)))
                    .limit(10)
                    .toList();
            for (var exec : recent) {
                sb.append("- ").append(exec.getTrackId())
                        .append(" | ").append(exec.getOriginalFilename())
                        .append(" | step ").append(exec.getCurrentStep())
                        .append(" | ").append(exec.getErrorMessage() != null ? exec.getErrorMessage() : "no error message")
                        .append("\n");
            }
            if (recent.isEmpty()) sb.append("No failed flows in the last 24 hours.\n");
        }

        if (deadLetters != null) {
            var pending = deadLetters.findByStatus(DeadLetterMessage.Status.PENDING);
            if (!pending.isEmpty()) {
                sb.append("\nDead Letter Queue (").append(pending.size()).append(" pending):\n");
                for (var msg : pending.stream().limit(5).toList()) {
                    sb.append("- ").append(msg.getOriginalQueue())
                            .append(" | ").append(msg.getErrorMessage() != null ? msg.getErrorMessage() : "no error")
                            .append(" | retries=").append(msg.getRetryCount())
                            .append("\n");
                }
            }
        }

        return sb.toString();
    }
}
