package com.filetransfer.ai.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Scans file content to detect sensitive data types.
 * Uses regex patterns (no LLM needed) for deterministic classification.
 *
 * Detected categories:
 * - PCI: Credit card numbers (Visa, MC, Amex, Discover)
 * - PII: SSN, email addresses, phone numbers, passport numbers
 * - PHI: Medical record numbers, diagnosis codes (ICD-10)
 * - FINANCIAL: Bank account/routing numbers, SWIFT/BIC codes
 */
@Service
@Slf4j
public class DataClassificationService {

    @Value("${ai.classification.max-scan-size-mb:100}")
    private int maxScanSizeMb;

    @Value("${ai.classification.block-unencrypted-pci:true}")
    private boolean blockUnencryptedPci;

    private static final Map<String, List<PatternDef>> PATTERNS = Map.of(
        "PCI", List.of(
            new PatternDef("CREDIT_CARD_VISA", "\\b4[0-9]{12}(?:[0-9]{3})?\\b"),
            new PatternDef("CREDIT_CARD_MC", "\\b5[1-5][0-9]{14}\\b"),
            new PatternDef("CREDIT_CARD_AMEX", "\\b3[47][0-9]{13}\\b"),
            new PatternDef("CREDIT_CARD_DISCOVER", "\\b6(?:011|5[0-9]{2})[0-9]{12}\\b"),
            new PatternDef("CREDIT_CARD_GENERIC", "\\b(?:4[0-9]{15}|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b")
        ),
        "PII", List.of(
            new PatternDef("SSN", "\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            new PatternDef("SSN_NODASH", "\\b(?!000|666|9\\d{2})\\d{3}(?!00)\\d{2}(?!0000)\\d{4}\\b"),
            new PatternDef("EMAIL", "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            new PatternDef("PHONE_US", "\\b(?:\\+1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b"),
            new PatternDef("PASSPORT", "\\b[A-Z]{1,2}\\d{6,9}\\b")
        ),
        "PHI", List.of(
            new PatternDef("ICD10_CODE", "\\b[A-Z]\\d{2}\\.\\d{1,4}\\b"),
            new PatternDef("MRN", "\\bMRN[:\\s]*\\d{6,10}\\b")
        ),
        "FINANCIAL", List.of(
            new PatternDef("ROUTING_NUMBER", "\\b\\d{9}\\b"),
            new PatternDef("SWIFT_BIC", "\\b[A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b"),
            new PatternDef("IBAN", "\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}(?:[A-Z0-9]{0,16})\\b")
        )
    );

    public ClassificationResult classify(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            if (fileSize > maxScanSizeMb * 1024L * 1024L) {
                return ClassificationResult.builder()
                        .filename(filePath.getFileName().toString())
                        .scanned(false)
                        .note("File too large for scanning (" + (fileSize / 1024 / 1024) + "MB)")
                        .build();
            }

            // Read file content (text-based scanning)
            String content;
            try {
                content = Files.readString(filePath);
            } catch (Exception e) {
                // Binary file — scan first 10KB for embedded text
                byte[] bytes = new byte[(int) Math.min(fileSize, 10240)];
                try (InputStream is = Files.newInputStream(filePath)) { is.read(bytes); }
                content = new String(bytes);
            }

            List<Detection> detections = new ArrayList<>();
            Map<String, Integer> categoryCounts = new LinkedHashMap<>();
            String highestRisk = "NONE";
            int riskScore = 0;

            for (Map.Entry<String, List<PatternDef>> entry : PATTERNS.entrySet()) {
                String category = entry.getKey();
                for (PatternDef pd : entry.getValue()) {
                    Matcher m = Pattern.compile(pd.regex).matcher(content);
                    int count = 0;
                    String firstMatch = null;
                    while (m.find()) {
                        // Filter routing number false positives via ABA checksum
                        if ("ROUTING_NUMBER".equals(pd.name) && !isValidRoutingNumber(m.group())) {
                            continue;
                        }
                        count++;
                        if (firstMatch == null) firstMatch = maskSensitive(m.group(), pd.name);
                    }
                    if (count > 0) {
                        detections.add(Detection.builder()
                                .category(category).type(pd.name)
                                .count(count).sample(firstMatch).build());
                        categoryCounts.merge(category, count, Integer::sum);
                    }
                }
            }

            // Risk scoring
            if (categoryCounts.containsKey("PCI")) { highestRisk = "CRITICAL"; riskScore = Math.min(100, 70 + categoryCounts.get("PCI") * 5); }
            else if (categoryCounts.containsKey("PHI")) { highestRisk = "HIGH"; riskScore = Math.min(100, 60 + categoryCounts.get("PHI") * 5); }
            else if (categoryCounts.containsKey("PII")) { highestRisk = "MEDIUM"; riskScore = Math.min(100, 40 + categoryCounts.get("PII") * 3); }
            else if (categoryCounts.containsKey("FINANCIAL")) { highestRisk = "MEDIUM"; riskScore = Math.min(100, 30 + categoryCounts.get("FINANCIAL") * 3); }

            boolean requiresEncryption = riskScore >= 50;
            boolean blocked = blockUnencryptedPci && categoryCounts.containsKey("PCI");

            log.info("Classification: {} — risk={} score={} detections={} blocked={}",
                    filePath.getFileName(), highestRisk, riskScore, detections.size(), blocked);

            return ClassificationResult.builder()
                    .filename(filePath.getFileName().toString())
                    .scanned(true)
                    .riskLevel(highestRisk)
                    .riskScore(riskScore)
                    .detections(detections)
                    .categoryCounts(categoryCounts)
                    .requiresEncryption(requiresEncryption)
                    .blocked(blocked)
                    .blockReason(blocked ? "PCI data detected — encryption required before transfer" : null)
                    .build();
        } catch (Exception e) {
            log.error("Classification failed for {}: {}", filePath, e.getMessage());
            return ClassificationResult.builder().filename(filePath.getFileName().toString())
                    .scanned(false).note("Scan error: " + e.getMessage()).build();
        }
    }

    /**
     * Validates an ABA routing number using the checksum algorithm:
     * (3*(d1+d4+d7) + 7*(d2+d5+d8) + (d3+d6+d9)) % 10 == 0
     */
    private boolean isValidRoutingNumber(String candidate) {
        if (candidate.length() != 9) return false;
        try {
            int[] d = new int[9];
            for (int i = 0; i < 9; i++) d[i] = candidate.charAt(i) - '0';
            int checksum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5] + d[8]);
            return checksum % 10 == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String maskSensitive(String value, String type) {
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ClassificationResult {
        private String filename;
        private boolean scanned;
        private String riskLevel;
        private int riskScore;
        private List<Detection> detections;
        private Map<String, Integer> categoryCounts;
        private boolean requiresEncryption;
        private boolean blocked;
        private String blockReason;
        private String note;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Detection {
        private String category;
        private String type;
        private int count;
        private String sample; // masked
    }

    private record PatternDef(String name, String regex) {}
}
