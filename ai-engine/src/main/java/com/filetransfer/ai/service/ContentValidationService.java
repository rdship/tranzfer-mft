package com.filetransfer.ai.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * AI Phase 2: File content validation and schema drift detection.
 * Validates CSV structure, detects missing columns, encoding issues.
 */
@Service
@Slf4j
public class ContentValidationService {

    /**
     * Validate a file's content structure.
     */
    public ValidationResult validate(Path filePath, Map<String, Object> expectedSchema) {
        String filename = filePath.getFileName().toString();
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            long fileSize = Files.size(filePath);

            // Check for empty file
            if (fileSize == 0) {
                issues.add("File is empty (0 bytes)");
                return result(filename, false, issues, warnings);
            }

            // Check for truncated file (suspiciously small)
            if (expectedSchema != null && expectedSchema.containsKey("minSizeBytes")) {
                long minSize = ((Number) expectedSchema.get("minSizeBytes")).longValue();
                if (fileSize < minSize) {
                    issues.add(String.format("File appears truncated (%d bytes, expected >= %d)", fileSize, minSize));
                }
            }

            // CSV validation
            if (filename.endsWith(".csv") || filename.endsWith(".tsv")) {
                validateCsv(filePath, expectedSchema, issues, warnings);
            }

            // Encoding detection
            byte[] head = new byte[(int) Math.min(fileSize, 4096)];
            try (InputStream is = Files.newInputStream(filePath)) { is.read(head); }
            String encoding = detectEncoding(head);
            if (!"UTF-8".equals(encoding)) {
                warnings.add("File encoding detected as " + encoding + " (expected UTF-8)");
            }

        } catch (Exception e) {
            issues.add("Validation error: " + e.getMessage());
        }

        return result(filename, issues.isEmpty(), issues, warnings);
    }

    private void validateCsv(Path filePath, Map<String, Object> schema,
                              List<String> issues, List<String> warnings) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        if (lines.isEmpty()) { issues.add("CSV has no rows"); return; }

        String[] headers = lines.get(0).split(",");
        int columnCount = headers.length;

        // Check expected columns
        if (schema != null && schema.containsKey("expectedColumns")) {
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) schema.get("expectedColumns");
            Set<String> actualSet = new HashSet<>(Arrays.asList(headers));
            for (String col : expected) {
                if (!actualSet.contains(col.trim())) {
                    issues.add("Missing expected column: '" + col + "'");
                }
            }
            // Detect new columns (schema drift)
            Set<String> expectedSet = new HashSet<>(expected);
            for (String h : headers) {
                if (!expectedSet.contains(h.trim())) {
                    warnings.add("New column detected (schema drift): '" + h.trim() + "'");
                }
            }
        }

        // Row consistency check
        int inconsistentRows = 0;
        for (int i = 1; i < lines.size(); i++) {
            int cols = lines.get(i).split(",", -1).length;
            if (cols != columnCount) inconsistentRows++;
        }
        if (inconsistentRows > 0) {
            issues.add(inconsistentRows + " rows have inconsistent column count (expected " + columnCount + ")");
        }

        // Row count
        if (schema != null && schema.containsKey("minRows")) {
            int minRows = ((Number) schema.get("minRows")).intValue();
            if (lines.size() - 1 < minRows) {
                warnings.add(String.format("Only %d data rows (expected >= %d)", lines.size() - 1, minRows));
            }
        }
    }

    private String detectEncoding(byte[] head) {
        // BOM detection
        if (head.length >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF)
            return "UTF-8-BOM";
        if (head.length >= 2 && head[0] == (byte) 0xFF && head[1] == (byte) 0xFE)
            return "UTF-16-LE";
        if (head.length >= 2 && head[0] == (byte) 0xFE && head[1] == (byte) 0xFF)
            return "UTF-16-BE";
        // Check for non-UTF-8 bytes
        for (byte b : head) {
            if (b < 0 && (b & 0xFF) > 0x7F) {
                // Could be Latin-1 or other encoding
                return "ISO-8859-1";
            }
        }
        return "UTF-8";
    }

    private ValidationResult result(String filename, boolean valid, List<String> issues, List<String> warnings) {
        return ValidationResult.builder()
                .filename(filename).valid(valid)
                .issues(issues).warnings(warnings)
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ValidationResult {
        private String filename;
        private boolean valid;
        private List<String> issues;
        private List<String> warnings;
    }
}
