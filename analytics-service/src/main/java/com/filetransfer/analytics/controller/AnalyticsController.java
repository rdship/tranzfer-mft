package com.filetransfer.analytics.controller;

import com.filetransfer.analytics.dto.*;
import com.filetransfer.analytics.entity.AlertRule;
import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.AlertRuleRepository;
import com.filetransfer.analytics.service.DashboardService;
import com.filetransfer.analytics.service.DedupStatsService;
import com.filetransfer.analytics.service.MetricsAggregationService;
import com.filetransfer.analytics.service.PredictionService;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
public class AnalyticsController {

    private final DashboardService dashboardService;
    private final PredictionService predictionService;
    private final MetricsAggregationService aggregationService;
    private final AlertRuleRepository alertRuleRepository;
    private final DedupStatsService dedupStatsService;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSummary> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboardSummary());
    }

    /**
     * Lightweight reachability probe for the UI sidebar's liveness poll.
     * Matches the platform-wide {@code /api/*\/health} permitAll rule so
     * the sidebar doesn't 403-spam when polling every 60 s.
     */
    @GetMapping("/health")
    @PreAuthorize("permitAll()")
    public java.util.Map<String, Object> health() {
        return java.util.Map.of("status", "UP", "service", "analytics-service");
    }

    @GetMapping("/predictions")
    public ResponseEntity<List<ScalingRecommendation>> getAllPredictions() {
        return ResponseEntity.ok(predictionService.predictAll());
    }

    @GetMapping("/predictions/{serviceType}")
    public ResponseEntity<ScalingRecommendation> getPrediction(@PathVariable String serviceType) {
        return ResponseEntity.ok(predictionService.predictForService(serviceType.toUpperCase()));
    }

    @GetMapping("/timeseries")
    public ResponseEntity<List<MetricSnapshot>> getTimeSeries(
            @RequestParam(defaultValue = "ALL") String service,
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(aggregationService.getSnapshots(service, hours));
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<AlertRule>> getAlertRules() {
        return ResponseEntity.ok(alertRuleRepository.findAll());
    }

    @PostMapping("/alerts")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<AlertRule> createAlertRule(@Valid @RequestBody AlertRule rule) {
        rule.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(alertRuleRepository.save(rule));
    }

    @PutMapping("/alerts/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<AlertRule> updateAlertRule(@PathVariable UUID id, @RequestBody AlertRule rule) {
        if (!alertRuleRepository.existsById(id)) return ResponseEntity.notFound().build();
        rule.setId(id);
        return ResponseEntity.ok(alertRuleRepository.save(rule));
    }

    @DeleteMapping("/alerts/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Void> deleteAlertRule(@PathVariable UUID id) {
        alertRuleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** CAS deduplication savings — storage saved by SHA-256 content-addressed dedup. */
    @GetMapping("/dedup-stats")
    public ResponseEntity<java.util.Map<String, Object>> getDedupStats() {
        return ResponseEntity.ok(dedupStatsService.getDedupStats());
    }
}
