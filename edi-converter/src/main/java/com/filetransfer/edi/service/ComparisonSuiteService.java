package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compare Suite — batch comparison of conversion outputs between two systems.
 *
 * Partners migrating from System A (old) to System B (new/our platform) provide:
 *   - Source input dir:  original EDI files processed by System A
 *   - Source output dir: System A's conversion outputs
 *   - Target input dir:  original EDI files processed by System B (often same as source input)
 *   - Target output dir: System B's conversion outputs
 *
 * The engine:
 *   1. Scans directories, matches files by name
 *   2. Shows a preview for user confirmation
 *   3. Runs field-level semantic diffs on every matched pair
 *   4. Aggregates results per map/format, identifies patterns
 *   5. Generates an intuitive summary report with fix recommendations
 *   6. Optionally writes the report to a user-specified path
 */
@Service @RequiredArgsConstructor @Slf4j
public class ComparisonSuiteService {

    private final UniversalEdiParser parser;
    private final FormatDetector formatDetector;
    private final SemanticDiffEngine diffEngine;
    private final ObjectMapper objectMapper;

    /** In-memory session store (stateless service — no DB) */
    private final ConcurrentHashMap<UUID, CompareSession> sessions = new ConcurrentHashMap<>();
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompareRequest {
        private String sourceInputPath;      // Dir with original EDI files (System A input)
        private String sourceOutputPath;     // Dir with System A conversion outputs
        private String targetInputPath;      // Dir with original EDI files (System B input)
        private String targetOutputPath;     // Dir with System B conversion outputs
        private String reportOutputPath;     // Optional: where to save the report file
        private String mappingFilePath;      // Optional: CSV file with explicit file pair mappings
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CsvMappingEntry {
        private String sourceInputFile;
        private String sourceOutputFile;
        private String targetInputFile;
        private String targetOutputFile;
        private String mapLabel;             // Optional: label for grouping
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ComparePreview {
        private UUID sessionId;
        private int totalSourceFiles;
        private int totalTargetFiles;
        private int matchedPairs;
        private List<FilePair> filePairs;
        private List<String> unmatchedSource;
        private List<String> unmatchedTarget;
        private String status;               // READY, NEEDS_MAPPING, ERROR
        private String message;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FilePair {
        private String sourceInputFile;
        private String sourceOutputFile;
        private String targetInputFile;
        private String targetOutputFile;
        private String detectedFormat;
        private long sourceOutputSize;
        private long targetOutputSize;
        private String mapLabel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompareReport {
        private UUID reportId;
        private Instant generatedAt;
        private long durationMs;
        private int totalPairsCompared;
        private int identicalPairs;
        private int differentPairs;
        private int errorPairs;
        private List<FileCompareResult> results;
        private List<MapSummary> mapSummaries;
        private List<Recommendation> recommendations;
        private String overallVerdict;
        private String reportFilePath;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FileCompareResult {
        private String filename;
        private String inputFormat;
        private String outputFormatA;
        private String outputFormatB;
        private boolean inputsIdentical;
        private boolean outputsIdentical;
        private int fieldDifferences;
        private int fieldsAdded;
        private int fieldsRemoved;
        private int fieldsModified;
        private List<FieldDiff> fieldDiffs;
        private String verdict;              // IDENTICAL, MINOR_DIFFS, MAJOR_DIFFS, INCOMPATIBLE, ERROR
        private String error;                // null unless ERROR
        private String mapLabel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FieldDiff {
        private String segmentId;
        private String fieldName;
        private String sourceValue;
        private String targetValue;
        private String description;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MapSummary {
        private String mapLabel;
        private int totalFiles;
        private int identicalFiles;
        private int differentFiles;
        private int errorFiles;
        private List<String> commonIssues;
        private List<String> patternInsights;
        private String verdict;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Recommendation {
        private String category;     // FIELD_MAPPING, TRANSFORM, FORMAT, MISSING_FIELD, EXTRA_FIELD, VALUE_MISMATCH
        private String severity;     // HIGH, MEDIUM, LOW
        private String field;
        private String issue;
        private String fix;
        private int affectedFiles;
    }

    // Internal session holder
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    private static class CompareSession {
        private UUID id;
        private CompareRequest request;
        private List<FilePair> filePairs;
        private CompareReport report;
        private Instant createdAt;
        private Instant expiresAt;
    }

    // ========================================================================
    // PREPARE — scan directories, match files, return preview
    // ========================================================================

    public ComparePreview prepareComparison(CompareRequest request) {
        UUID sessionId = UUID.randomUUID();
        log.info("Preparing comparison session {} — scanning directories", sessionId);

        // If a CSV mapping file is provided, use explicit mappings
        if (request.getMappingFilePath() != null && !request.getMappingFilePath().isBlank()) {
            return prepareFromMappingFile(sessionId, request);
        }

        // Validate paths
        Path sourceInputDir = Path.of(request.getSourceInputPath());
        Path sourceOutputDir = Path.of(request.getSourceOutputPath());
        Path targetInputDir = Path.of(request.getTargetInputPath());
        Path targetOutputDir = Path.of(request.getTargetOutputPath());

        if (!Files.isDirectory(sourceInputDir) || !Files.isDirectory(sourceOutputDir)) {
            return ComparePreview.builder()
                    .sessionId(sessionId).status("ERROR")
                    .message("Source input/output paths must be valid directories").build();
        }
        if (!Files.isDirectory(targetInputDir) || !Files.isDirectory(targetOutputDir)) {
            return ComparePreview.builder()
                    .sessionId(sessionId).status("ERROR")
                    .message("Target input/output paths must be valid directories").build();
        }

        // Scan both output directories for files
        Map<String, Path> sourceOutputFiles = scanDirectory(sourceOutputDir);
        Map<String, Path> targetOutputFiles = scanDirectory(targetOutputDir);
        Map<String, Path> sourceInputFiles = scanDirectory(sourceInputDir);
        Map<String, Path> targetInputFiles = scanDirectory(targetInputDir);

        // Match files by name (case-insensitive)
        List<FilePair> pairs = new ArrayList<>();
        Set<String> matchedTargetNames = new HashSet<>();

        for (Map.Entry<String, Path> entry : sourceOutputFiles.entrySet()) {
            String name = entry.getKey();
            Path sourceOutput = entry.getValue();
            Path targetOutput = targetOutputFiles.get(name);

            if (targetOutput != null) {
                matchedTargetNames.add(name);
                Path sourceInput = sourceInputFiles.get(name);
                Path targetInput = targetInputFiles.get(name);

                // Also try matching input files with common EDI extensions
                if (sourceInput == null) sourceInput = findInputByBaseName(name, sourceInputFiles);
                if (targetInput == null) targetInput = findInputByBaseName(name, targetInputFiles);

                String detectedFormat = detectFormatSafe(sourceOutput);
                String mapLabel = buildMapLabel(detectedFormat, name);

                pairs.add(FilePair.builder()
                        .sourceInputFile(sourceInput != null ? sourceInput.toString() : null)
                        .sourceOutputFile(sourceOutput.toString())
                        .targetInputFile(targetInput != null ? targetInput.toString() : null)
                        .targetOutputFile(targetOutput.toString())
                        .detectedFormat(detectedFormat)
                        .sourceOutputSize(fileSizeSafe(sourceOutput))
                        .targetOutputSize(fileSizeSafe(targetOutput))
                        .mapLabel(mapLabel)
                        .build());
            }
        }

        List<String> unmatchedSource = sourceOutputFiles.keySet().stream()
                .filter(n -> !targetOutputFiles.containsKey(n))
                .sorted().collect(Collectors.toList());
        List<String> unmatchedTarget = targetOutputFiles.keySet().stream()
                .filter(n -> !matchedTargetNames.contains(n))
                .sorted().collect(Collectors.toList());

        String status = pairs.isEmpty() ? "NEEDS_MAPPING" : "READY";
        String message = pairs.isEmpty()
                ? "No files matched by name. Provide a CSV mapping file with explicit file pairs."
                : String.format("Found %d matched file pairs. %d source-only, %d target-only. Ready to compare.",
                    pairs.size(), unmatchedSource.size(), unmatchedTarget.size());

        // Store session
        CompareSession session = CompareSession.builder()
                .id(sessionId).request(request).filePairs(pairs)
                .createdAt(Instant.now()).expiresAt(Instant.now().plus(SESSION_TTL)).build();
        sessions.put(sessionId, session);
        log.info("Comparison session {} created (instance-local — use sticky routing or complete within one request)", sessionId);

        return ComparePreview.builder()
                .sessionId(sessionId)
                .totalSourceFiles(sourceOutputFiles.size())
                .totalTargetFiles(targetOutputFiles.size())
                .matchedPairs(pairs.size())
                .filePairs(pairs)
                .unmatchedSource(unmatchedSource)
                .unmatchedTarget(unmatchedTarget)
                .status(status).message(message).build();
    }

    /**
     * Prepare from a CSV mapping file.
     * CSV format: sourceInputFile,sourceOutputFile,targetInputFile,targetOutputFile[,mapLabel]
     * First row is treated as header if it contains "source" (case-insensitive).
     */
    public ComparePreview prepareFromMappingFile(UUID sessionId, CompareRequest request) {
        Path csvPath = Path.of(request.getMappingFilePath());
        if (!Files.isRegularFile(csvPath)) {
            return ComparePreview.builder()
                    .sessionId(sessionId).status("ERROR")
                    .message("Mapping file not found: " + request.getMappingFilePath()).build();
        }

        List<FilePair> pairs = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Skip header row
                if (lineNum == 1 && line.toLowerCase().contains("source")) continue;

                String[] cols = parseCsvLine(line);
                if (cols.length < 4) {
                    errors.add("Line " + lineNum + ": expected at least 4 columns, got " + cols.length);
                    continue;
                }

                String sourceInput = cols[0].trim();
                String sourceOutput = cols[1].trim();
                String targetInput = cols[2].trim();
                String targetOutput = cols[3].trim();
                String mapLabel = cols.length > 4 ? cols[4].trim() : null;

                // Validate that output files exist
                if (!Files.isRegularFile(Path.of(sourceOutput))) {
                    errors.add("Line " + lineNum + ": source output file not found: " + sourceOutput);
                    continue;
                }
                if (!Files.isRegularFile(Path.of(targetOutput))) {
                    errors.add("Line " + lineNum + ": target output file not found: " + targetOutput);
                    continue;
                }

                String detectedFormat = detectFormatSafe(Path.of(sourceOutput));
                if (mapLabel == null || mapLabel.isEmpty()) {
                    mapLabel = buildMapLabel(detectedFormat, Path.of(sourceOutput).getFileName().toString());
                }

                pairs.add(FilePair.builder()
                        .sourceInputFile(sourceInput.isEmpty() ? null : sourceInput)
                        .sourceOutputFile(sourceOutput)
                        .targetInputFile(targetInput.isEmpty() ? null : targetInput)
                        .targetOutputFile(targetOutput)
                        .detectedFormat(detectedFormat)
                        .sourceOutputSize(fileSizeSafe(Path.of(sourceOutput)))
                        .targetOutputSize(fileSizeSafe(Path.of(targetOutput)))
                        .mapLabel(mapLabel)
                        .build());
            }
        } catch (IOException e) {
            return ComparePreview.builder()
                    .sessionId(sessionId).status("ERROR")
                    .message("Failed to read mapping file: " + e.getMessage()).build();
        }

        String status = pairs.isEmpty() ? "ERROR" : "READY";
        String message = pairs.isEmpty()
                ? "No valid file pairs found in mapping file." + (errors.isEmpty() ? "" : " Errors: " + String.join("; ", errors))
                : String.format("Loaded %d file pairs from mapping file.%s", pairs.size(),
                    errors.isEmpty() ? "" : " Warnings: " + String.join("; ", errors));

        CompareSession session = CompareSession.builder()
                .id(sessionId).request(request).filePairs(pairs)
                .createdAt(Instant.now()).expiresAt(Instant.now().plus(SESSION_TTL)).build();
        sessions.put(sessionId, session);
        log.info("Comparison session {} created (instance-local — use sticky routing or complete within one request)", sessionId);

        return ComparePreview.builder()
                .sessionId(sessionId)
                .totalSourceFiles(pairs.size())
                .totalTargetFiles(pairs.size())
                .matchedPairs(pairs.size())
                .filePairs(pairs)
                .unmatchedSource(List.of())
                .unmatchedTarget(List.of())
                .status(status).message(message).build();
    }

    /**
     * Prepare from inline CSV content (multipart upload).
     */
    public ComparePreview prepareFromCsvContent(String csvContent, String reportOutputPath) {
        UUID sessionId = UUID.randomUUID();

        // Write to temp file and delegate
        try {
            Path temp = Files.createTempFile("compare-mapping-", ".csv");
            Files.writeString(temp, csvContent, StandardCharsets.UTF_8);

            CompareRequest request = CompareRequest.builder()
                    .mappingFilePath(temp.toString())
                    .reportOutputPath(reportOutputPath)
                    .build();

            ComparePreview preview = prepareFromMappingFile(sessionId, request);

            // Update session with original report path
            CompareSession session = sessions.get(sessionId);
            if (session != null && reportOutputPath != null) {
                session.getRequest().setReportOutputPath(reportOutputPath);
            }

            Files.deleteIfExists(temp);
            return preview;
        } catch (IOException e) {
            return ComparePreview.builder()
                    .sessionId(sessionId).status("ERROR")
                    .message("Failed to process uploaded mapping: " + e.getMessage()).build();
        }
    }

    // ========================================================================
    // EXECUTE — run all comparisons, build report
    // ========================================================================

    public CompareReport executeComparison(UUID sessionId) {
        CompareSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found or expired: " + sessionId);
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            throw new IllegalArgumentException("Session expired: " + sessionId);
        }
        if (session.getReport() != null) {
            return session.getReport(); // Already executed
        }

        log.info("Executing comparison session {} — {} file pairs", sessionId, session.getFilePairs().size());
        Instant start = Instant.now();

        List<FileCompareResult> results = new ArrayList<>();
        int identical = 0, different = 0, errors = 0;

        for (FilePair pair : session.getFilePairs()) {
            FileCompareResult result = comparePair(pair);
            results.add(result);

            switch (result.getVerdict()) {
                case "IDENTICAL" -> identical++;
                case "ERROR" -> errors++;
                default -> different++;
            }
        }

        // Aggregate by map label
        List<MapSummary> mapSummaries = aggregateByMap(results);

        // Generate recommendations
        List<Recommendation> recommendations = generateRecommendations(results);

        // Sort recommendations by severity (HIGH first) then affected files (desc)
        recommendations.sort((a, b) -> {
            int sevComp = severityRank(a.getSeverity()) - severityRank(b.getSeverity());
            if (sevComp != 0) return sevComp;
            return Integer.compare(b.getAffectedFiles(), a.getAffectedFiles());
        });

        long durationMs = Duration.between(start, Instant.now()).toMillis();

        // Build verdict
        String verdict;
        if (different == 0 && errors == 0) {
            verdict = "PERFECT — All " + identical + " file pairs produce identical output. No changes needed.";
        } else if (errors > 0 && different == 0) {
            verdict = "INCOMPLETE — " + errors + " pairs had errors during comparison. Review error details.";
        } else {
            double matchRate = results.isEmpty() ? 0 : (identical * 100.0 / results.size());
            verdict = String.format("NEEDS_WORK — %d/%d pairs identical (%.0f%%), %d different, %d errors. See recommendations.",
                    identical, results.size(), matchRate, different, errors);
        }

        CompareReport report = CompareReport.builder()
                .reportId(sessionId)
                .generatedAt(Instant.now())
                .durationMs(durationMs)
                .totalPairsCompared(results.size())
                .identicalPairs(identical)
                .differentPairs(different)
                .errorPairs(errors)
                .results(results)
                .mapSummaries(mapSummaries)
                .recommendations(recommendations)
                .overallVerdict(verdict)
                .build();

        // Write report to disk if requested
        String reportPath = session.getRequest().getReportOutputPath();
        if (reportPath != null && !reportPath.isBlank()) {
            String savedPath = writeReportToFile(report, reportPath);
            report.setReportFilePath(savedPath);
        }

        session.setReport(report);
        return report;
    }

    // ========================================================================
    // RETRIEVE — get a previously generated report
    // ========================================================================

    public CompareReport getReport(UUID sessionId) {
        CompareSession session = sessions.get(sessionId);
        if (session == null) return null;
        return session.getReport();
    }

    public ComparePreview getPreview(UUID sessionId) {
        CompareSession session = sessions.get(sessionId);
        if (session == null) return null;
        return ComparePreview.builder()
                .sessionId(session.getId())
                .matchedPairs(session.getFilePairs().size())
                .filePairs(session.getFilePairs())
                .status(session.getReport() != null ? "COMPLETED" : "READY")
                .build();
    }

    // ========================================================================
    // COMPARE a single file pair
    // ========================================================================

    FileCompareResult comparePair(FilePair pair) {
        String filename = Path.of(pair.getSourceOutputFile()).getFileName().toString();
        try {
            // Read output files
            String sourceOutput = Files.readString(Path.of(pair.getSourceOutputFile()), StandardCharsets.UTF_8);
            String targetOutput = Files.readString(Path.of(pair.getTargetOutputFile()), StandardCharsets.UTF_8);

            // Check if outputs are byte-identical
            if (sourceOutput.equals(targetOutput)) {
                boolean inputsIdentical = checkInputsIdentical(pair);
                return FileCompareResult.builder()
                        .filename(filename).inputsIdentical(inputsIdentical).outputsIdentical(true)
                        .fieldDifferences(0).fieldsAdded(0).fieldsRemoved(0).fieldsModified(0)
                        .fieldDiffs(List.of()).verdict("IDENTICAL")
                        .inputFormat(pair.getDetectedFormat()).mapLabel(pair.getMapLabel()).build();
            }

            // Try to detect if outputs are structured (JSON/XML/EDI) and use semantic diff
            String sourceFormat = detectContentFormat(sourceOutput);
            String targetFormat = detectContentFormat(targetOutput);

            // If both are EDI content, use the SemanticDiffEngine directly
            if (isEdiFormat(sourceFormat) && isEdiFormat(targetFormat)) {
                return compareEdiOutputs(filename, pair, sourceOutput, targetOutput);
            }

            // If both are JSON, do JSON field comparison
            if ("JSON".equals(sourceFormat) && "JSON".equals(targetFormat)) {
                return compareJsonOutputs(filename, pair, sourceOutput, targetOutput);
            }

            // Fallback: line-by-line diff for other formats
            return compareTextOutputs(filename, pair, sourceOutput, targetOutput);

        } catch (Exception e) {
            log.warn("Error comparing pair {}: {}", filename, e.getMessage());
            return FileCompareResult.builder()
                    .filename(filename).verdict("ERROR").error(e.getMessage())
                    .mapLabel(pair.getMapLabel()).build();
        }
    }

    private FileCompareResult compareEdiOutputs(String filename, FilePair pair,
                                                  String sourceOutput, String targetOutput) {
        SemanticDiffEngine.DiffResult diff = diffEngine.diff(sourceOutput, targetOutput);

        List<FieldDiff> fieldDiffs = new ArrayList<>();
        for (SemanticDiffEngine.DiffEntry entry : diff.getChanges()) {
            if (entry.getElementDiffs() != null) {
                for (SemanticDiffEngine.ElementDiff ed : entry.getElementDiffs()) {
                    fieldDiffs.add(FieldDiff.builder()
                            .segmentId(entry.getSegmentId())
                            .fieldName(ed.getFieldName())
                            .sourceValue(ed.getLeftValue())
                            .targetValue(ed.getRightValue())
                            .description(ed.getDescription())
                            .build());
                }
            } else {
                fieldDiffs.add(FieldDiff.builder()
                        .segmentId(entry.getSegmentId())
                        .fieldName(entry.getSegmentId())
                        .sourceValue(entry.getLeftValue())
                        .targetValue(entry.getRightValue())
                        .description(entry.getDescription())
                        .build());
            }
        }

        boolean inputsIdentical = checkInputsIdentical(pair);
        String verdict = classifyVerdict(diff.getTotalChanges(), diff.getSegmentsAdded(),
                diff.getSegmentsRemoved(), diff.getSegmentsModified());

        return FileCompareResult.builder()
                .filename(filename)
                .inputFormat(pair.getDetectedFormat())
                .outputFormatA(diff.getLeftFormat())
                .outputFormatB(diff.getRightFormat())
                .inputsIdentical(inputsIdentical)
                .outputsIdentical(false)
                .fieldDifferences(diff.getTotalChanges())
                .fieldsAdded(diff.getSegmentsAdded())
                .fieldsRemoved(diff.getSegmentsRemoved())
                .fieldsModified(diff.getSegmentsModified())
                .fieldDiffs(fieldDiffs)
                .verdict(verdict)
                .mapLabel(pair.getMapLabel())
                .build();
    }

    @SuppressWarnings("unchecked")
    private FileCompareResult compareJsonOutputs(String filename, FilePair pair,
                                                   String sourceOutput, String targetOutput) {
        try {
            Map<String, Object> sourceMap = objectMapper.readValue(sourceOutput, Map.class);
            Map<String, Object> targetMap = objectMapper.readValue(targetOutput, Map.class);

            List<FieldDiff> fieldDiffs = new ArrayList<>();
            compareJsonMaps("", sourceMap, targetMap, fieldDiffs);

            int added = 0, removed = 0, modified = 0;
            for (FieldDiff fd : fieldDiffs) {
                if (fd.getSourceValue() == null || fd.getSourceValue().equals("(missing)")) added++;
                else if (fd.getTargetValue() == null || fd.getTargetValue().equals("(missing)")) removed++;
                else modified++;
            }

            boolean inputsIdentical = checkInputsIdentical(pair);
            String verdict = classifyVerdict(fieldDiffs.size(), added, removed, modified);

            return FileCompareResult.builder()
                    .filename(filename)
                    .inputFormat(pair.getDetectedFormat())
                    .outputFormatA("JSON").outputFormatB("JSON")
                    .inputsIdentical(inputsIdentical)
                    .outputsIdentical(false)
                    .fieldDifferences(fieldDiffs.size())
                    .fieldsAdded(added).fieldsRemoved(removed).fieldsModified(modified)
                    .fieldDiffs(fieldDiffs)
                    .verdict(verdict)
                    .mapLabel(pair.getMapLabel())
                    .build();
        } catch (Exception e) {
            // Fallback to text comparison if JSON parsing fails
            return compareTextOutputs(filename, pair, sourceOutput, targetOutput);
        }
    }

    @SuppressWarnings("unchecked")
    private void compareJsonMaps(String prefix, Map<String, Object> source,
                                   Map<String, Object> target, List<FieldDiff> diffs) {
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(source.keySet());
        allKeys.addAll(target.keySet());

        for (String key : allKeys) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object sv = source.get(key);
            Object tv = target.get(key);

            if (sv == null && tv != null) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue("(missing)").targetValue(String.valueOf(tv))
                        .description("Field '" + path + "' added in target: " + tv).build());
            } else if (sv != null && tv == null) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue(String.valueOf(sv)).targetValue("(missing)")
                        .description("Field '" + path + "' missing in target (was: " + sv + ")").build());
            } else if (sv instanceof Map && tv instanceof Map) {
                compareJsonMaps(path, (Map<String, Object>) sv, (Map<String, Object>) tv, diffs);
            } else if (sv instanceof List && tv instanceof List) {
                compareJsonLists(path, (List<Object>) sv, (List<Object>) tv, diffs);
            } else if (!Objects.equals(String.valueOf(sv), String.valueOf(tv))) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue(String.valueOf(sv)).targetValue(String.valueOf(tv))
                        .description("Field '" + path + "': '" + sv + "' → '" + tv + "'").build());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void compareJsonLists(String prefix, List<Object> source, List<Object> target,
                                    List<FieldDiff> diffs) {
        int max = Math.max(source.size(), target.size());
        for (int i = 0; i < max; i++) {
            String path = prefix + "[" + i + "]";
            Object sv = i < source.size() ? source.get(i) : null;
            Object tv = i < target.size() ? target.get(i) : null;

            if (sv == null && tv != null) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue("(missing)").targetValue(String.valueOf(tv))
                        .description("Element '" + path + "' added in target").build());
            } else if (sv != null && tv == null) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue(String.valueOf(sv)).targetValue("(missing)")
                        .description("Element '" + path + "' missing in target").build());
            } else if (sv instanceof Map && tv instanceof Map) {
                compareJsonMaps(path, (Map<String, Object>) sv, (Map<String, Object>) tv, diffs);
            } else if (sv instanceof List && tv instanceof List) {
                compareJsonLists(path, (List<Object>) sv, (List<Object>) tv, diffs);
            } else if (!Objects.equals(String.valueOf(sv), String.valueOf(tv))) {
                diffs.add(FieldDiff.builder()
                        .fieldName(path).sourceValue(String.valueOf(sv)).targetValue(String.valueOf(tv))
                        .description("Element '" + path + "': '" + sv + "' → '" + tv + "'").build());
            }
        }
    }

    private FileCompareResult compareTextOutputs(String filename, FilePair pair,
                                                    String sourceOutput, String targetOutput) {
        String[] sourceLines = sourceOutput.split("\n");
        String[] targetLines = targetOutput.split("\n");

        List<FieldDiff> diffs = new ArrayList<>();
        int maxLines = Math.max(sourceLines.length, targetLines.length);
        int modified = 0, added = 0, removed = 0;

        for (int i = 0; i < maxLines; i++) {
            String sl = i < sourceLines.length ? sourceLines[i] : null;
            String tl = i < targetLines.length ? targetLines[i] : null;

            if (sl == null && tl != null) {
                added++;
                diffs.add(FieldDiff.builder()
                        .fieldName("line:" + (i + 1)).targetValue(tl.trim())
                        .description("Line " + (i + 1) + " added in target").build());
            } else if (sl != null && tl == null) {
                removed++;
                diffs.add(FieldDiff.builder()
                        .fieldName("line:" + (i + 1)).sourceValue(sl.trim())
                        .description("Line " + (i + 1) + " missing in target").build());
            } else if (!Objects.equals(sl, tl)) {
                modified++;
                diffs.add(FieldDiff.builder()
                        .fieldName("line:" + (i + 1)).sourceValue(sl != null ? sl.trim() : "")
                        .targetValue(tl != null ? tl.trim() : "")
                        .description("Line " + (i + 1) + " differs").build());
            }
        }

        // Cap field diffs at 200 to avoid massive reports
        if (diffs.size() > 200) {
            int totalDiffs = diffs.size();
            diffs = new ArrayList<>(diffs.subList(0, 200));
            diffs.add(FieldDiff.builder()
                    .fieldName("...truncated")
                    .description("Showing first 200 of " + totalDiffs + " line differences").build());
        }

        boolean inputsIdentical = checkInputsIdentical(pair);
        String verdict = classifyVerdict(modified + added + removed, added, removed, modified);

        return FileCompareResult.builder()
                .filename(filename)
                .inputFormat(pair.getDetectedFormat())
                .outputFormatA("TEXT").outputFormatB("TEXT")
                .inputsIdentical(inputsIdentical)
                .outputsIdentical(false)
                .fieldDifferences(modified + added + removed)
                .fieldsAdded(added).fieldsRemoved(removed).fieldsModified(modified)
                .fieldDiffs(diffs)
                .verdict(verdict)
                .mapLabel(pair.getMapLabel())
                .build();
    }

    // ========================================================================
    // AGGREGATE by map label
    // ========================================================================

    private List<MapSummary> aggregateByMap(List<FileCompareResult> results) {
        Map<String, List<FileCompareResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMapLabel() != null ? r.getMapLabel() : "unknown",
                        LinkedHashMap::new, Collectors.toList()));

        List<MapSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<FileCompareResult>> entry : grouped.entrySet()) {
            String mapLabel = entry.getKey();
            List<FileCompareResult> mapResults = entry.getValue();

            int identical = 0, different = 0, errorCount = 0;
            Map<String, Integer> issueFrequency = new LinkedHashMap<>();

            for (FileCompareResult r : mapResults) {
                switch (r.getVerdict()) {
                    case "IDENTICAL" -> identical++;
                    case "ERROR" -> errorCount++;
                    default -> {
                        different++;
                        if (r.getFieldDiffs() != null) {
                            for (FieldDiff fd : r.getFieldDiffs()) {
                                String issueKey = fd.getFieldName() != null ? fd.getFieldName() : "unknown";
                                issueFrequency.merge(issueKey, 1, Integer::sum);
                            }
                        }
                    }
                }
            }

            // Find common issues (appear in >50% of different files)
            int diffThreshold = Math.max(1, different / 2);
            List<String> commonIssues = issueFrequency.entrySet().stream()
                    .filter(e -> e.getValue() >= diffThreshold)
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(10)
                    .map(e -> e.getKey() + " (in " + e.getValue() + " files)")
                    .collect(Collectors.toList());

            // Pattern insights
            List<String> insights = new ArrayList<>();
            if (identical == mapResults.size()) {
                insights.add("All files in this map produce identical output — no action needed.");
            } else if (different == mapResults.size()) {
                insights.add("Every file in this map has differences — likely a systematic mapping issue.");
            } else {
                insights.add(String.format("%.0f%% match rate — some files differ, investigate common issues.",
                        identical * 100.0 / mapResults.size()));
            }

            boolean allSameFieldDiffs = different > 1 && mapResults.stream()
                    .filter(r -> !"IDENTICAL".equals(r.getVerdict()) && !"ERROR".equals(r.getVerdict()))
                    .map(r -> r.getFieldDiffs() != null ? r.getFieldDiffs().stream()
                            .map(FieldDiff::getFieldName).collect(Collectors.toSet()) : Set.<String>of())
                    .distinct().count() == 1;
            if (allSameFieldDiffs && different > 1) {
                insights.add("All different files have the SAME differing fields — a single mapping fix will resolve all.");
            }

            String verdict;
            if (identical == mapResults.size()) verdict = "PERFECT";
            else if (errorCount == mapResults.size()) verdict = "ERROR";
            else if (different > identical) verdict = "NEEDS_WORK";
            else verdict = "MOSTLY_GOOD";

            summaries.add(MapSummary.builder()
                    .mapLabel(mapLabel)
                    .totalFiles(mapResults.size())
                    .identicalFiles(identical).differentFiles(different).errorFiles(errorCount)
                    .commonIssues(commonIssues).patternInsights(insights)
                    .verdict(verdict).build());
        }

        return summaries;
    }

    // ========================================================================
    // RECOMMENDATIONS — analyze all diffs and suggest fixes
    // ========================================================================

    List<Recommendation> generateRecommendations(List<FileCompareResult> results) {
        Map<String, RecommendationAccumulator> accumulators = new LinkedHashMap<>();

        for (FileCompareResult result : results) {
            if (result.getFieldDiffs() == null || "IDENTICAL".equals(result.getVerdict())) continue;

            for (FieldDiff diff : result.getFieldDiffs()) {
                String field = diff.getFieldName() != null ? diff.getFieldName() : "unknown";
                String key = categorizeIssue(diff) + "|" + field;

                accumulators.computeIfAbsent(key, k -> new RecommendationAccumulator(field, diff))
                        .fileCount++;
            }
        }

        List<Recommendation> recommendations = new ArrayList<>();
        for (Map.Entry<String, RecommendationAccumulator> entry : accumulators.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String category = parts[0];
            RecommendationAccumulator acc = entry.getValue();

            String severity = acc.fileCount > results.size() / 2 ? "HIGH"
                    : acc.fileCount > results.size() / 4 ? "MEDIUM" : "LOW";

            String issue = buildIssueDescription(category, acc.sampleDiff);
            String fix = buildFixSuggestion(category, acc.sampleDiff);

            recommendations.add(Recommendation.builder()
                    .category(category)
                    .severity(severity)
                    .field(acc.field)
                    .issue(issue)
                    .fix(fix)
                    .affectedFiles(acc.fileCount)
                    .build());
        }

        return recommendations;
    }

    @AllArgsConstructor
    private static class RecommendationAccumulator {
        String field;
        FieldDiff sampleDiff;
        int fileCount;

        RecommendationAccumulator(String field, FieldDiff diff) {
            this.field = field;
            this.sampleDiff = diff;
            this.fileCount = 0;
        }
    }

    private String categorizeIssue(FieldDiff diff) {
        if (diff.getSourceValue() == null || "(missing)".equals(diff.getSourceValue())) return "EXTRA_FIELD";
        if (diff.getTargetValue() == null || "(missing)".equals(diff.getTargetValue())) return "MISSING_FIELD";

        String sv = diff.getSourceValue();
        String tv = diff.getTargetValue();

        // Check if it's a transform issue
        if (sv.equalsIgnoreCase(tv)) return "TRANSFORM_CASE";
        if (sv.trim().equals(tv.trim())) return "TRANSFORM_WHITESPACE";
        if (isDateVariant(sv, tv)) return "TRANSFORM_DATE";
        if (isPaddingVariant(sv, tv)) return "TRANSFORM_PADDING";

        return "VALUE_MISMATCH";
    }

    private boolean isDateVariant(String a, String b) {
        // Common date patterns: 20260406 vs 2026-04-06 vs 04/06/2026
        String cleanA = a.replaceAll("[\\-/.]", "");
        String cleanB = b.replaceAll("[\\-/.]", "");
        return cleanA.length() >= 6 && cleanB.length() >= 6 && cleanA.equals(cleanB);
    }

    private boolean isPaddingVariant(String a, String b) {
        return a.trim().equals(b.trim()) || a.replaceFirst("^0+", "").equals(b.replaceFirst("^0+", ""));
    }

    private String buildIssueDescription(String category, FieldDiff sample) {
        return switch (category) {
            case "MISSING_FIELD" -> "Field '" + sample.getFieldName() + "' present in source output but missing in target output";
            case "EXTRA_FIELD" -> "Field '" + sample.getFieldName() + "' present in target but not in source";
            case "TRANSFORM_CASE" -> "Field '" + sample.getFieldName() + "' has case difference: '" + sample.getSourceValue() + "' vs '" + sample.getTargetValue() + "'";
            case "TRANSFORM_WHITESPACE" -> "Field '" + sample.getFieldName() + "' has whitespace/trim difference";
            case "TRANSFORM_DATE" -> "Field '" + sample.getFieldName() + "' has date format difference: '" + sample.getSourceValue() + "' vs '" + sample.getTargetValue() + "'";
            case "TRANSFORM_PADDING" -> "Field '" + sample.getFieldName() + "' has zero-padding difference: '" + sample.getSourceValue() + "' vs '" + sample.getTargetValue() + "'";
            default -> "Field '" + sample.getFieldName() + "' value mismatch: '" + sample.getSourceValue() + "' vs '" + sample.getTargetValue() + "'";
        };
    }

    private String buildFixSuggestion(String category, FieldDiff sample) {
        return switch (category) {
            case "MISSING_FIELD" -> "Add mapping for '" + sample.getFieldName() + "' in the conversion map. Use NL correction: \"Map " + sample.getFieldName() + " to <targetField>\"";
            case "EXTRA_FIELD" -> "Remove mapping for '" + sample.getFieldName() + "' or verify if source system strips this field. Use NL correction: \"Remove " + sample.getFieldName() + "\"";
            case "TRANSFORM_CASE" -> "Add UPPERCASE or LOWERCASE transform. Use NL correction: \"Make " + sample.getFieldName() + " " + (isUpperCase(sample.getSourceValue()) ? "uppercase" : "lowercase") + "\"";
            case "TRANSFORM_WHITESPACE" -> "Add TRIM transform. Use NL correction: \"Trim " + sample.getFieldName() + "\"";
            case "TRANSFORM_DATE" -> "Add DATE_REFORMAT transform. Use NL correction: \"Format " + sample.getFieldName() + " as " + detectDateFormat(sample.getSourceValue()) + "\"";
            case "TRANSFORM_PADDING" -> "Add ZERO_PAD transform. Use NL correction: \"Zero-pad " + sample.getFieldName() + " to " + sample.getSourceValue().length() + " characters\"";
            default -> "Review field mapping for '" + sample.getFieldName() + "'. Source produces '" + sample.getSourceValue() + "', target produces '" + sample.getTargetValue() + "'. Update the source field reference or add appropriate transform.";
        };
    }

    // ========================================================================
    // REPORT WRITER
    // ========================================================================

    private String writeReportToFile(CompareReport report, String outputPath) {
        try {
            Path path = Path.of(outputPath);

            // If user gave a directory, generate filename
            if (Files.isDirectory(path)) {
                String timestamp = Instant.now().toString().replace(":", "-").substring(0, 19);
                path = path.resolve("compare-report-" + timestamp + ".json");
            }

            // Ensure parent directory exists
            if (path.getParent() != null) Files.createDirectories(path.getParent());

            // Write JSON report — use a copy with Instant handling
            ObjectMapper writer = objectMapper.copy();
            writer.findAndRegisterModules();
            writer.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            String json = writer.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(path, json, StandardCharsets.UTF_8);

            // Also write a human-readable summary alongside
            Path summaryPath = path.resolveSibling(
                    path.getFileName().toString().replace(".json", "-summary.txt"));
            Files.writeString(summaryPath, buildHumanReadableSummary(report), StandardCharsets.UTF_8);

            log.info("Comparison report written to {} and {}", path, summaryPath);
            return path.toString();
        } catch (Exception e) {
            log.error("Failed to write report to {}: {}", outputPath, e.getMessage());
            return null;
        }
    }

    public String buildHumanReadableSummary(CompareReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("  EDI CONVERSION COMPARE REPORT\n");
        sb.append("  Generated: ").append(report.getGeneratedAt()).append("\n");
        sb.append("  Duration: ").append(report.getDurationMs()).append("ms\n");
        sb.append("═══════════════════════════════════════════════════════════════\n\n");

        sb.append("VERDICT: ").append(report.getOverallVerdict()).append("\n\n");

        sb.append("SUMMARY\n");
        sb.append("  Total pairs compared: ").append(report.getTotalPairsCompared()).append("\n");
        sb.append("  Identical:            ").append(report.getIdenticalPairs()).append("\n");
        sb.append("  Different:            ").append(report.getDifferentPairs()).append("\n");
        sb.append("  Errors:               ").append(report.getErrorPairs()).append("\n\n");

        // Map summaries
        if (report.getMapSummaries() != null && !report.getMapSummaries().isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────\n");
            sb.append("BY MAP / FORMAT\n");
            sb.append("───────────────────────────────────────────────────────────────\n");
            for (MapSummary ms : report.getMapSummaries()) {
                sb.append("\n  [").append(ms.getVerdict()).append("] ").append(ms.getMapLabel()).append("\n");
                sb.append("    Files: ").append(ms.getTotalFiles())
                        .append(" | Identical: ").append(ms.getIdenticalFiles())
                        .append(" | Different: ").append(ms.getDifferentFiles())
                        .append(" | Errors: ").append(ms.getErrorFiles()).append("\n");
                if (ms.getCommonIssues() != null && !ms.getCommonIssues().isEmpty()) {
                    sb.append("    Common issues:\n");
                    ms.getCommonIssues().forEach(i -> sb.append("      - ").append(i).append("\n"));
                }
                if (ms.getPatternInsights() != null) {
                    ms.getPatternInsights().forEach(i -> sb.append("    Insight: ").append(i).append("\n"));
                }
            }
            sb.append("\n");
        }

        // Recommendations
        if (report.getRecommendations() != null && !report.getRecommendations().isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────\n");
            sb.append("RECOMMENDATIONS (sorted by severity + impact)\n");
            sb.append("───────────────────────────────────────────────────────────────\n\n");
            int idx = 1;
            for (Recommendation rec : report.getRecommendations()) {
                sb.append("  ").append(idx++).append(". [").append(rec.getSeverity()).append("] ")
                        .append(rec.getCategory()).append("\n");
                sb.append("     Field: ").append(rec.getField()).append("\n");
                sb.append("     Issue: ").append(rec.getIssue()).append("\n");
                sb.append("     Fix:   ").append(rec.getFix()).append("\n");
                sb.append("     Affects: ").append(rec.getAffectedFiles()).append(" file(s)\n\n");
            }
        }

        // Per-file details (only for different files)
        List<FileCompareResult> differentFiles = report.getResults().stream()
                .filter(r -> !"IDENTICAL".equals(r.getVerdict()))
                .toList();

        if (!differentFiles.isEmpty()) {
            sb.append("───────────────────────────────────────────────────────────────\n");
            sb.append("FILE-LEVEL DETAILS (non-identical files only)\n");
            sb.append("───────────────────────────────────────────────────────────────\n");
            for (FileCompareResult r : differentFiles) {
                sb.append("\n  ").append(r.getFilename()).append(" [").append(r.getVerdict()).append("]\n");
                if (r.getError() != null) {
                    sb.append("    Error: ").append(r.getError()).append("\n");
                    continue;
                }
                sb.append("    Format: ").append(r.getOutputFormatA()).append(" vs ").append(r.getOutputFormatB()).append("\n");
                sb.append("    Diffs: ").append(r.getFieldDifferences())
                        .append(" (added: ").append(r.getFieldsAdded())
                        .append(", removed: ").append(r.getFieldsRemoved())
                        .append(", modified: ").append(r.getFieldsModified()).append(")\n");
                if (r.getFieldDiffs() != null) {
                    int shown = 0;
                    for (FieldDiff fd : r.getFieldDiffs()) {
                        if (shown++ >= 10) {
                            sb.append("    ... and ").append(r.getFieldDiffs().size() - 10).append(" more\n");
                            break;
                        }
                        sb.append("    - ").append(fd.getDescription()).append("\n");
                    }
                }
            }
        }

        sb.append("\n═══════════════════════════════════════════════════════════════\n");
        sb.append("  END OF REPORT\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private Map<String, Path> scanDirectory(Path dir) {
        Map<String, Path> files = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> files.put(p.getFileName().toString().toLowerCase(), p));
        } catch (IOException e) {
            log.warn("Failed to scan directory {}: {}", dir, e.getMessage());
        }
        return files;
    }

    private Path findInputByBaseName(String outputName, Map<String, Path> inputFiles) {
        // Try matching by base name (strip extension and match)
        String baseName = outputName.contains(".")
                ? outputName.substring(0, outputName.lastIndexOf('.')) : outputName;

        for (Map.Entry<String, Path> entry : inputFiles.entrySet()) {
            String inputBase = entry.getKey().contains(".")
                    ? entry.getKey().substring(0, entry.getKey().lastIndexOf('.')) : entry.getKey();
            if (inputBase.equalsIgnoreCase(baseName)) return entry.getValue();
        }
        return null;
    }

    private String detectFormatSafe(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > 4096) content = content.substring(0, 4096);
            return formatDetector.detect(content);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String detectContentFormat(String content) {
        if (content == null || content.isBlank()) return "EMPTY";
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "JSON";
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) return "XML";
        try {
            return formatDetector.detect(trimmed);
        } catch (Exception e) {
            return "TEXT";
        }
    }

    private boolean isEdiFormat(String format) {
        return format != null && Set.of("X12", "EDIFACT", "HL7", "SWIFT_MT", "NACHA",
                "BAI2", "FIX", "TRADACOMS", "ISO20022", "PEPPOL").contains(format);
    }

    private boolean checkInputsIdentical(FilePair pair) {
        if (pair.getSourceInputFile() == null || pair.getTargetInputFile() == null) return true;
        try {
            String a = Files.readString(Path.of(pair.getSourceInputFile()), StandardCharsets.UTF_8);
            String b = Files.readString(Path.of(pair.getTargetInputFile()), StandardCharsets.UTF_8);
            return a.equals(b);
        } catch (Exception e) {
            return true; // Assume identical if we can't read
        }
    }

    private long fileSizeSafe(Path path) {
        try { return Files.size(path); } catch (Exception e) { return -1; }
    }

    private String buildMapLabel(String format, String filename) {
        if (format == null || "UNKNOWN".equals(format)) return "generic";
        // Try to detect transaction type from filename (e.g., "850_order.json" → "X12:850")
        String name = filename.toLowerCase();
        for (String type : List.of("837", "835", "850", "856", "810", "820", "834", "270", "271", "997")) {
            if (name.contains(type)) return format + ":" + type;
        }
        return format;
    }

    private String classifyVerdict(int total, int added, int removed, int modified) {
        if (total == 0) return "IDENTICAL";
        if (removed > 0 && added == 0 && modified == 0) return "MAJOR_DIFFS";
        if (total <= 3) return "MINOR_DIFFS";
        if (total <= 10) return "MODERATE_DIFFS";
        return "MAJOR_DIFFS";
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    private boolean isUpperCase(String s) {
        return s != null && s.equals(s.toUpperCase());
    }

    private String detectDateFormat(String date) {
        if (date == null) return "yyyy-MM-dd";
        if (date.matches("\\d{8}")) return "yyyyMMdd";
        if (date.matches("\\d{4}-\\d{2}-\\d{2}")) return "yyyy-MM-dd";
        if (date.matches("\\d{2}/\\d{2}/\\d{4}")) return "MM/dd/yyyy";
        return "yyyy-MM-dd";
    }

    private String[] parseCsvLine(String line) {
        // Simple CSV parser that handles quoted fields
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /** Clean up expired sessions — runs every 5 minutes */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().getExpiresAt().isBefore(now));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired comparison sessions, {} remaining", removed, sessions.size());
        }
    }
}
