package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.*;
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
    private final NlpService nlpService;

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

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "ai-engine",
                "version", "2.1.0-phase2",
                "features", Map.of(
                        "classification", true, "anomalyDetection", true,
                        "nlp", true, "riskScoring", true,
                        "smartRetry", true, "contentValidation", true,
                        "threatScoring", true
                ));
    }
}
