package com.filetransfer.screening.service;

import com.filetransfer.shared.entity.security.DlpPolicy;
import com.filetransfer.shared.repository.DlpPolicyRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data Loss Prevention engine.
 *
 * Scans text-based files for sensitive data patterns:
 * - Credit card numbers (with Luhn validation)
 * - SSN patterns
 * - Email addresses
 * - Phone numbers (international)
 * - IBAN bank account numbers
 * - Custom regex patterns from configurable DLP policies
 *
 * Binary files are skipped (DLP only applies to human-readable content).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlpEngine {

    private final DlpPolicyRepository policyRepository;

    /** File extensions that are scanned as text */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".csv", ".tsv", ".txt", ".json", ".xml", ".html", ".htm",
            ".yaml", ".yml", ".log", ".md", ".ini", ".cfg", ".properties",
            ".sql", ".edi", ".x12", ".edifact"
    );

    /**
     * Scan a file for sensitive data using all active DLP policies.
     *
     * @param filePath path to the file to scan
     * @param trackId  tracking ID for logging
     * @return DLP scan result with findings and recommended action
     */
    public DlpScanResult scanFile(Path filePath, String trackId) {
        long start = System.currentTimeMillis();
        String filename = filePath.getFileName().toString();

        // Only scan text files
        if (!isTextFile(filename)) {
            log.debug("[{}] DLP: Skipping binary file {}", trackId, filename);
            return DlpScanResult.builder()
                    .hasSensitiveData(false)
                    .findings(List.of())
                    .action("PASS")
                    .scanTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        List<DlpPolicy> activePolicies = policyRepository.findByActiveTrueOrderByCreatedAtDesc();
        if (activePolicies.isEmpty()) {
            log.debug("[{}] DLP: No active policies configured", trackId);
            return DlpScanResult.builder()
                    .hasSensitiveData(false)
                    .findings(List.of())
                    .action("PASS")
                    .scanTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        try {
            List<String> lines = Files.readAllLines(filePath);
            List<DlpFinding> allFindings = new ArrayList<>();
            String highestAction = "PASS"; // PASS < LOG < FLAG < BLOCK

            for (DlpPolicy policy : activePolicies) {
                if (policy.getPatterns() == null) continue;

                for (DlpPolicy.PatternDefinition patternDef : policy.getPatterns()) {
                    try {
                        Pattern regex = Pattern.compile(patternDef.getRegex());

                        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
                            String line = lines.get(lineIdx);
                            Matcher matcher = regex.matcher(line);

                            while (matcher.find()) {
                                String matched = matcher.group();

                                // Extra validation for credit cards (Luhn check)
                                if ("PCI_CREDIT_CARD".equals(patternDef.getType())) {
                                    String digits = matched.replaceAll("\\D", "");
                                    if (!luhnCheck(digits)) continue;
                                }

                                allFindings.add(DlpFinding.builder()
                                        .type(patternDef.getType())
                                        .label(patternDef.getLabel())
                                        .policyName(policy.getName())
                                        .lineNumber(lineIdx + 1)
                                        .columnNumber(matcher.start() + 1)
                                        .maskedValue(maskValue(matched, patternDef.getType()))
                                        .build());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[{}] DLP: Invalid regex in policy '{}': {}",
                                trackId, policy.getName(), e.getMessage());
                    }
                }

                // Track the most restrictive action across all policies with findings
                if (!allFindings.isEmpty()) {
                    highestAction = moreRestrictive(highestAction, policy.getAction());
                }
            }

            long scanTimeMs = System.currentTimeMillis() - start;

            if (!allFindings.isEmpty()) {
                log.info("[{}] DLP: {} findings in {} (action: {}, {}ms)",
                        trackId, allFindings.size(), filename, highestAction, scanTimeMs);
            }

            return DlpScanResult.builder()
                    .hasSensitiveData(!allFindings.isEmpty())
                    .findings(allFindings)
                    .action(allFindings.isEmpty() ? "PASS" : highestAction)
                    .scanTimeMs(scanTimeMs)
                    .build();

        } catch (IOException e) {
            log.error("[{}] DLP: Failed to read file {}: {}", trackId, filename, e.getMessage());
            return DlpScanResult.builder()
                    .hasSensitiveData(false)
                    .findings(List.of())
                    .action("PASS")
                    .error("Failed to read file: " + e.getMessage())
                    .scanTimeMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private boolean isTextFile(String filename) {
        String lower = filename.toLowerCase();
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Luhn algorithm for credit card number validation.
     */
    static boolean luhnCheck(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /**
     * Mask sensitive values for safe logging/display.
     * Shows first and last 2 characters with asterisks in between.
     */
    private String maskValue(String value, String type) {
        if (value == null || value.length() < 4) return "****";

        return switch (type) {
            case "PCI_CREDIT_CARD" -> {
                String digits = value.replaceAll("\\D", "");
                yield "****-****-****-" + digits.substring(Math.max(0, digits.length() - 4));
            }
            case "PII_SSN" -> "***-**-" + value.substring(Math.max(0, value.length() - 4));
            case "PII_EMAIL" -> {
                int at = value.indexOf('@');
                if (at > 0) {
                    yield value.charAt(0) + "***@" + value.substring(at + 1);
                }
                yield "***@***";
            }
            default -> value.substring(0, 2) + "***" + value.substring(value.length() - 2);
        };
    }

    private String moreRestrictive(String current, String incoming) {
        Map<String, Integer> priority = Map.of("PASS", 0, "LOG", 1, "FLAG", 2, "BLOCK", 3);
        int currentPri = priority.getOrDefault(current, 0);
        int incomingPri = priority.getOrDefault(incoming, 0);
        return incomingPri > currentPri ? incoming : current;
    }

    // === DTOs ===

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DlpScanResult {
        private boolean hasSensitiveData;
        private List<DlpFinding> findings;
        /** Recommended action: PASS, LOG, FLAG, BLOCK */
        private String action;
        private long scanTimeMs;
        private String error;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DlpFinding {
        /** PII_SSN, PCI_CREDIT_CARD, PII_EMAIL, PII_PHONE, PCI_IBAN, CUSTOM */
        private String type;
        /** Human-readable label */
        private String label;
        /** Policy that triggered this finding */
        private String policyName;
        private int lineNumber;
        private int columnNumber;
        /** Masked version of the sensitive value */
        private String maskedValue;
    }
}
