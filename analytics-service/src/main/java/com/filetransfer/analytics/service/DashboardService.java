package com.filetransfer.analytics.service;

import com.filetransfer.analytics.dto.DashboardSummary;
import com.filetransfer.analytics.dto.ScalingRecommendation;
import com.filetransfer.analytics.entity.AlertRule;
import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.AlertRuleRepository;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final MetricSnapshotRepository snapshotRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final PredictionService predictionService;

    @Cacheable(value = "dashboard", unless = "#result == null")
    public DashboardSummary getDashboardSummary() {
        Instant now = Instant.now();
        Instant todayStart = now.truncatedTo(ChronoUnit.DAYS);
        Instant hourStart = now.minus(1, ChronoUnit.HOURS);

        long totalToday = snapshotRepository.sumTotalTransfersSince(todayStart);
        long successToday = snapshotRepository.sumSuccessSince(todayStart);
        long bytesToday = snapshotRepository.sumBytesSince(todayStart);
        long totalLastHour = snapshotRepository.sumTotalTransfersSince(hourStart);

        double successRate = totalToday > 0 ? (double) successToday / totalToday : 1.0;
        double gbToday = bytesToday / (1024.0 * 1024.0 * 1024.0);

        List<MetricSnapshot> last24h = snapshotRepository
                .findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(
                        now.minus(24, ChronoUnit.HOURS), now);

        List<DashboardSummary.TimeSeriesPoint> timeSeries = last24h.stream()
                .map(s -> DashboardSummary.TimeSeriesPoint.builder()
                        .hour(DateTimeFormatter.ofPattern("HH:00")
                                .withZone(ZoneOffset.UTC).format(s.getSnapshotTime()))
                        .transfers(s.getTotalTransfers())
                        .successRate(s.getTotalTransfers() > 0
                                ? (double) s.getSuccessfulTransfers() / s.getTotalTransfers() : 1.0)
                        .bytes(s.getTotalBytesTransferred())
                        .build())
                .collect(Collectors.toList());

        Map<String, Long> byProtocol = last24h.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getServiceType() != null ? s.getServiceType() : "UNKNOWN",
                        Collectors.summingLong(MetricSnapshot::getTotalTransfers)));

        String topProtocol = byProtocol.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("N/A");

        // Active connections: sum activeSessions from the most recent hour's snapshots
        int activeConnections = last24h.stream()
                .filter(s -> s.getSnapshotTime().isAfter(hourStart))
                .mapToInt(s -> s.getActiveSessions() != null ? s.getActiveSessions() : 0)
                .sum();

        List<DashboardSummary.ActiveAlert> alerts = evaluateAlerts(last24h);
        List<ScalingRecommendation> recommendations = predictionService.predictAll();

        return DashboardSummary.builder()
                .totalTransfersToday(totalToday)
                .totalTransfersLastHour(totalLastHour)
                .successRateToday(successRate)
                .totalGbToday(gbToday)
                .activeConnections(activeConnections)
                .topProtocol(topProtocol)
                .alerts(alerts)
                .scalingRecommendations(recommendations)
                .transfersPerHour(timeSeries)
                .transfersByProtocol(byProtocol)
                .build();
    }

    private List<DashboardSummary.ActiveAlert> evaluateAlerts(List<MetricSnapshot> recent) {
        List<DashboardSummary.ActiveAlert> active = new ArrayList<>();
        List<AlertRule> rules = alertRuleRepository.findByEnabledTrue();

        for (AlertRule rule : rules) {
            double currentValue = computeMetricValue(rule, recent);
            boolean triggered = evaluate(currentValue, rule.getOperator(), rule.getThreshold());
            if (triggered) {
                active.add(DashboardSummary.ActiveAlert.builder()
                        .ruleName(rule.getName())
                        .serviceType(rule.getServiceType())
                        .metric(rule.getMetric())
                        .currentValue(currentValue)
                        .threshold(rule.getThreshold())
                        .severity(currentValue > rule.getThreshold() * 2 ? "CRITICAL" : "WARN")
                        .build());
            }
        }
        return active;
    }

    private double computeMetricValue(AlertRule rule, List<MetricSnapshot> snapshots) {
        List<MetricSnapshot> relevant = snapshots.stream()
                .filter(s -> rule.getServiceType() == null ||
                        rule.getServiceType().equals(s.getServiceType()))
                .collect(Collectors.toList());
        if (relevant.isEmpty()) return 0.0;

        return switch (rule.getMetric()) {
            case "ERROR_RATE" -> {
                long total = relevant.stream().mapToLong(MetricSnapshot::getTotalTransfers).sum();
                long failed = relevant.stream().mapToLong(MetricSnapshot::getFailedTransfers).sum();
                yield total > 0 ? (double) failed / total : 0.0;
            }
            case "TRANSFER_VOLUME" -> relevant.stream().mapToLong(MetricSnapshot::getTotalTransfers).sum();
            case "LATENCY_P95" -> relevant.stream().mapToDouble(MetricSnapshot::getP95LatencyMs).average().orElse(0.0);
            default -> 0.0;
        };
    }

    private boolean evaluate(double value, String operator, double threshold) {
        return switch (operator) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            default -> false;
        };
    }
}
