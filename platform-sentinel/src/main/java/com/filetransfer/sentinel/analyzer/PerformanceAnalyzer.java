package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.HealthCollector;
import com.filetransfer.sentinel.collector.MetricsCollector;
import com.filetransfer.sentinel.collector.TransferCollector;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceAnalyzer {

    private static final String ANALYZER = "PERFORMANCE";

    private final SentinelRuleRepository ruleRepository;
    private final SentinelFindingRepository findingRepository;
    private final MetricsCollector metricsCollector;
    private final TransferCollector transferCollector;
    private final HealthCollector healthCollector;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 300000)
    @SchedulerLock(name = "sentinel_performanceAnalyzer", lockAtLeastFor = "PT4M", lockAtMostFor = "PT9M")
    @Transactional
    public void analyze() {
        log.info("PerformanceAnalyzer: starting analysis cycle");

        metricsCollector.collect();
        transferCollector.collect(60);
        healthCollector.collect();

        List<SentinelRule> rules = ruleRepository.findByAnalyzerAndEnabledTrue(ANALYZER);
        int findings = 0;

        for (SentinelRule rule : rules) {
            if (rule.isInCooldown()) continue;
            try {
                SentinelFinding finding = evaluate(rule);
                if (finding != null) {
                    findingRepository.save(finding);
                    rule.setLastTriggered(Instant.now());
                    ruleRepository.save(rule);
                    findings++;
                }
            } catch (Exception e) {
                log.warn("PerformanceAnalyzer: rule '{}' failed: {}", rule.getName(), e.getMessage());
            }
        }

        log.info("PerformanceAnalyzer: cycle complete, {} new findings", findings);
    }

    private SentinelFinding evaluate(SentinelRule rule) {
        return switch (rule.getName()) {
            case "latency_degradation" -> checkLatency(rule);
            case "error_rate_spike" -> checkErrorRate(rule);
            case "throughput_drop" -> checkThroughput(rule);
            case "service_unhealthy" -> checkServiceHealth(rule);
            case "disk_usage_high" -> checkDiskUsage(rule);
            case "connection_saturation" -> checkConnections(rule);
            default -> null;
        };
    }

    private SentinelFinding checkLatency(SentinelRule rule) {
        double current = metricsCollector.getP95Latency();
        if (current <= 0) return null;

        // Compare current p95 against a reasonable baseline (> 5000ms is always bad)
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 50;
        // If p95 > 5s, that's always a problem
        if (current < 5000) return null;

        return buildFinding(rule,
                String.format("Latency degradation: p95 = %.0fms", current),
                String.format("P95 latency is %.0fms which exceeds acceptable thresholds. " +
                        "Investigate slow transfers, disk I/O, or downstream service bottlenecks.", current),
                evidence("p95LatencyMs", current, "threshold", threshold));
    }

    private SentinelFinding checkErrorRate(SentinelRule rule) {
        double rate = metricsCollector.getFailureRate();
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 10;
        if (rate <= threshold || metricsCollector.getTransfersToday() < 10) return null;

        return buildFinding(rule,
                String.format("Error rate spike: %.1f%%", rate),
                String.format("Transfer error rate is %.1f%% (threshold: %.0f%%). " +
                        "High error rates indicate systemic issues requiring investigation.", rate, threshold),
                evidence("errorRate", rate, "threshold", threshold,
                        "transfersToday", metricsCollector.getTransfersToday()));
    }

    private SentinelFinding checkThroughput(SentinelRule rule) {
        long current = metricsCollector.getTransfersLastHour();
        // Only alert if we normally have traffic and it dropped significantly
        if (current >= 5 || metricsCollector.getTransfersToday() < 20) return null;

        // If today is abnormally low (less than 40% of what we'd expect)
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 60;
        return null; // Needs baseline comparison — skip until we have 24h data
    }

    private SentinelFinding checkServiceHealth(SentinelRule rule) {
        Map<String, HealthCollector.ServiceHealth> health = healthCollector.getServiceHealth();
        List<String> downServices = new ArrayList<>();

        for (var entry : health.entrySet()) {
            if ("DOWN".equals(entry.getValue().status())) {
                downServices.add(entry.getKey());
            }
        }

        if (downServices.isEmpty()) return null;

        String first = downServices.get(0);
        return SentinelFinding.builder()
                .analyzer(ANALYZER)
                .ruleName(rule.getName())
                .severity(rule.getSeverity())
                .title(String.format("Service unhealthy: %d service(s) DOWN", downServices.size()))
                .description(String.format("The following services failed health checks: %s. " +
                        "This may cause transfer failures and degraded functionality.",
                        String.join(", ", downServices)))
                .evidence(evidence("downServices", downServices, "healthyCount", healthCollector.getHealthyCount(),
                        "totalCount", healthCollector.getTotalCount()))
                .affectedService(first)
                .build();
    }

    private SentinelFinding checkDiskUsage(SentinelRule rule) {
        // Disk usage comes from actuator health details — would need /actuator/health with details
        // For now, this is a placeholder that activates when we get richer health data
        return null;
    }

    private SentinelFinding checkConnections(SentinelRule rule) {
        // Connection saturation would come from gateway/dmz stats
        // Placeholder for future enrichment
        return null;
    }

    // --- helpers ---

    private SentinelFinding buildFinding(SentinelRule rule, String title, String description, String evidence) {
        if (findingRepository.existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
                ANALYZER, rule.getName(), null, null)) {
            return null;
        }

        return SentinelFinding.builder()
                .analyzer(ANALYZER)
                .ruleName(rule.getName())
                .severity(rule.getSeverity())
                .title(title)
                .description(description)
                .evidence(evidence)
                .build();
    }

    private String evidence(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        map.put("analyzedAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
