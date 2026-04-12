package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.*;
import com.filetransfer.ai.service.phase3.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final DataClassificationService classificationService;
    private final AnomalyDetectionService anomalyService;
    private final SmartRetryService smartRetryService;
    private final ContentValidationService contentValidationService;
    private final ThreatScoringService threatScoringService;
    private final PartnerProfileService partnerProfileService;
    private final PredictiveSlaService slaService;
    private final AutoRemediationService autoRemediationService;
    private final NaturalLanguageMonitoringService nlMonitoringService;
    private final FileFormatDetector fileFormatDetector;
    private final ObservabilityAnalyzer observabilityAnalyzer;
    private final SelfDrivingInfraService selfDrivingService;
    private final IntelligenceNetworkService intelligenceService;
    private final NlpService nlpService;
    private final CommandOrchestrator commandOrchestrator;

    // === Data Classification ===

    @PostMapping("/classify")
    public ResponseEntity<DataClassificationService.ClassificationResult> classifyFile(
            @RequestPart("file") MultipartFile file) throws Exception {
        Path tempFile = Files.createTempFile("classify_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            return ResponseEntity.ok(classificationService.classify(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @PostMapping("/classify/text")
    public ResponseEntity<DataClassificationService.ClassificationResult> classifyText(
            @RequestBody Map<String, String> body) throws Exception {
        String content = body.get("content");
        String filename = body.getOrDefault("filename", "inline-text.txt");
        Path tempFile = Files.createTempFile("classify_", ".txt");
        Files.writeString(tempFile, content);
        try {
            return ResponseEntity.ok(classificationService.classify(tempFile));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // === Anomaly Detection ===

    @GetMapping("/anomalies")
    public List<AnomalyDetectionService.Anomaly> getAnomalies() {
        return anomalyService.getActiveAnomalies();
    }

    // === NLP / Natural Language ===

    @PostMapping("/nlp/command")
    public ResponseEntity<NlpService.NlpResult> translateCommand(
            @RequestBody Map<String, String> body) {
        String query = body.get("query");
        String context = body.get("context");
        return ResponseEntity.ok(nlpService.translateToCommand(query, context));
    }

    @PostMapping("/nlp/suggest-flow")
    public ResponseEntity<NlpService.FlowSuggestion> suggestFlow(
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(nlpService.suggestFlow(body.get("description")));
    }

    @PostMapping("/nlp/explain")
    public ResponseEntity<Map<String, String>> explainEvent(
            @RequestBody Map<String, Object> body) {
        String event = (String) body.get("event");
        return ResponseEntity.ok(Map.of("explanation",
                nlpService.explainEvent(event, body)));
    }

    // === Command Orchestration (one sentence → multiple API calls) ===

    @PostMapping("/orchestrate")
    public ResponseEntity<CommandOrchestrator.ExecutionResult> orchestrate(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> planMaps = (List<Map<String, String>>) body.get("plan");
        String baseUrl = (String) body.getOrDefault("baseUrl", "https://onboarding-api:9080");

        List<CommandOrchestrator.PlanStep> plan = planMaps.stream()
                .map(m -> new CommandOrchestrator.PlanStep(
                        m.getOrDefault("method", "POST"),
                        m.get("path"),
                        m.get("body"),
                        m.getOrDefault("description", "Step")))
                .toList();

        return ResponseEntity.ok(commandOrchestrator.execute(plan, baseUrl, authHeader));
    }

    // === Risk Score ===

    @PostMapping("/risk-score")
    public ResponseEntity<Map<String, Object>> computeRiskScore(@RequestBody Map<String, Object> body) {
        int score = 0;
        List<String> factors = new java.util.ArrayList<>();

        // Factor: new IP
        if (Boolean.TRUE.equals(body.get("newIp"))) { score += 30; factors.add("Login from new IP (+30)"); }
        // Factor: unusual hour
        if (Boolean.TRUE.equals(body.get("unusualHour"))) { score += 20; factors.add("Activity at unusual hour (+20)"); }
        // Factor: large file
        if (body.get("fileSizeMb") instanceof Number n && n.doubleValue() > 100) { score += 15; factors.add("Large file >100MB (+15)"); }
        // Factor: sensitive data
        if (Boolean.TRUE.equals(body.get("containsPci"))) { score += 25; factors.add("Contains PCI data (+25)"); }
        if (Boolean.TRUE.equals(body.get("containsPii"))) { score += 15; factors.add("Contains PII data (+15)"); }

        String level = score >= 80 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
        String action = score >= 80 ? "BLOCK" : score >= 50 ? "REVIEW" : "ALLOW";

        return ResponseEntity.ok(Map.of(
                "score", score, "level", level, "action", action, "factors", factors
        ));
    }

    // === Phase 2: Smart Retry ===

    @PostMapping("/smart-retry")
    public ResponseEntity<SmartRetryService.RetryDecision> classifyFailure(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(smartRetryService.classify(
                (String) body.get("errorMessage"),
                (String) body.get("filename"),
                body.get("retryCount") instanceof Number n ? n.intValue() : 0));
    }

    // === Phase 2: Content Validation ===

    @PostMapping("/validate")
    public ResponseEntity<ContentValidationService.ValidationResult> validateFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String expectedColumns) throws Exception {
        Path tempFile = Files.createTempFile("validate_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            Map<String, Object> schema = expectedColumns != null ?
                    Map.of("expectedColumns", java.util.Arrays.asList(expectedColumns.split(","))) : null;
            return ResponseEntity.ok(contentValidationService.validate(tempFile, schema));
        } finally { Files.deleteIfExists(tempFile); }
    }

    // === Phase 2: Threat Scoring ===

    @PostMapping("/threat-score")
    public ResponseEntity<ThreatScoringService.ThreatScore> threatScore(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(threatScoringService.score(
                (String) body.getOrDefault("username", "unknown"),
                (String) body.get("ipAddress"),
                (String) body.get("action"),
                (String) body.get("filename"),
                body.get("fileSizeBytes") instanceof Number n ? n.longValue() : null,
                java.time.Instant.now()));
    }

    // === Phase 3: Partner Profiles ===

    @GetMapping("/partners")
    public List<PartnerProfileService.PartnerProfile> getPartnerProfiles() {
        return partnerProfileService.getAllProfiles();
    }

    @GetMapping("/partners/{username}")
    public ResponseEntity<?> getPartnerProfile(@PathVariable String username) {
        var profile = partnerProfileService.getProfile(username);
        return profile != null ? ResponseEntity.ok(profile) : ResponseEntity.notFound().build();
    }

    @PostMapping("/partners/{username}/check")
    public List<String> checkDeviations(@PathVariable String username, @RequestBody Map<String, Object> body) {
        return partnerProfileService.checkDeviations(username,
                (String) body.get("filename"),
                body.get("fileSize") instanceof Number n ? n.longValue() : null,
                java.time.Instant.now());
    }

    // === Phase 3: SLA Forecasts ===

    @GetMapping("/sla/forecasts")
    public List<PredictiveSlaService.SlaForecast> getSlaForecasts() {
        return slaService.getForecasts();
    }

    // === Phase 3: Auto-Remediation ===

    @GetMapping("/remediation/actions")
    public List<AutoRemediationService.RemediationAction> getRemediationActions() {
        return autoRemediationService.getRecentActions();
    }

    // === Phase 3: Natural Language Monitoring ===

    @PostMapping("/ask")
    public ResponseEntity<Map<String, String>> ask(@RequestBody Map<String, String> body) {
        String answer = nlMonitoringService.answer(body.get("question"));
        return ResponseEntity.ok(Map.of("answer", answer));
    }

    // === Phase 3: File Format Detection ===

    @PostMapping("/detect-format")
    public ResponseEntity<FileFormatDetector.FormatResult> detectFormat(
            @RequestPart("file") MultipartFile file) throws Exception {
        Path tempFile = Files.createTempFile("detect_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            return ResponseEntity.ok(fileFormatDetector.detect(tempFile));
        } finally { Files.deleteIfExists(tempFile); }
    }

    // === Observability Recommendations ===

    @GetMapping("/recommendations")
    public List<ObservabilityAnalyzer.Recommendation> getRecommendations(
            @RequestParam(required = false) String category) {
        if (category != null) return observabilityAnalyzer.getRecommendationsByCategory(category);
        return observabilityAnalyzer.getRecommendations();
    }

    @GetMapping("/recommendations/summary")
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> result = new java.util.LinkedHashMap<>(observabilityAnalyzer.getHealthSummary());
        result.put("recommendations", observabilityAnalyzer.getRecommendations().size());
        result.put("lastAnalysis", observabilityAnalyzer.getLastAnalysis() != null
                ? observabilityAnalyzer.getLastAnalysis().toString() : "pending");
        return result;
    }

    // === Breakthrough: Self-Driving Infrastructure ===

    @GetMapping("/self-driving/actions")
    public List<SelfDrivingInfraService.InfraAction> selfDrivingActions() {
        return selfDrivingService.getActions();
    }

    @GetMapping("/self-driving/status")
    public Map<String, Object> selfDrivingStatus() {
        return selfDrivingService.getAutonomyStatus();
    }

    // === Breakthrough: Intelligence Network ===

    @GetMapping("/intelligence/signals")
    public List<IntelligenceNetworkService.ThreatSignal> intelligenceSignals() {
        return intelligenceService.getActiveSignals();
    }

    @GetMapping("/intelligence/status")
    public Map<String, Object> intelligenceStatus() {
        return intelligenceService.getNetworkStatus();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "ai-engine",
                "version", "3.0.0-phase3",
                "features", new java.util.LinkedHashMap<>(Map.of(
                        "classification", true, "anomalyDetection", true,
                        "nlp", true, "riskScoring", true,
                        "smartRetry", true, "contentValidation", true,
                        "threatScoring", true,
                        "partnerProfiles", true, "predictiveSla", true,
                        "autoRemediation", true
                )) {{ put("nlMonitoring", true); put("formatDetection", true); }});
    }
}
