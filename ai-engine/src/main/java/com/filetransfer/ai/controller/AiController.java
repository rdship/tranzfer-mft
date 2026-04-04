package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.AnomalyDetectionService;
import com.filetransfer.ai.service.DataClassificationService;
import com.filetransfer.ai.service.NlpService;
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

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "ai-engine",
                "features", Map.of(
                        "classification", true,
                        "anomalyDetection", true,
                        "nlp", true,
                        "riskScoring", true
                ));
    }
}
