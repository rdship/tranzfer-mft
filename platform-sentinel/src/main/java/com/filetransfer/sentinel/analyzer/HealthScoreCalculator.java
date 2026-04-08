package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.DlqCollector;
import com.filetransfer.sentinel.collector.HealthCollector;
import com.filetransfer.sentinel.collector.MetricsCollector;
import com.filetransfer.sentinel.entity.HealthScore;
import com.filetransfer.sentinel.repository.HealthScoreRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthScoreCalculator {

    private final HealthCollector healthCollector;
    private final MetricsCollector metricsCollector;
    private final DlqCollector dlqCollector;
    private final SentinelFindingRepository findingRepository;
    private final HealthScoreRepository healthScoreRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 300000)
    @SchedulerLock(name = "sentinel_healthScore", lockAtLeastFor = "PT4M", lockAtMostFor = "PT9M")
    @Transactional
    public void calculate() {
        log.info("HealthScoreCalculator: computing platform health score");

        // Collect fresh data
        healthCollector.collect();
        metricsCollector.collect();
        dlqCollector.collect();

        int infraScore = calculateInfrastructureScore();
        int dataScore = calculateDataScore();
        int securityScore = calculateSecurityScore();

        // Weighted composite: infra 30%, data 40%, security 30%
        int overall = (int) Math.round(infraScore * 0.3 + dataScore * 0.4 + securityScore * 0.3);
        overall = Math.max(0, Math.min(100, overall));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("infrastructure", Map.of(
                "score", infraScore,
                "healthyServices", healthCollector.getHealthyCount(),
                "totalServices", healthCollector.getTotalCount(),
                "pendingDlq", dlqCollector.getPendingCount()));
        details.put("data", Map.of(
                "score", dataScore,
                "failureRate", metricsCollector.getFailureRate(),
                "transfersToday", metricsCollector.getTransfersToday()));
        details.put("security", Map.of(
                "score", securityScore,
                "openCritical", findingRepository.countByStatusAndSeverity("OPEN", "CRITICAL"),
                "openHigh", findingRepository.countByStatusAndSeverity("OPEN", "HIGH")));

        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }

        HealthScore score = HealthScore.builder()
                .overallScore(overall)
                .infrastructureScore(infraScore)
                .dataScore(dataScore)
                .securityScore(securityScore)
                .details(detailsJson)
                .build();

        healthScoreRepository.save(score);
        log.info("HealthScoreCalculator: overall={}, infra={}, data={}, security={}",
                overall, infraScore, dataScore, securityScore);
    }

    private int calculateInfrastructureScore() {
        int score = 100;

        // Service health: -5 per down service
        long totalServices = healthCollector.getTotalCount();
        long healthyServices = healthCollector.getHealthyCount();
        if (totalServices > 0) {
            long downServices = totalServices - healthyServices;
            score -= (int) (downServices * 5);
        }

        // DLQ penalty: -2 per 10 pending messages
        long dlq = dlqCollector.getPendingCount();
        score -= (int) (dlq / 10) * 2;

        return Math.max(0, Math.min(100, score));
    }

    private int calculateDataScore() {
        int score = 100;

        // Failure rate penalty
        double failureRate = metricsCollector.getFailureRate();
        if (failureRate > 20) score -= 30;
        else if (failureRate > 10) score -= 20;
        else if (failureRate > 5) score -= 10;

        // P95 latency penalty
        double p95 = metricsCollector.getP95Latency();
        if (p95 > 10000) score -= 20;
        else if (p95 > 5000) score -= 10;

        return Math.max(0, Math.min(100, score));
    }

    private int calculateSecurityScore() {
        int score = 100;

        // Open findings penalty
        long critical = findingRepository.countByStatusAndSeverity("OPEN", "CRITICAL");
        long high = findingRepository.countByStatusAndSeverity("OPEN", "HIGH");
        long medium = findingRepository.countByStatusAndSeverity("OPEN", "MEDIUM");

        score -= (int) (critical * 15);
        score -= (int) (high * 8);
        score -= (int) (medium * 3);

        return Math.max(0, Math.min(100, score));
    }
}
