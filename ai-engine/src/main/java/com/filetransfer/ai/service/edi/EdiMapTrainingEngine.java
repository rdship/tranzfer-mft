package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.entity.edi.TrainingSample;
import com.filetransfer.ai.entity.edi.TrainingSession;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core ML training engine for EDI conversion maps.
 *
 * Training pipeline (runs per map key):
 *   1. Parse all training samples into source→target field pairs
 *   2. Strategy 1: Exact Value Alignment — match identical values across samples (99% confidence)
 *   3. Strategy 2: Statistical Value Correlation — frequency-based matching across many samples
 *   4. Strategy 3: Structural Position Mapping — same position = same meaning
 *   5. Strategy 4: Semantic Field Embedding — n-gram/synonym similarity on field names
 *   6. Strategy 5: Transform Detection — detect DATE_FORMAT, PAD, TRIM, CONCAT transforms
 *   7. Cross-validate on held-out samples, measure accuracy
 *   8. Produce versioned ConversionMap with confidence scores per rule
 *
 * More samples → higher confidence → more accurate maps.
 * Pure Java, no external ML libraries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EdiMapTrainingEngine {

    private final FieldEmbeddingEngine embeddingEngine;

    private static final double MIN_EMBEDDING_THRESHOLD = 0.35;
    private static final int MIN_SAMPLES_FOR_STATISTICAL = 3;
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^\\d{8}$");
    private static final Pattern DATE_DASH_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DATE_SLASH_PATTERN = Pattern.compile("^\\d{2}/\\d{2}/\\d{4}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+\\.?\\d*$");

    /**
     * Train a conversion map from a set of samples.
     * Returns a TrainingResult containing the map and session metadata.
     */
    public TrainingResult train(List<TrainingSample> samples, String mapKey) {
        Instant start = Instant.now();
        log.info("Training map '{}' with {} samples", mapKey, samples.size());

        if (samples.isEmpty()) {
            return TrainingResult.builder()
                    .success(false).error("No training samples provided").build();
        }

        // Split into training and test sets (80/20 if enough samples)
        List<TrainingSample> trainSet;
        List<TrainingSample> testSet;
        if (samples.size() >= 5) {
            int splitIdx = (int) (samples.size() * 0.8);
            trainSet = new ArrayList<>(samples.subList(0, splitIdx));
            testSet = new ArrayList<>(samples.subList(splitIdx, samples.size()));
        } else {
            trainSet = new ArrayList<>(samples);
            testSet = List.of();
        }

        // Parse all samples into field-value maps
        List<ParsedSample> parsedSamples = trainSet.stream()
                .map(this::parseSample)
                .filter(Objects::nonNull)
                .toList();

        if (parsedSamples.isEmpty()) {
            return TrainingResult.builder()
                    .success(false).error("Could not parse any training samples").build();
        }

        // Run all training strategies
        List<String> strategiesUsed = new ArrayList<>();
        Map<String, LearnedRule> ruleMap = new LinkedHashMap<>(); // targetField → best rule

        // Strategy 1: Exact value alignment
        exactValueAlignment(parsedSamples, ruleMap);
        strategiesUsed.add("EXACT_VALUE");

        // Strategy 2: Statistical correlation (needs 3+ samples)
        if (parsedSamples.size() >= MIN_SAMPLES_FOR_STATISTICAL) {
            statisticalCorrelation(parsedSamples, ruleMap);
            strategiesUsed.add("STATISTICAL");
        }

        // Strategy 3: Structural position mapping
        structuralPositionMapping(parsedSamples, ruleMap);
        strategiesUsed.add("STRUCTURAL");

        // Strategy 4: Semantic field embedding
        semanticFieldEmbedding(parsedSamples, ruleMap);
        strategiesUsed.add("SEMANTIC_EMBEDDING");

        // Strategy 5: Transform detection (enhances existing rules)
        detectTransforms(parsedSamples, ruleMap);
        strategiesUsed.add("TRANSFORM_DETECTION");

        // Consolidate rules and compute confidence
        List<FieldMapping> fieldMappings = consolidateRules(ruleMap);

        // Test on held-out samples
        Integer testAccuracy = null;
        if (!testSet.isEmpty()) {
            testAccuracy = evaluateAccuracy(fieldMappings, testSet);
        }

        // Compute overall confidence
        int overallConfidence = computeOverallConfidence(fieldMappings, parsedSamples.size());

        // Generate executable mapping code
        String code = generateCode(fieldMappings);

        // Collect unmapped fields
        Set<String> allSourceFields = parsedSamples.stream()
                .flatMap(p -> p.sourceFields.keySet().stream())
                .collect(Collectors.toSet());
        Set<String> allTargetFields = parsedSamples.stream()
                .flatMap(p -> p.targetFields.keySet().stream())
                .collect(Collectors.toSet());
        Set<String> mappedSources = fieldMappings.stream()
                .map(FieldMapping::getSourceField).collect(Collectors.toSet());
        Set<String> mappedTargets = fieldMappings.stream()
                .map(FieldMapping::getTargetField).collect(Collectors.toSet());

        List<String> unmappedSource = allSourceFields.stream()
                .filter(f -> !mappedSources.contains(f)).sorted().toList();
        List<String> unmappedTarget = allTargetFields.stream()
                .filter(f -> !mappedTargets.contains(f)).sorted().toList();

        // Build training report
        String report = buildTrainingReport(parsedSamples.size(), testSet.size(),
                strategiesUsed, fieldMappings, unmappedSource, unmappedTarget, testAccuracy);

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        log.info("Training complete for '{}': {} mappings, {}% confidence, {}ms",
                mapKey, fieldMappings.size(), overallConfidence, durationMs);

        return TrainingResult.builder()
                .success(true)
                .mapKey(mapKey)
                .fieldMappings(fieldMappings)
                .generatedCode(code)
                .confidence(overallConfidence)
                .testAccuracy(testAccuracy)
                .trainingSampleCount(trainSet.size())
                .testSampleCount(testSet.size())
                .strategiesUsed(strategiesUsed)
                .unmappedSourceFields(unmappedSource)
                .unmappedTargetFields(unmappedTarget)
                .trainingReport(report)
                .durationMs(durationMs)
                .build();
    }

    // ========================================================================
    // Strategy 1: Exact Value Alignment
    // ========================================================================

    private void exactValueAlignment(List<ParsedSample> samples, Map<String, LearnedRule> ruleMap) {
        // For each sample, find source→target pairs where values are identical
        Map<String, Map<String, Integer>> pairCounts = new HashMap<>();

        for (ParsedSample sample : samples) {
            for (Map.Entry<String, String> target : sample.targetFields.entrySet()) {
                String targetField = target.getKey();
                String targetValue = target.getValue().trim();
                if (targetValue.isEmpty()) continue;

                for (Map.Entry<String, String> source : sample.sourceFields.entrySet()) {
                    String sourceField = source.getKey();
                    String sourceValue = source.getValue().trim();

                    if (targetValue.equals(sourceValue)) {
                        String pairKey = sourceField + "→" + targetField;
                        pairCounts.computeIfAbsent(targetField, k -> new HashMap<>())
                                .merge(pairKey, 1, Integer::sum);
                    }
                }
            }
        }

        // Accept pairs that appear in majority of samples
        for (Map.Entry<String, Map<String, Integer>> entry : pairCounts.entrySet()) {
            String targetField = entry.getKey();
            Map<String, Integer> candidates = entry.getValue();

            candidates.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(best -> {
                        String sourceField = best.getKey().split("→")[0];
                        int count = best.getValue();
                        int confidence = Math.min(99, 70 + (count * 30 / samples.size()));

                        LearnedRule rule = new LearnedRule(sourceField, targetField, "DIRECT",
                                null, confidence, "EXACT_VALUE",
                                String.format("Exact value match in %d/%d samples", count, samples.size()));
                        ruleMap.merge(targetField, rule, (old, nw) ->
                                nw.confidence > old.confidence ? nw : old);
                    });
        }
    }

    // ========================================================================
    // Strategy 2: Statistical Correlation
    // ========================================================================

    private void statisticalCorrelation(List<ParsedSample> samples, Map<String, LearnedRule> ruleMap) {
        // Build co-occurrence matrix: how often does sourceField value correlate with targetField value?
        // If ISA*06 always maps to header.sender across samples, even with different values, that's a correlation.

        // Collect all unique target fields not yet mapped
        Set<String> unmappedTargets = samples.stream()
                .flatMap(s -> s.targetFields.keySet().stream())
                .filter(t -> !ruleMap.containsKey(t))
                .collect(Collectors.toSet());

        for (String targetField : unmappedTargets) {
            // For each source field, compute correlation with this target
            Map<String, Double> sourceCorrelations = new HashMap<>();

            Set<String> allSourceFields = samples.stream()
                    .flatMap(s -> s.sourceFields.keySet().stream())
                    .collect(Collectors.toSet());

            for (String sourceField : allSourceFields) {
                double correlation = computeValueCorrelation(samples, sourceField, targetField);
                if (correlation > 0.5) {
                    sourceCorrelations.put(sourceField, correlation);
                }
            }

            if (!sourceCorrelations.isEmpty()) {
                Map.Entry<String, Double> best = sourceCorrelations.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (best != null) {
                    int confidence = (int) (best.getValue() * 85);
                    LearnedRule rule = new LearnedRule(best.getKey(), targetField, "DIRECT",
                            null, confidence, "STATISTICAL",
                            String.format("Value correlation %.0f%% across %d samples",
                                    best.getValue() * 100, samples.size()));
                    ruleMap.merge(targetField, rule, (old, nw) ->
                            nw.confidence > old.confidence ? nw : old);
                }
            }
        }
    }

    private double computeValueCorrelation(List<ParsedSample> samples, String sourceField, String targetField) {
        // Mutual information approximation: how much does knowing sourceField reduce uncertainty about targetField?
        int coPresent = 0;
        int bothHaveValue = 0;
        int valueRelated = 0;

        for (ParsedSample sample : samples) {
            String sv = sample.sourceFields.get(sourceField);
            String tv = sample.targetFields.get(targetField);

            if (sv != null && tv != null) {
                bothHaveValue++;
                String svTrim = sv.trim();
                String tvTrim = tv.trim();

                // Check various relationships
                if (svTrim.equals(tvTrim)) {
                    valueRelated += 3; // exact match
                } else if (svTrim.equalsIgnoreCase(tvTrim)) {
                    valueRelated += 2; // case-insensitive
                } else if (svTrim.contains(tvTrim) || tvTrim.contains(svTrim)) {
                    valueRelated += 1; // substring
                }
                coPresent++;
            }
        }

        if (bothHaveValue == 0) return 0.0;
        double presenceCorrelation = (double) coPresent / samples.size();
        double valueScore = (double) valueRelated / (bothHaveValue * 3);
        return (presenceCorrelation * 0.3 + valueScore * 0.7);
    }

    // ========================================================================
    // Strategy 3: Structural Position Mapping
    // ========================================================================

    private void structuralPositionMapping(List<ParsedSample> samples, Map<String, LearnedRule> ruleMap) {
        // If source field at position N consistently maps to target field at position M,
        // that structural relationship holds even when values change

        if (samples.isEmpty()) return;
        ParsedSample reference = samples.get(0);

        List<String> sourceOrder = new ArrayList<>(reference.sourceFields.keySet());
        List<String> targetOrder = new ArrayList<>(reference.targetFields.keySet());

        // Only map unmapped targets
        Set<String> unmappedTargets = targetOrder.stream()
                .filter(t -> !ruleMap.containsKey(t))
                .collect(Collectors.toSet());

        for (String targetField : unmappedTargets) {
            int targetIdx = targetOrder.indexOf(targetField);
            if (targetIdx < 0 || targetIdx >= sourceOrder.size()) continue;

            // Check if same-position source field has consistent relationship
            String candidateSource = sourceOrder.get(Math.min(targetIdx, sourceOrder.size() - 1));

            // Verify across samples
            int matches = 0;
            for (ParsedSample sample : samples) {
                String sv = sample.sourceFields.get(candidateSource);
                String tv = sample.targetFields.get(targetField);
                if (sv != null && tv != null && !sv.trim().isEmpty() && !tv.trim().isEmpty()) {
                    if (sv.trim().equals(tv.trim()) || sv.trim().equalsIgnoreCase(tv.trim())) {
                        matches++;
                    }
                }
            }

            if (matches > 0) {
                int confidence = Math.min(70, 30 + (matches * 40 / samples.size()));
                LearnedRule rule = new LearnedRule(candidateSource, targetField, "DIRECT",
                        null, confidence, "STRUCTURAL",
                        String.format("Structural position match (idx %d), verified in %d/%d samples",
                                targetIdx, matches, samples.size()));
                ruleMap.merge(targetField, rule, (old, nw) ->
                        nw.confidence > old.confidence ? nw : old);
            }
        }
    }

    // ========================================================================
    // Strategy 4: Semantic Field Embedding
    // ========================================================================

    private void semanticFieldEmbedding(List<ParsedSample> samples, Map<String, LearnedRule> ruleMap) {
        if (samples.isEmpty()) return;

        Set<String> allSourceFields = samples.stream()
                .flatMap(s -> s.sourceFields.keySet().stream())
                .collect(Collectors.toSet());
        Set<String> unmappedTargets = samples.stream()
                .flatMap(s -> s.targetFields.keySet().stream())
                .filter(t -> !ruleMap.containsKey(t))
                .collect(Collectors.toSet());

        for (String targetField : unmappedTargets) {
            embeddingEngine.findBestMatch(targetField, allSourceFields, MIN_EMBEDDING_THRESHOLD)
                    .ifPresent(match -> {
                        int confidence = (int) (match.similarity() * 75);
                        LearnedRule rule = new LearnedRule(match.sourceField(), targetField, "DIRECT",
                                null, confidence, "SEMANTIC_EMBEDDING", match.reasoning());
                        ruleMap.merge(targetField, rule, (old, nw) ->
                                nw.confidence > old.confidence ? nw : old);
                    });
        }
    }

    // ========================================================================
    // Strategy 5: Transform Detection
    // ========================================================================

    private void detectTransforms(List<ParsedSample> samples, Map<String, LearnedRule> ruleMap) {
        for (Map.Entry<String, LearnedRule> entry : new ArrayList<>(ruleMap.entrySet())) {
            LearnedRule rule = entry.getValue();
            String sourceField = rule.sourceField;
            String targetField = rule.targetField;

            // Check across samples what transform is needed
            TransformResult transform = detectTransformType(samples, sourceField, targetField);
            if (transform != null && !"DIRECT".equals(transform.type)) {
                rule.transform = transform.type;
                rule.transformParam = transform.param;
                rule.reasoning += " + " + transform.description;
                // Boost confidence slightly when we can detect the exact transform
                rule.confidence = Math.min(99, rule.confidence + 5);
            }
        }
    }

    private TransformResult detectTransformType(List<ParsedSample> samples, String sourceField, String targetField) {
        int dateReformat = 0, trimNeeded = 0, uppercased = 0, lowercased = 0, padded = 0;
        int total = 0;
        String dateSourceFormat = null, dateTargetFormat = null;
        int detectedPadLength = -1;

        for (ParsedSample sample : samples) {
            String sv = sample.sourceFields.get(sourceField);
            String tv = sample.targetFields.get(targetField);
            if (sv == null || tv == null) continue;
            total++;

            // Check: trim transform
            if (!sv.equals(tv) && sv.trim().equals(tv.trim())) {
                trimNeeded++;
            }
            // Check: case transform
            if (sv.equalsIgnoreCase(tv) && !sv.equals(tv)) {
                if (tv.equals(tv.toUpperCase())) uppercased++;
                if (tv.equals(tv.toLowerCase())) lowercased++;
            }
            // Check: date reformat (YYYYMMDD ↔ YYYY-MM-DD ↔ MM/DD/YYYY)
            if (DATE_8_PATTERN.matcher(sv).matches() && DATE_DASH_PATTERN.matcher(tv).matches()) {
                dateReformat++;
                dateSourceFormat = "yyyyMMdd";
                dateTargetFormat = "yyyy-MM-dd";
            } else if (DATE_DASH_PATTERN.matcher(sv).matches() && DATE_8_PATTERN.matcher(tv).matches()) {
                dateReformat++;
                dateSourceFormat = "yyyy-MM-dd";
                dateTargetFormat = "yyyyMMdd";
            } else if (DATE_8_PATTERN.matcher(sv).matches() && DATE_SLASH_PATTERN.matcher(tv).matches()) {
                dateReformat++;
                dateSourceFormat = "yyyyMMdd";
                dateTargetFormat = "MM/dd/yyyy";
            }
            // Check: zero-padding
            if (NUMERIC_PATTERN.matcher(sv.trim()).matches() && NUMERIC_PATTERN.matcher(tv.trim()).matches()) {
                if (tv.length() > sv.trim().length() && tv.startsWith("0")) {
                    padded++;
                    detectedPadLength = tv.length();
                }
            }
        }

        if (total == 0) return null;
        double threshold = 0.6;

        if ((double) dateReformat / total >= threshold && dateSourceFormat != null) {
            return new TransformResult("DATE_REFORMAT", dateSourceFormat + "→" + dateTargetFormat,
                    String.format("Date reformatted %s→%s in %d/%d samples", dateSourceFormat, dateTargetFormat, dateReformat, total));
        }
        if ((double) trimNeeded / total >= threshold) {
            return new TransformResult("TRIM", null,
                    String.format("Whitespace trimming needed in %d/%d samples", trimNeeded, total));
        }
        if ((double) uppercased / total >= threshold) {
            return new TransformResult("UPPERCASE", null,
                    String.format("Uppercased in %d/%d samples", uppercased, total));
        }
        if ((double) lowercased / total >= threshold) {
            return new TransformResult("LOWERCASE", null,
                    String.format("Lowercased in %d/%d samples", lowercased, total));
        }
        if ((double) padded / total >= threshold && detectedPadLength > 0) {
            return new TransformResult("ZERO_PAD", String.valueOf(detectedPadLength),
                    String.format("Zero-padded to %d chars in %d/%d samples", detectedPadLength, padded, total));
        }

        return null;
    }

    // ========================================================================
    // Consolidation & Evaluation
    // ========================================================================

    private List<FieldMapping> consolidateRules(Map<String, LearnedRule> ruleMap) {
        return ruleMap.values().stream()
                .map(r -> FieldMapping.builder()
                        .sourceField(r.sourceField)
                        .targetField(r.targetField)
                        .transform(r.transform)
                        .transformParam(r.transformParam)
                        .confidence(r.confidence)
                        .strategy(r.strategy)
                        .reasoning(r.reasoning)
                        .build())
                .sorted((a, b) -> Integer.compare(b.getConfidence(), a.getConfidence()))
                .toList();
    }

    private int evaluateAccuracy(List<FieldMapping> mappings, List<TrainingSample> testSamples) {
        if (testSamples.isEmpty() || mappings.isEmpty()) return 0;

        int totalFields = 0;
        int correctFields = 0;

        for (TrainingSample testSample : testSamples) {
            ParsedSample parsed = parseSample(testSample);
            if (parsed == null) continue;

            for (FieldMapping mapping : mappings) {
                String sourceValue = parsed.sourceFields.get(mapping.getSourceField());
                String expectedTarget = parsed.targetFields.get(mapping.getTargetField());

                if (expectedTarget != null) {
                    totalFields++;
                    if (sourceValue != null) {
                        String predicted = applyTransform(sourceValue, mapping.getTransform(), mapping.getTransformParam());
                        if (predicted.equals(expectedTarget) || predicted.trim().equals(expectedTarget.trim())) {
                            correctFields++;
                        }
                    }
                }
            }
        }

        return totalFields > 0 ? (correctFields * 100) / totalFields : 0;
    }

    private String applyTransform(String value, String transform, String param) {
        if (value == null) return "";
        return switch (transform != null ? transform : "DIRECT") {
            case "TRIM" -> value.trim();
            case "UPPERCASE" -> value.toUpperCase();
            case "LOWERCASE" -> value.toLowerCase();
            case "ZERO_PAD" -> {
                int len = param != null ? Integer.parseInt(param) : value.length();
                yield String.format("%" + len + "s", value.trim()).replace(' ', '0');
            }
            case "DATE_REFORMAT" -> reformatDate(value, param);
            default -> value;
        };
    }

    private String reformatDate(String value, String param) {
        if (param == null || !param.contains("→")) return value;
        // Simple date reformatting: yyyyMMdd → yyyy-MM-dd
        try {
            if (value.length() == 8 && param.startsWith("yyyyMMdd")) {
                return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
            }
            if (value.length() == 10 && value.contains("-") && param.startsWith("yyyy-MM-dd")) {
                return value.replace("-", "");
            }
        } catch (Exception e) {
            // Fall through
        }
        return value;
    }

    private int computeOverallConfidence(List<FieldMapping> mappings, int sampleCount) {
        if (mappings.isEmpty()) return 0;

        double avgConfidence = mappings.stream()
                .mapToInt(FieldMapping::getConfidence)
                .average().orElse(0);

        // Boost for more samples (diminishing returns)
        double sampleBoost = Math.min(15, Math.log(sampleCount + 1) * 5);

        return Math.min(99, (int) (avgConfidence + sampleBoost));
    }

    // ========================================================================
    // Sample Parsing
    // ========================================================================

    @SuppressWarnings("unchecked")
    private ParsedSample parseSample(TrainingSample sample) {
        try {
            Map<String, String> sourceFields = parseContent(sample.getInputContent(), sample.getSourceFormat());
            Map<String, String> targetFields = parseContent(sample.getOutputContent(), sample.getTargetFormat());

            if (sourceFields.isEmpty() || targetFields.isEmpty()) return null;

            return new ParsedSample(sourceFields, targetFields);
        } catch (Exception e) {
            log.warn("Failed to parse training sample {}: {}", sample.getId(), e.getMessage());
            return null;
        }
    }

    /** Parse EDI or structured content into flat field→value map */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseContent(String content, String format) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (content == null || content.isBlank()) return fields;

        String trimmed = content.trim();

        // Try JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> json = mapper.readValue(trimmed, Map.class);
                flattenJson(json, "", fields);
                return fields;
            } catch (Exception ignored) {}
        }

        // Try XML
        if (trimmed.startsWith("<")) {
            parseXmlFields(trimmed, fields);
            if (!fields.isEmpty()) return fields;
        }

        // Try CSV
        if (trimmed.contains(",") && trimmed.contains("\n")) {
            parseCsvFields(trimmed, fields);
            if (!fields.isEmpty()) return fields;
        }

        // Try EDI formats
        if (format != null) {
            switch (format.toUpperCase()) {
                case "X12" -> parseX12Fields(trimmed, fields);
                case "EDIFACT" -> parseEdifactFields(trimmed, fields);
                case "HL7" -> parseHl7Fields(trimmed, fields);
                case "SWIFT_MT", "SWIFT" -> parseSwiftFields(trimmed, fields);
                default -> {
                    // Auto-detect
                    if (trimmed.contains("ISA*")) parseX12Fields(trimmed, fields);
                    else if (trimmed.contains("UNB+")) parseEdifactFields(trimmed, fields);
                    else if (trimmed.contains("MSH|")) parseHl7Fields(trimmed, fields);
                    else if (trimmed.contains("{1:")) parseSwiftFields(trimmed, fields);
                }
            }
        }

        // Fallback: auto-detect from content
        if (fields.isEmpty()) {
            if (trimmed.contains("ISA*")) parseX12Fields(trimmed, fields);
            else if (trimmed.contains("UNB+")) parseEdifactFields(trimmed, fields);
            else if (trimmed.contains("MSH|")) parseHl7Fields(trimmed, fields);
        }

        return fields;
    }

    private void parseX12Fields(String content, Map<String, String> fields) {
        String delimiter = content.contains("~") ? "~" : "\n";
        String[] segments = content.split(delimiter);
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            String[] elements = trimmed.split("\\*");
            String segId = elements[0];
            for (int i = 1; i < elements.length; i++) {
                String key = segId + "*" + String.format("%02d", i);
                if (!elements[i].trim().isEmpty()) {
                    fields.put(key, elements[i]);
                }
            }
        }
    }

    private void parseEdifactFields(String content, Map<String, String> fields) {
        String[] segments = content.split("'");
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            String[] elements = trimmed.split("\\+");
            String segId = elements[0];
            for (int i = 1; i < elements.length; i++) {
                String key = segId + "+" + String.format("%02d", i);
                // Handle sub-elements (colon-separated)
                String[] subElements = elements[i].split(":");
                if (subElements.length > 1) {
                    for (int j = 0; j < subElements.length; j++) {
                        if (!subElements[j].trim().isEmpty()) {
                            fields.put(key + ":" + (j + 1), subElements[j]);
                        }
                    }
                } else if (!elements[i].trim().isEmpty()) {
                    fields.put(key, elements[i]);
                }
            }
        }
    }

    private void parseHl7Fields(String content, Map<String, String> fields) {
        String[] segments = content.split("\r?\n|\r");
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.isEmpty()) continue;
            String[] elements = trimmed.split("\\|");
            String segId = elements[0];
            for (int i = 1; i < elements.length; i++) {
                String key = segId + "|" + i;
                if (!elements[i].trim().isEmpty()) {
                    // Handle HL7 sub-components (^ separated)
                    String[] subs = elements[i].split("\\^");
                    if (subs.length > 1) {
                        for (int j = 0; j < subs.length; j++) {
                            if (!subs[j].trim().isEmpty()) {
                                fields.put(key + "." + (j + 1), subs[j]);
                            }
                        }
                    } else {
                        fields.put(key, elements[i]);
                    }
                }
            }
        }
    }

    private void parseSwiftFields(String content, Map<String, String> fields) {
        Pattern tagPattern = Pattern.compile(":([0-9]{2}[A-Z]?):(.+?)(?=:[0-9]{2}[A-Z]?:|$)",
                Pattern.DOTALL);
        Matcher matcher = tagPattern.matcher(content);
        while (matcher.find()) {
            fields.put("TAG_" + matcher.group(1), matcher.group(2).trim());
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenJson(Map<String, Object> json, String prefix, Map<String, String> fields) {
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flattenJson((Map<String, Object>) value, key, fields);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        flattenJson((Map<String, Object>) item, key + "[" + i + "]", fields);
                    } else if (item != null) {
                        fields.put(key + "[" + i + "]", item.toString());
                    }
                }
            } else if (value != null) {
                fields.put(key, value.toString());
            }
        }
    }

    private void parseXmlFields(String content, Map<String, String> fields) {
        Pattern tagPattern = Pattern.compile("<([a-zA-Z][a-zA-Z0-9_:-]*)(?:\\s[^>]*)?>([^<]+)</\\1>");
        Matcher matcher = tagPattern.matcher(content);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2).trim());
        }
    }

    private void parseCsvFields(String content, Map<String, String> fields) {
        String[] lines = content.split("\n");
        if (lines.length < 2) return;
        String[] headers = lines[0].split(",");
        String[] values = lines[1].split(",", -1);
        for (int i = 0; i < Math.min(headers.length, values.length); i++) {
            String header = headers[i].trim().replaceAll("^\"|\"$", "");
            String value = values[i].trim().replaceAll("^\"|\"$", "");
            if (!header.isEmpty() && !value.isEmpty()) {
                fields.put(header, value);
            }
        }
    }

    // ========================================================================
    // Code Generation
    // ========================================================================

    /**
     * Generate executable mapping code from field mappings.
     * Package-accessible for use by MappingCorrectionService.
     */
    public String generateMappingCode(List<FieldMapping> mappings) {
        return generateCode(mappings);
    }

    private String generateCode(List<FieldMapping> mappings) {
        StringBuilder code = new StringBuilder();
        code.append("// Auto-generated from AI training engine\n");
        code.append("// Trained on ").append(mappings.size()).append(" field mappings\n\n");
        code.append("{\n");

        for (int i = 0; i < mappings.size(); i++) {
            FieldMapping m = mappings.get(i);
            code.append("  \"").append(m.getTargetField()).append("\": ");

            switch (m.getTransform() != null ? m.getTransform() : "DIRECT") {
                case "TRIM" -> code.append("$trim($source.\"").append(m.getSourceField()).append("\")");
                case "UPPERCASE" -> code.append("$uppercase($source.\"").append(m.getSourceField()).append("\")");
                case "LOWERCASE" -> code.append("$lowercase($source.\"").append(m.getSourceField()).append("\")");
                case "ZERO_PAD" -> code.append("$pad($source.\"").append(m.getSourceField())
                        .append("\", ").append(m.getTransformParam()).append(", '0')");
                case "DATE_REFORMAT" -> code.append("$dateFormat($source.\"").append(m.getSourceField())
                        .append("\", \"").append(m.getTransformParam()).append("\")");
                default -> code.append("$source.\"").append(m.getSourceField()).append("\"");
            }

            if (i < mappings.size() - 1) code.append(",");
            code.append("  // ").append(m.getConfidence()).append("% [").append(m.getStrategy()).append("]");
            code.append("\n");
        }

        code.append("}\n");
        return code.toString();
    }

    private String buildTrainingReport(int trainCount, int testCount, List<String> strategies,
                                        List<FieldMapping> mappings, List<String> unmappedSource,
                                        List<String> unmappedTarget, Integer testAccuracy) {
        StringBuilder report = new StringBuilder();
        report.append("=== EDI Map Training Report ===\n\n");
        report.append("Training samples: ").append(trainCount).append("\n");
        report.append("Test samples: ").append(testCount).append("\n");
        report.append("Strategies applied: ").append(String.join(", ", strategies)).append("\n\n");

        report.append("--- Field Mappings (").append(mappings.size()).append(") ---\n");
        for (FieldMapping m : mappings) {
            report.append(String.format("  %s → %s [%s, %d%%, %s]\n",
                    m.getSourceField(), m.getTargetField(), m.getTransform(),
                    m.getConfidence(), m.getStrategy()));
        }

        if (!unmappedSource.isEmpty()) {
            report.append("\n--- Unmapped Source Fields (").append(unmappedSource.size()).append(") ---\n");
            unmappedSource.forEach(f -> report.append("  ").append(f).append("\n"));
        }
        if (!unmappedTarget.isEmpty()) {
            report.append("\n--- Unmapped Target Fields (").append(unmappedTarget.size()).append(") ---\n");
            unmappedTarget.forEach(f -> report.append("  ").append(f).append("\n"));
        }

        if (testAccuracy != null) {
            report.append("\n--- Test Accuracy: ").append(testAccuracy).append("% ---\n");
        }

        return report.toString();
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    private static class ParsedSample {
        final Map<String, String> sourceFields;
        final Map<String, String> targetFields;

        ParsedSample(Map<String, String> sourceFields, Map<String, String> targetFields) {
            this.sourceFields = sourceFields;
            this.targetFields = targetFields;
        }
    }

    private static class LearnedRule {
        String sourceField;
        String targetField;
        String transform;
        String transformParam;
        int confidence;
        String strategy;
        String reasoning;

        LearnedRule(String sourceField, String targetField, String transform, String transformParam,
                    int confidence, String strategy, String reasoning) {
            this.sourceField = sourceField;
            this.targetField = targetField;
            this.transform = transform;
            this.transformParam = transformParam;
            this.confidence = confidence;
            this.strategy = strategy;
            this.reasoning = reasoning;
        }
    }

    private record TransformResult(String type, String param, String description) {}

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FieldMapping {
        private String sourceField;
        private String targetField;
        private String transform;
        private String transformParam;
        private int confidence;
        private String strategy;
        private String reasoning;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrainingResult {
        private boolean success;
        private String error;
        private String mapKey;
        private List<FieldMapping> fieldMappings;
        private String generatedCode;
        private int confidence;
        private Integer testAccuracy;
        private int trainingSampleCount;
        private int testSampleCount;
        private List<String> strategiesUsed;
        private List<String> unmappedSourceFields;
        private List<String> unmappedTargetFields;
        private String trainingReport;
        private long durationMs;
    }
}
