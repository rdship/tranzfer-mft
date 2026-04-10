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

    private String detectEncoding(byte[] data) {
        // Check BOM first
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF)
            return "UTF-8";
        if (data.length >= 2 && data[0] == (byte) 0xFE && data[1] == (byte) 0xFF)
            return "UTF-16BE";
        if (data.length >= 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xFE)
            return "UTF-16LE";

        // Try to decode as UTF-8 — if it succeeds without errors, it's UTF-8
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(data));
            return "UTF-8";
        } catch (Exception e) {
            // Not valid UTF-8 — likely ISO-8859-1 or Windows-1252
            return "ISO-8859-1";
        }
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
