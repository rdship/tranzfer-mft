package com.filetransfer.analytics.service;

import com.filetransfer.analytics.dto.ObservatoryDto;
import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.repository.FlowStepSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.cache.annotation.Cacheable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Powers the Observatory dashboard with three data products:
 *
 * <ul>
 *   <li><b>Heatmap</b>   — 30d × 24h transfer volume grid from MetricSnapshot.
 *   <li><b>Service graph</b> — topology nodes with live traffic + health derived from MetricSnapshot.
 *   <li><b>Domain groups</b> — FlowExecution activity grouped by flow name for the last 7 days.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservatoryService {

    private final MetricSnapshotRepository     snapshotRepo;
    private final FlowExecutionRepository      execRepo;
    private final FlowStepSnapshotRepository   stepSnapshotRepo;

    /** Protocol → UI tier mapping (PLATFORM is the catch-all). */
    private static final Map<String, String> SERVICE_TIERS = Map.ofEntries(
            Map.entry("SFTP",       "INGRESS"),
            Map.entry("FTP",        "INGRESS"),
            Map.entry("FTP_WEB",    "INGRESS"),
            Map.entry("GATEWAY",    "INGRESS"),
            Map.entry("ENCRYPTION", "PROCESSING"),
            Map.entry("SCREENING",  "PROCESSING"),
            Map.entry("STORAGE",    "PROCESSING"),
            Map.entry("EDI",        "PROCESSING"),
            Map.entry("FORWARDER",  "DELIVERY"),
            Map.entry("AS2",        "DELIVERY")
    );

    /** Display labels for all tracked service IDs. */
    private static final Map<String, String> SERVICE_LABELS = Map.ofEntries(
            Map.entry("SFTP",       "SFTP"),
            Map.entry("FTP",        "FTP"),
            Map.entry("FTP_WEB",    "FTP Web"),
            Map.entry("GATEWAY",    "Gateway"),
            Map.entry("ENCRYPTION", "Encryption"),
            Map.entry("SCREENING",  "Screening"),
            Map.entry("STORAGE",    "Storage"),
            Map.entry("EDI",        "EDI"),
            Map.entry("FORWARDER",  "Forwarder"),
            Map.entry("AS2",        "AS2"),
            Map.entry("ANALYTICS",  "Analytics"),
            Map.entry("AI_ENGINE",  "AI Engine"),
            Map.entry("SENTINEL",   "Sentinel")
    );

    @Cacheable("observatory")
    public ObservatoryDto.ObservatoryData getObservatoryData() {
        return ObservatoryDto.ObservatoryData.builder()
                .heatmap(buildHeatmap(30))
                .serviceGraph(buildServiceGraph())
                .domainGroups(buildDomainGroups())
                .generatedAt(Instant.now())
                .build();
    }

    // ── Heatmap ───────────────────────────────────────────────────────────────

    private List<ObservatoryDto.HeatmapCell> buildHeatmap(int days) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        List<MetricSnapshot> snapshots =
                snapshotRepo.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(from, Instant.now());

        // key = "dayOffset:hour" → [total, failed]
        Map<String, long[]> buckets = new HashMap<>();
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);

        for (MetricSnapshot s : snapshots) {
            Instant snapDay = s.getSnapshotTime().truncatedTo(ChronoUnit.DAYS);
            long dayOffset = ChronoUnit.DAYS.between(snapDay, todayStart);
            int  hour      = s.getSnapshotTime().atZone(ZoneOffset.UTC).getHour();
            if (dayOffset < 0 || dayOffset >= days) continue;
            String key = dayOffset + ":" + hour;
            long[] v = buckets.computeIfAbsent(key, k -> new long[2]);
            v[0] += s.getTotalTransfers();
            v[1] += s.getFailedTransfers();
        }

        List<ObservatoryDto.HeatmapCell> cells = new ArrayList<>(days * 24);
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                long[] v = buckets.getOrDefault(d + ":" + h, new long[2]);
                cells.add(ObservatoryDto.HeatmapCell.builder()
                        .dayOffset(d).hour(h).count(v[0]).failedCount(v[1])
                        .build());
            }
        }
        return cells;
    }

    // ── Service graph ─────────────────────────────────────────────────────────

    private List<ObservatoryDto.ServiceNode> buildServiceGraph() {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        List<MetricSnapshot> recent =
                snapshotRepo.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(since, Instant.now());

        // Aggregate per serviceType for the last hour
        Map<String, long[]> byType = new LinkedHashMap<>();
        for (MetricSnapshot s : recent) {
            String type = s.getServiceType() != null ? s.getServiceType().toUpperCase() : "UNKNOWN";
            long[] v = byType.computeIfAbsent(type, k -> new long[2]);
            v[0] += s.getTotalTransfers();
            v[1] += s.getFailedTransfers();
        }

        List<ObservatoryDto.ServiceNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> entry : SERVICE_LABELS.entrySet()) {
            String svcKey = entry.getKey();
            long[] v = byType.getOrDefault(svcKey, new long[2]);
            long total = v[0], failed = v[1];
            double errorRate = total > 0 ? (double) failed / total : 0.0;
            String health = total == 0 ? "UNKNOWN"
                    : errorRate > 0.3 ? "DOWN"
                    : errorRate > 0.1 ? "DEGRADED"
                    : "UP";

            nodes.add(ObservatoryDto.ServiceNode.builder()
                    .id(svcKey.toLowerCase().replace('_', '-'))
                    .label(entry.getValue())
                    .tier(SERVICE_TIERS.getOrDefault(svcKey, "PLATFORM"))
                    .health(health)
                    .transfersLastHour(total)
                    .errorRate(errorRate)
                    .build());
        }
        return nodes;
    }

    // ── Domain groups ─────────────────────────────────────────────────────────

    private List<ObservatoryDto.DomainGroup> buildDomainGroups() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        List<FlowExecution> execs = execRepo.findRecentWithFlow(since);

        Map<String, List<FlowExecution>> grouped = execs.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getFlow() != null ? e.getFlow().getName() : "Unmatched"));

        return grouped.entrySet().stream().map(entry -> {
            List<FlowExecution> group = entry.getValue();
            long total      = group.size();
            long completed  = group.stream().filter(e -> e.getStatus() == FlowExecution.FlowStatus.COMPLETED).count();
            long failed     = group.stream().filter(e -> e.getStatus() == FlowExecution.FlowStatus.FAILED
                                                      || e.getStatus() == FlowExecution.FlowStatus.CANCELLED).count();
            long processing = group.stream().filter(e -> e.getStatus() == FlowExecution.FlowStatus.PROCESSING
                                                      || e.getStatus() == FlowExecution.FlowStatus.PENDING).count();

            Instant lastActivity = group.stream()
                    .map(e -> e.getCompletedAt() != null ? e.getCompletedAt() : e.getStartedAt())
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);

            String topError = group.stream()
                    .filter(e -> e.getErrorMessage() != null)
                    .map(FlowExecution::getErrorMessage)
                    .findFirst().orElse(null);

            return ObservatoryDto.DomainGroup.builder()
                    .domainName(entry.getKey())
                    .totalCount(total)
                    .completedCount(completed)
                    .failedCount(failed)
                    .processingCount(processing)
                    .successRate(total > 0 ? (double) completed / total : 1.0)
                    .lastActivityAt(lastActivity)
                    .topError(topError)
                    .build();
        })
        .sorted(Comparator.comparingLong(ObservatoryDto.DomainGroup::getTotalCount).reversed())
        .collect(Collectors.toList());
    }

    // ── Step Latency Heatmap ─────────────────────────────────────────────────

    /**
     * Aggregates {@code FlowStepSnapshot.durationMs} by step type over the given window.
     *
     * <p>Returns two data products:
     * <ul>
     *   <li><b>summary</b>  — avg/P95/min/max latency + call count + failure rate per step type.
     *   <li><b>heatmap</b>  — avg latency per step type × hour-of-day (0-23 UTC) grid.
     * </ul>
     */
    @Cacheable(value = "step-latency", key = "#hours")
    public ObservatoryDto.StepLatencyData getStepLatencyData(int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        // ── Summary (one row per step type) ──
        List<ObservatoryDto.StepSummary> summary = stepSnapshotRepo
                .summarizeByStepType(since)
                .stream()
                .map(row -> {
                    long total  = toLong(row[5]);
                    long failed = toLong(row[6]);
                    return ObservatoryDto.StepSummary.builder()
                            .stepType(  (String) row[0])
                            .avgMs(     toDouble(row[1]))
                            .p95Ms(     toDouble(row[2]))
                            .minMs(     toLong(  row[3]))
                            .maxMs(     toLong(  row[4]))
                            .totalCalls(total)
                            .failedCalls(failed)
                            .failureRate(total > 0 ? (double) failed / total : 0.0)
                            .build();
                })
                .collect(Collectors.toList());

        // ── Heatmap (stepType × hourOfDay) ──
        List<ObservatoryDto.StepHeatmapCell> heatmap = stepSnapshotRepo
                .heatmapByStepAndHour(since)
                .stream()
                .map(row -> ObservatoryDto.StepHeatmapCell.builder()
                        .stepType(  (String) row[0])
                        .hourOfDay( toInt(   row[1]))
                        .avgMs(     toDouble(row[2]))
                        .callCount( toLong(  row[3]))
                        .build())
                .collect(Collectors.toList());

        return ObservatoryDto.StepLatencyData.builder()
                .summary(summary)
                .heatmap(heatmap)
                .hours(hours)
                .generatedAt(Instant.now())
                .build();
    }

    // ── Numeric helpers for native query Object[] rows ───────────────────────

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
