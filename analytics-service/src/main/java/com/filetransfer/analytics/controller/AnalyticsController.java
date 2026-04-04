package com.filetransfer.analytics.controller;

import com.filetransfer.analytics.dto.*;
import com.filetransfer.analytics.entity.AlertRule;
import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.AlertRuleRepository;
import com.filetransfer.analytics.service.DashboardService;
import com.filetransfer.analytics.service.MetricsAggregationService;
import com.filetransfer.analytics.service.PredictionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final DashboardService dashboardService;
    private final PredictionService predictionService;
    private final MetricsAggregationService aggregationService;
    private final AlertRuleRepository alertRuleRepository;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSummary> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboardSummary());
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
    public ResponseEntity<AlertRule> createAlertRule(@Valid @RequestBody AlertRule rule) {
        rule.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(alertRuleRepository.save(rule));
    }

    @PutMapping("/alerts/{id}")
    public ResponseEntity<AlertRule> updateAlertRule(@PathVariable UUID id, @RequestBody AlertRule rule) {
        if (!alertRuleRepository.existsById(id)) return ResponseEntity.notFound().build();
        rule.setId(id);
        return ResponseEntity.ok(alertRuleRepository.save(rule));
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlertRule(@PathVariable UUID id) {
        alertRuleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
