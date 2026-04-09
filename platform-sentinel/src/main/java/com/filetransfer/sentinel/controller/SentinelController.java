package com.filetransfer.sentinel.controller;

import com.filetransfer.sentinel.analyzer.CorrelationEngine;
import com.filetransfer.sentinel.analyzer.HealthScoreCalculator;
import com.filetransfer.sentinel.analyzer.PerformanceAnalyzer;
import com.filetransfer.sentinel.analyzer.SecurityAnalyzer;
import com.filetransfer.sentinel.collector.CircuitBreakerCollector;
import com.filetransfer.sentinel.entity.CorrelationGroup;
import com.filetransfer.sentinel.entity.HealthScore;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.CorrelationGroupRepository;
import com.filetransfer.sentinel.repository.HealthScoreRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sentinel")
@RequiredArgsConstructor
public class SentinelController {

    private final SentinelFindingRepository findingRepository;
    private final SentinelRuleRepository ruleRepository;
    private final HealthScoreRepository healthScoreRepository;
    private final CorrelationGroupRepository correlationGroupRepository;
    private final SecurityAnalyzer securityAnalyzer;
    private final PerformanceAnalyzer performanceAnalyzer;
    private final CorrelationEngine correlationEngine;
    private final HealthScoreCalculator healthScoreCalculator;
    private final CircuitBreakerCollector circuitBreakerCollector;

    // --- Health Score ---

    @GetMapping("/health-score")
    public ResponseEntity<HealthScore> getHealthScore() {
        return healthScoreRepository.findTopByOrderByRecordedAtDesc()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok(HealthScore.builder()
                        .overallScore(100).infrastructureScore(100)
                        .dataScore(100).securityScore(100).build()));
    }

    @GetMapping("/health-score/history")
    public ResponseEntity<List<HealthScore>> getHealthScoreHistory(
            @RequestParam(defaultValue = "24") int hours) {
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        return ResponseEntity.ok(healthScoreRepository.findByRecordedAtAfterOrderByRecordedAtAsc(cutoff));
    }

    // --- Findings ---

    @GetMapping("/findings")
    public ResponseEntity<Page<SentinelFinding>> getFindings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String analyzer,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(findingRepository.search(status, severity, analyzer, service,
                PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/findings/{id}")
    public ResponseEntity<SentinelFinding> getFinding(@PathVariable UUID id) {
        return findingRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/findings/{id}/dismiss")
    public ResponseEntity<SentinelFinding> dismissFinding(@PathVariable UUID id) {
        return findingRepository.findById(id).map(f -> {
            f.setStatus("DISMISSED");
            f.setResolvedAt(Instant.now());
            return ResponseEntity.ok(findingRepository.save(f));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/findings/{id}/acknowledge")
    public ResponseEntity<SentinelFinding> acknowledgeFinding(@PathVariable UUID id) {
        return findingRepository.findById(id).map(f -> {
            f.setStatus("ACKNOWLEDGED");
            return ResponseEntity.ok(findingRepository.save(f));
        }).orElse(ResponseEntity.notFound().build());
    }

    // --- Correlations ---

    @GetMapping("/correlations")
    public ResponseEntity<List<Map<String, Object>>> getCorrelations() {
        List<CorrelationGroup> groups = correlationGroupRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (CorrelationGroup group : groups) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("group", group);
            entry.put("findings", findingRepository.findByCorrelationGroupId(group.getId()));
            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    // --- Rules ---

    @GetMapping("/rules")
    public ResponseEntity<List<SentinelRule>> getRules() {
        return ResponseEntity.ok(ruleRepository.findAll());
    }

    @PostMapping("/rules")
    public ResponseEntity<Object> createRule(@RequestBody SentinelRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (rule.getAnalyzer() == null || rule.getAnalyzer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "analyzer is required"));
        }
        if (ruleRepository.existsByName(rule.getName())) {
            return ResponseEntity.status(409).body(Map.of("error", "Rule name already exists: " + rule.getName()));
        }
        rule.setId(null);
        rule.setBuiltin(false);
        rule.setCreatedAt(Instant.now());
        rule.setLastTriggered(null);
        return ResponseEntity.status(201).body(ruleRepository.save(rule));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<SentinelRule> updateRule(@PathVariable UUID id, @RequestBody SentinelRule update) {
        return ruleRepository.findById(id).map(rule -> {
            if (update.getEnabled() != null) rule.setEnabled(update.getEnabled());
            if (update.getThresholdValue() != null) rule.setThresholdValue(update.getThresholdValue());
            if (update.getWindowMinutes() != null) rule.setWindowMinutes(update.getWindowMinutes());
            if (update.getCooldownMinutes() != null) rule.setCooldownMinutes(update.getCooldownMinutes());
            if (update.getSeverity() != null) rule.setSeverity(update.getSeverity());
            if (update.getDescription() != null) rule.setDescription(update.getDescription());
            return ResponseEntity.ok(ruleRepository.save(rule));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Object> deleteRule(@PathVariable UUID id) {
        return ruleRepository.findById(id).map(rule -> {
            if (Boolean.TRUE.equals(rule.getBuiltin())) {
                return ResponseEntity.status(409).<Object>body(
                        Map.of("error", "Cannot delete built-in rule '" + rule.getName() + "'. Disable it instead."));
            }
            ruleRepository.delete(rule);
            return ResponseEntity.noContent().<Object>build();
        }).orElse(ResponseEntity.notFound().<Object>build());
    }

    // --- Dashboard ---

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        // Health score
        dashboard.put("healthScore", healthScoreRepository.findTopByOrderByRecordedAtDesc().orElse(null));

        // Finding counts by severity
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            bySeverity.put(sev, findingRepository.countByStatusAndSeverity("OPEN", sev));
        }
        dashboard.put("openBySeverity", bySeverity);

        // Total counts
        dashboard.put("totalOpen", findingRepository.countByStatus("OPEN"));
        dashboard.put("totalToday", findingRepository.countByCreatedAtAfter(
                Instant.now().truncatedTo(ChronoUnit.DAYS)));

        // Recent findings
        dashboard.put("recentFindings", findingRepository.findTop10ByOrderByCreatedAtDesc());

        // Correlation groups count
        dashboard.put("correlationGroups", correlationGroupRepository.count());

        return ResponseEntity.ok(dashboard);
    }

    // --- Manual trigger ---

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> triggerAnalysis() {
        new Thread(() -> {
            securityAnalyzer.analyze();
            performanceAnalyzer.analyze();
            correlationEngine.correlate();
            healthScoreCalculator.calculate();
        }).start();
        return ResponseEntity.ok(Map.of("status", "Analysis triggered"));
    }

    // --- Circuit Breakers ---

    @GetMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> getCircuitBreakers() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("circuitBreakers", circuitBreakerCollector.getAllCircuitBreakers());
        result.put("totalCount", circuitBreakerCollector.getTotalCount());
        result.put("closedCount", circuitBreakerCollector.getClosedCount());
        result.put("openCount", circuitBreakerCollector.getOpenCount());
        result.put("halfOpenCount", circuitBreakerCollector.getHalfOpenCount());
        result.put("unknownCount", circuitBreakerCollector.getUnknownCount());
        return ResponseEntity.ok(result);
    }

    // --- Health ---

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "platform-sentinel",
                "port", 8098,
                "totalRules", ruleRepository.count(),
                "openFindings", findingRepository.countByStatus("OPEN")
        ));
    }
}
