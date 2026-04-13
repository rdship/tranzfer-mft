package com.filetransfer.ai.service.phase3;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.AuditLogRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-powered observability analyzer.
 * Scrapes Prometheus metrics and OTEL data from all services,
 * analyzes patterns, and generates actionable recommendations.
 *
 * Categories:
 * - PERFORMANCE: slow queries, high latency, throughput degradation
 * - SCALING: services needing more/fewer replicas
 * - RELIABILITY: error spikes, connection pool exhaustion, disk pressure
 * - SECURITY: unusual access patterns, brute force attempts
 * - COST: over-provisioned services, idle resources
 * - COMPLIANCE: audit gaps, screening backlogs, SLA risks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ObservabilityAnalyzer {

    private final FileTransferRecordRepository transferRepo;
    private final AuditLogRepository auditLogRepo;
    private final ServiceRegistrationRepository serviceRepo;

    @Value("${ai.observability.prometheus-url:http://prometheus:9090}")
    private String prometheusUrl;

    private final List<Recommendation> recommendations = Collections.synchronizedList(new ArrayList<>());
    private volatile Instant lastAnalysis;
    private volatile Map<String, Object> healthSummary = new LinkedHashMap<>();

    @Scheduled(fixedDelay = 300000) // every 5 min
    @SchedulerLock(name = "ai_observability_analyze", lockAtLeastFor = "PT4M", lockAtMostFor = "PT14M")
    public void analyze() {
        List<Recommendation> newRecs = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        // Collect data
        List<FileTransferRecord> allRecords = transferRepo.findAll();
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant oneDayAgo = now.minus(1, ChronoUnit.DAYS);

        List<FileTransferRecord> lastHour = allRecords.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(oneHourAgo))
                .collect(Collectors.toList());
        List<FileTransferRecord> lastDay = allRecords.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(oneDayAgo))
                .collect(Collectors.toList());

        // === PERFORMANCE ANALYSIS ===

        // Transfer latency
        double avgLatencyMs = lastHour.stream()
                .filter(r -> r.getRoutedAt() != null && r.getUploadedAt() != null)
                .mapToLong(r -> ChronoUnit.MILLIS.between(r.getUploadedAt(), r.getRoutedAt()))
                .average().orElse(0);
        summary.put("avgTransferLatencyMs", Math.round(avgLatencyMs));

        if (avgLatencyMs > 5000) {
            newRecs.add(rec("PERFORMANCE", "CRITICAL",
                    "Transfer latency is " + Math.round(avgLatencyMs) + "ms (threshold: 5000ms)",
                    "Scale SFTP and routing services. Check database query performance. Consider adding read replicas.",
                    Map.of("currentLatencyMs", Math.round(avgLatencyMs), "threshold", 5000)));
        } else if (avgLatencyMs > 2000) {
            newRecs.add(rec("PERFORMANCE", "WARNING",
                    "Transfer latency trending high at " + Math.round(avgLatencyMs) + "ms",
                    "Monitor closely. If sustained, add SFTP replicas.",
                    Map.of("currentLatencyMs", Math.round(avgLatencyMs))));
        }

        // Throughput
        long transfersLastHour = lastHour.size();
        long transfersLastDay = lastDay.size();
        summary.put("transfersLastHour", transfersLastHour);
        summary.put("transfersLastDay", transfersLastDay);

        // === RELIABILITY ANALYSIS ===

        long failedLastHour = lastHour.stream().filter(r -> r.getStatus() == FileTransferStatus.FAILED).count();
        double errorRate = transfersLastHour > 0 ? (double) failedLastHour / transfersLastHour : 0;
        summary.put("errorRateLastHour", Math.round(errorRate * 10000) / 100.0);

        if (errorRate > 0.05) {
            // Analyze failure patterns
            Map<String, Long> errorsByType = lastHour.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.FAILED && r.getErrorMessage() != null)
                    .collect(Collectors.groupingBy(r -> classifyError(r.getErrorMessage()), Collectors.counting()));

            String topError = errorsByType.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() + " (" + e.getValue() + " occurrences)").orElse("unknown");

            newRecs.add(rec("RELIABILITY", errorRate > 0.1 ? "CRITICAL" : "WARNING",
                    String.format("Error rate is %.1f%% (%d failures in last hour). Top cause: %s",
                            errorRate * 100, failedLastHour, topError),
                    buildErrorRemediation(errorsByType),
                    Map.of("errorRate", Math.round(errorRate * 1000) / 10.0, "failures", failedLastHour, "topError", topError)));
        }

        // Stuck transfers
        long stuckCount = allRecords.stream()
                .filter(r -> r.getStatus() == FileTransferStatus.PENDING
                        && r.getUploadedAt() != null
                        && r.getUploadedAt().isBefore(now.minus(30, ChronoUnit.MINUTES)))
                .count();
        summary.put("stuckTransfers", stuckCount);

        if (stuckCount > 0) {
            newRecs.add(rec("RELIABILITY", stuckCount > 50 ? "CRITICAL" : "WARNING",
                    stuckCount + " transfers stuck in PENDING for >30 minutes",
                    "Auto-remediation should clear these. If persisting: check routing engine logs, verify destination service is running, check disk space.",
                    Map.of("stuckCount", stuckCount)));
        }

        // === SCALING ANALYSIS ===

        // Predict if current capacity is sufficient
        double peakTransfersPerMin = lastHour.stream()
                .collect(Collectors.groupingBy(r -> r.getUploadedAt().truncatedTo(ChronoUnit.MINUTES), Collectors.counting()))
                .values().stream().mapToLong(Long::longValue).max().orElse(0);
        summary.put("peakTransfersPerMin", peakTransfersPerMin);

        if (peakTransfersPerMin > 200) { // ~500 concurrent if each takes 2-3 sec
            int recommendedSftpReplicas = (int) Math.ceil(peakTransfersPerMin / 50.0); // 50 transfers/min/replica
            newRecs.add(rec("SCALING", "INFO",
                    String.format("Peak rate: %.0f transfers/min. Recommend %d SFTP replicas.",
                            peakTransfersPerMin, recommendedSftpReplicas),
                    "Run: kubectl scale statefulset sftp-service --replicas=" + recommendedSftpReplicas,
                    Map.of("peakRate", peakTransfersPerMin, "recommendedReplicas", recommendedSftpReplicas)));
        }

        // Check for over-provisioning (low utilization)
        if (transfersLastDay < 100 && transfersLastDay > 0) {
            newRecs.add(rec("COST", "INFO",
                    "Low transfer volume (" + transfersLastDay + " in 24h). Services may be over-provisioned.",
                    "Consider scaling down: SFTP to 2 replicas, encryption to 2, screening to 1.",
                    Map.of("dailyVolume", transfersLastDay)));
        }

        // === COMPLIANCE ANALYSIS ===

        // Check for transfers without screening
        long unscreenedToday = lastDay.stream()
                .filter(r -> r.getSourceChecksum() == null)
                .count();
        if (unscreenedToday > 0 && transfersLastDay > 0) {
            double unscreenedPct = (double) unscreenedToday / transfersLastDay * 100;
            if (unscreenedPct > 10) {
                newRecs.add(rec("COMPLIANCE", "WARNING",
                        String.format("%.0f%% of transfers (%d) have no source checksum — integrity verification gaps",
                                unscreenedPct, unscreenedToday),
                        "Ensure all flows include checksum computation. Check if AI classification is enabled.",
                        Map.of("unscreenedPercent", Math.round(unscreenedPct), "count", unscreenedToday)));
            }
        }

        // Retry rate analysis
        long retriedTransfers = allRecords.stream().filter(r -> r.getRetryCount() > 0).count();
        if (retriedTransfers > 100) {
            newRecs.add(rec("RELIABILITY", "INFO",
                    retriedTransfers + " transfers required retries. Review failure root causes.",
                    "Check GuaranteedDeliveryService logs. Most retries indicate transient issues (network, disk). Persistent retries indicate config problems.",
                    Map.of("retriedCount", retriedTransfers)));
        }

        // === SECURITY ANALYSIS ===

        // Check for brute force patterns in audit logs
        long loginFails = auditLogRepo.findAll().stream()
                .filter(a -> "LOGIN_FAIL".equals(a.getAction()))
                .filter(a -> a.getTimestamp() != null && a.getTimestamp().isAfter(oneHourAgo))
                .count();
        summary.put("loginFailuresLastHour", loginFails);

        if (loginFails > 20) {
            newRecs.add(rec("SECURITY", loginFails > 50 ? "CRITICAL" : "WARNING",
                    loginFails + " failed login attempts in the last hour",
                    "Possible brute-force attack. Verify BruteForceProtection is active. Check source IPs in audit logs. Consider geo-blocking.",
                    Map.of("failedLogins", loginFails)));
        }

        // === SERVICE HEALTH ===

        long registeredServices = serviceRepo.count();
        summary.put("registeredServices", registeredServices);
        summary.put("totalTransferRecords", allRecords.size());
        summary.put("analysisTimestamp", now.toString());

        // Scrape Prometheus metrics if available
        try {
            Map<String, Object> prometheusMetrics = scrapePrometheus();
            if (prometheusMetrics != null) {
                summary.put("prometheus", prometheusMetrics);
                analyzePrometheusMetrics(prometheusMetrics, newRecs);
            }
        } catch (Exception e) {
            log.debug("Prometheus not available: {}", e.getMessage());
        }

        // Finalize
        recommendations.clear();
        recommendations.addAll(newRecs);
        healthSummary = summary;
        lastAnalysis = now;

        if (!newRecs.isEmpty()) {
            log.info("Observability analysis: {} recommendations generated", newRecs.size());
        }
    }

    private Map<String, Object> scrapePrometheus() {
        try {
            RestTemplate rest = new RestTemplate();
            // Try to get key metrics from each service's actuator
            Map<String, Object> metrics = new LinkedHashMap<>();

            for (String svc : List.of("onboarding-api:8080", "sftp-service:8081", "config-service:8084",
                    "analytics-service:8090", "ai-engine:8091", "screening-service:8092")) {
                try {
                    String[] parts = svc.split(":");
                    String url = "http://" + parts[0] + ":" + parts[1] + "/actuator/metrics/jvm.memory.used";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resp = rest.getForObject(url, Map.class);
                    if (resp != null && resp.containsKey("measurements")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> measurements = (List<Map<String, Object>>) resp.get("measurements");
                        if (!measurements.isEmpty()) {
                            double memoryBytes = ((Number) measurements.get(0).get("value")).doubleValue();
                            metrics.put(parts[0] + "_memory_mb", Math.round(memoryBytes / 1024 / 1024));
                        }
                    }
                } catch (Exception ignored) {}
            }
            return metrics.isEmpty() ? null : metrics;
        } catch (Exception e) {
            return null;
        }
    }

    private void analyzePrometheusMetrics(Map<String, Object> metrics, List<Recommendation> recs) {
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            if (entry.getKey().endsWith("_memory_mb") && entry.getValue() instanceof Number) {
                long memMb = ((Number) entry.getValue()).longValue();
                String service = entry.getKey().replace("_memory_mb", "");
                if (memMb > 3000) { // > 3GB
                    recs.add(rec("PERFORMANCE", "WARNING",
                            service + " using " + memMb + "MB memory (>3GB)",
                            "Consider increasing JVM heap (-Xmx) or scaling horizontally. Check for memory leaks if growing continuously.",
                            Map.of("service", service, "memoryMb", memMb)));
                }
            }
        }
    }

    private String classifyError(String error) {
        if (error == null) return "UNKNOWN";
        String lower = error.toLowerCase();
        if (lower.contains("timeout") || lower.contains("connection")) return "NETWORK";
        if (lower.contains("auth") || lower.contains("permission")) return "AUTH";
        if (lower.contains("disk") || lower.contains("space") || lower.contains("quota")) return "STORAGE";
        if (lower.contains("checksum") || lower.contains("integrity")) return "INTEGRITY";
        if (lower.contains("key") || lower.contains("encrypt") || lower.contains("decrypt")) return "ENCRYPTION";
        if (lower.contains("sanction") || lower.contains("blocked") || lower.contains("screen")) return "SCREENING";
        return "OTHER";
    }

    private String buildErrorRemediation(Map<String, Long> errorsByType) {
        StringBuilder sb = new StringBuilder("Recommended actions:\n");
        if (errorsByType.containsKey("NETWORK")) sb.append("- NETWORK errors: Check connectivity to partner servers. Verify firewall rules. Increase connection timeouts.\n");
        if (errorsByType.containsKey("AUTH")) sb.append("- AUTH errors: Verify partner credentials. Check if keys expired. Review BruteForceProtection lockouts.\n");
        if (errorsByType.containsKey("STORAGE")) sb.append("- STORAGE errors: Check disk space on /data. Run cleanup job. Expand storage volume.\n");
        if (errorsByType.containsKey("INTEGRITY")) sb.append("- INTEGRITY errors: Files corrupted in transit. Check network stability. Verify source system.\n");
        if (errorsByType.containsKey("ENCRYPTION")) sb.append("- ENCRYPTION errors: Check Keystore Manager for expired keys. Verify partner PGP key is current.\n");
        if (errorsByType.containsKey("SCREENING")) sb.append("- SCREENING errors: Check screening-service health. Verify OFAC list loaded.\n");
        return sb.toString();
    }

    private Recommendation rec(String category, String severity, String finding, String action, Map<String, Object> data) {
        return Recommendation.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .category(category).severity(severity)
                .finding(finding).recommendedAction(action)
                .data(data).generatedAt(Instant.now())
                .build();
    }

    public List<Recommendation> getRecommendations() { return Collections.unmodifiableList(recommendations); }
    public List<Recommendation> getRecommendationsByCategory(String category) {
        return recommendations.stream().filter(r -> r.category.equalsIgnoreCase(category)).collect(Collectors.toList());
    }
    public Map<String, Object> getHealthSummary() { return Collections.unmodifiableMap(healthSummary); }
    public Instant getLastAnalysis() { return lastAnalysis; }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Recommendation {
        private String id;
        private String category;    // PERFORMANCE, SCALING, RELIABILITY, SECURITY, COST, COMPLIANCE
        private String severity;    // CRITICAL, WARNING, INFO
        private String finding;     // What was found
        private String recommendedAction; // What to do
        private Map<String, Object> data; // Supporting metrics
        private Instant generatedAt;
    }
}
