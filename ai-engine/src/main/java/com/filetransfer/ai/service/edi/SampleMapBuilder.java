package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds conversion maps automatically from sample file pairs.
 * No technical knowledge required -- partner uploads files, gets a map.
 *
 * Pipeline:
 *   1. Detect source/target formats (via edi-converter /detect/type)
 *   2. Parse all samples into structured form
 *   3. Flatten to field path -> value maps
 *   4. Match target fields to source fields using value correlation + field-name similarity
 *   5. Detect transforms (date reformat, trim, case change, padding)
 *   6. Detect loops (repeating segments)
 *   7. Build ConversionMapDefinition, preview, and confidence report
 *   8. Save as DRAFT
 */
@Service
@Slf4j
public class SampleMapBuilder {

    private final FieldEmbeddingEngine fieldEmbedding;
    private final ConversionMapRepository mapRepo;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${platform.services.edi-converter.url:http://edi-converter:8095}")
    private String ediConverterUrl;

    public SampleMapBuilder(FieldEmbeddingEngine fieldEmbedding,
                            ConversionMapRepository mapRepo,
                            ObjectMapper objectMapper) {
        this.fieldEmbedding = fieldEmbedding;
        this.mapRepo = mapRepo;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    // ===================================================================
    // Public API
    // ===================================================================

    /**
     * Build a map from sample file pairs.
     *
     * @param samples   list of input/output pairs (2-5 recommended)
     * @param partnerId partner this map is for
     * @param name      human-friendly map name
     * @return BuildResult with the generated map, preview, and confidence details
     */
    public BuildResult buildFromSamples(List<SamplePair> samples, String partnerId, String name) {
        Instant start = Instant.now();

        if (samples == null || samples.isEmpty()) {
            return BuildResult.builder().error("At least one sample pair is required").build();
        }
        if (samples.size() > 10) {
            return BuildResult.builder().error("Maximum 10 sample pairs allowed").build();
        }

        try {
            return doBuild(samples, partnerId, name, start);
        } catch (Exception e) {
            log.error("Sample map build failed for partner {}: {}", partnerId, e.getMessage(), e);
            return BuildResult.builder().error("Build failed: " + e.getMessage()).build();
        }
    }

    private BuildResult doBuild(List<SamplePair> samples, String partnerId, String name, Instant start) {
        // 1. Detect source format
        String sourceType = detectType(samples.get(0).getInput());

        // 2. Parse all source samples into structured form
        List<Map<String, Object>> parsedSources = samples.stream()
                .map(s -> parseDocument(s.getInput()))
                .toList();

        // 3. Parse all target samples
        List<Map<String, Object>> parsedTargets = samples.stream()
                .map(s -> parseTargetDocument(s.getOutput()))
                .toList();

        // 4. Flatten both sides to field path -> value maps
        List<Map<String, String>> flatSources = parsedSources.stream()
                .map(this::flattenToPathValues)
                .toList();
        List<Map<String, String>> flatTargets = parsedTargets.stream()
                .map(this::flattenToPathValues)
                .toList();

        // 5. Collect all field paths
        Set<String> allTargetPaths = flatTargets.stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> allSourcePaths = flatSources.stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toCollection(TreeSet::new));

        // 6. For each target field, find the best matching source field
        List<FieldMappingCandidate> candidates = new ArrayList<>();
        Set<String> usedSourcePaths = new HashSet<>();

        for (String targetPath : allTargetPaths) {
            FieldMappingCandidate best = findBestSourceMatch(
                    targetPath, allSourcePaths, flatSources, flatTargets);
            if (best != null) {
                candidates.add(best);
                usedSourcePaths.add(best.sourcePath);
            }
        }

        // 7. Detect transforms needed (date format, case change, padding, etc.)
        for (FieldMappingCandidate candidate : candidates) {
            detectTransform(candidate, flatSources, flatTargets);
        }

        // 8. Detect loops (repeating patterns)
        List<LoopMappingCandidate> loops = detectLoops(flatSources, flatTargets, candidates);

        // 9. Build map definition as JSON structure
        String targetType = detectTargetType(flatTargets);
        Map<String, Object> mapDefinition = buildMapDefinition(
                name, sourceType, targetType, partnerId, candidates, loops);

        // 10. Run conversion preview on last sample
        Map<String, Object> preview = generatePreview(candidates,
                flatSources.get(flatSources.size() - 1));

        // 11. Identify issues
        List<String> unmappedSource = allSourcePaths.stream()
                .filter(p -> !usedSourcePaths.contains(p))
                .sorted()
                .toList();
        List<String> unmappedTarget = allTargetPaths.stream()
                .filter(p -> candidates.stream().noneMatch(c -> c.targetPath.equals(p)))
                .sorted()
                .toList();
        List<LowConfidenceField> lowConfidence = candidates.stream()
                .filter(c -> c.confidence < 0.8)
                .map(c -> new LowConfidenceField(c.sourcePath, c.targetPath, c.confidence, c.reason))
                .toList();

        // 12. Save as DRAFT
        ConversionMap entity = saveAsDraft(partnerId, name, sourceType,
                targetType, candidates, samples.size());

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        double overallConfidence = candidates.stream()
                .mapToDouble(c -> c.confidence).average().orElse(0);

        log.info("Built map from {} samples for partner {}: {} fields mapped, {}% avg confidence, {}ms",
                samples.size(), partnerId, candidates.size(),
                String.format("%.0f", overallConfidence * 100), durationMs);

        return BuildResult.builder()
                .mapId(entity.getId().toString())
                .mapDefinition(mapDefinition)
                .preview(preview)
                .overallConfidence(overallConfidence)
                .totalFields(candidates.size())
                .unmappedSourceFields(unmappedSource)
                .unmappedTargetFields(unmappedTarget)
                .lowConfidenceFields(lowConfidence)
                .loopMappings(loops.size())
                .durationMs(durationMs)
                .build();
    }

    // ===================================================================
    // Matching Strategies
    // ===================================================================

    /**
     * Find the best source field match for a given target field.
     * Uses 4 strategies in priority order:
     *   1. Exact value match across all samples (0.99)
     *   2. Normalized value match (trim/case) across all samples (0.95)
     *   3. Value containment (substring) across most samples (0.80)
     *   4. Field name semantic similarity via FieldEmbeddingEngine (0.30-0.80)
     */
    FieldMappingCandidate findBestSourceMatch(String targetPath, Set<String> sourcePaths,
                                               List<Map<String, String>> flatSources,
                                               List<Map<String, String>> flatTargets) {
        FieldMappingCandidate best = null;

        for (String sourcePath : sourcePaths) {
            double score = 0;
            String reason = "";

            // Strategy 1: Exact value match across all samples
            int exactMatches = 0;
            int comparableSamples = 0;
            for (int i = 0; i < flatSources.size(); i++) {
                String sv = flatSources.get(i).get(sourcePath);
                String tv = flatTargets.get(i).get(targetPath);
                if (sv != null && tv != null) {
                    comparableSamples++;
                    if (sv.equals(tv)) exactMatches++;
                }
            }
            if (comparableSamples > 0 && exactMatches == comparableSamples) {
                score = 0.99;
                reason = "Exact value match in all " + exactMatches + " samples";
            }

            // Strategy 2: Normalized value match (trim, case-insensitive)
            if (score < 0.9) {
                int normalizedMatches = 0;
                int normComparables = 0;
                for (int i = 0; i < flatSources.size(); i++) {
                    String sv = normalizeValue(flatSources.get(i).get(sourcePath));
                    String tv = normalizeValue(flatTargets.get(i).get(targetPath));
                    if (sv != null && tv != null) {
                        normComparables++;
                        if (sv.equals(tv)) normalizedMatches++;
                    }
                }
                if (normComparables > 0 && normalizedMatches == normComparables) {
                    score = Math.max(score, 0.95);
                    reason = "Value match after normalization in all " + normalizedMatches + " samples";
                }
            }

            // Strategy 3: Value containment (substring match)
            if (score < 0.8) {
                int containedMatches = 0;
                int containComparables = 0;
                for (int i = 0; i < flatSources.size(); i++) {
                    String sv = flatSources.get(i).get(sourcePath);
                    String tv = flatTargets.get(i).get(targetPath);
                    if (sv != null && tv != null && !sv.isEmpty() && !tv.isEmpty()) {
                        containComparables++;
                        if (sv.contains(tv) || tv.contains(sv)) containedMatches++;
                    }
                }
                if (containComparables > 0 && containedMatches >= Math.max(1, containComparables - 1)) {
                    double containScore = 0.75 + 0.05 * containedMatches / Math.max(1, containComparables);
                    if (containScore > score) {
                        score = containScore;
                        reason = "Value contained in " + containedMatches + "/" + containComparables + " samples";
                    }
                }
            }

            // Strategy 4: Field name semantic similarity
            if (score < 0.7) {
                String sourceLeaf = extractFieldName(sourcePath);
                String targetLeaf = extractFieldName(targetPath);
                double nameSim = fieldEmbedding.similarity(sourceLeaf, targetLeaf);
                if (nameSim > 0.4) {
                    double nameScore = 0.3 + nameSim * 0.5;
                    if (nameScore > score) {
                        score = nameScore;
                        reason = "Field name similarity: " + String.format("%.0f%%", nameSim * 100);
                    }
                }
            }

            // Combined boost: if both value and name match, boost confidence
            if (score > 0.5) {
                String sourceLeaf = extractFieldName(sourcePath);
                String targetLeaf = extractFieldName(targetPath);
                double nameSim = fieldEmbedding.similarity(sourceLeaf, targetLeaf);
                if (nameSim > 0.5 && score < 0.99) {
                    score = Math.min(0.99, score + 0.05);
                    reason += " + name similarity boost";
                }
            }

            if (score > 0 && (best == null || score > best.confidence)) {
                best = new FieldMappingCandidate(sourcePath, targetPath, score, reason, null, null);
            }
        }

        return best;
    }

    // ===================================================================
    // Transform Detection
    // ===================================================================

    /**
     * Detect what transform is needed between matched source and target values.
     * Examines actual values across samples to infer the transformation.
     */
    private void detectTransform(FieldMappingCandidate candidate,
                                 List<Map<String, String>> flatSources,
                                 List<Map<String, String>> flatTargets) {
        candidate.transform = "COPY";
        candidate.transformConfig = new LinkedHashMap<>();

        // Collect paired values for analysis
        List<String[]> valuePairs = new ArrayList<>();
        for (int i = 0; i < flatSources.size(); i++) {
            String sv = flatSources.get(i).get(candidate.sourcePath);
            String tv = flatTargets.get(i).get(candidate.targetPath);
            if (sv != null && tv != null) {
                valuePairs.add(new String[]{sv, tv});
            }
        }

        if (valuePairs.isEmpty()) return;

        // Check: exact match -> COPY
        boolean allExact = valuePairs.stream().allMatch(p -> p[0].equals(p[1]));
        if (allExact) return;

        // Check: date format change
        if (detectDateTransform(candidate, valuePairs)) return;

        // Check: case change (UPPERCASE / LOWERCASE)
        boolean allUpper = valuePairs.stream().allMatch(p -> p[1].equals(p[0].toUpperCase()));
        if (allUpper && valuePairs.stream().anyMatch(p -> !p[0].equals(p[0].toUpperCase()))) {
            candidate.transform = "UPPERCASE";
            return;
        }
        boolean allLower = valuePairs.stream().allMatch(p -> p[1].equals(p[0].toLowerCase()));
        if (allLower && valuePairs.stream().anyMatch(p -> !p[0].equals(p[0].toLowerCase()))) {
            candidate.transform = "LOWERCASE";
            return;
        }

        // Check: trim (source has leading/trailing whitespace, target does not)
        boolean allTrimmed = valuePairs.stream().allMatch(p -> p[1].equals(p[0].trim()));
        if (allTrimmed && valuePairs.stream().anyMatch(p -> !p[0].equals(p[0].trim()))) {
            candidate.transform = "TRIM";
            return;
        }

        // Check: zero padding
        if (detectPaddingTransform(candidate, valuePairs)) return;

        // Check: substring extraction
        if (detectSubstringTransform(candidate, valuePairs)) return;

        // If values differ but no transform detected, mark as COPY with lower confidence
        if (!allExact) {
            candidate.confidence = Math.min(candidate.confidence, 0.7);
            candidate.reason += " (values differ, no transform detected)";
        }
    }

    private boolean detectDateTransform(FieldMappingCandidate candidate, List<String[]> valuePairs) {
        String[][] dateFormats = {
                {"yyyyMMdd", "\\d{8}"},
                {"yyyy-MM-dd", "\\d{4}-\\d{2}-\\d{2}"},
                {"MM/dd/yyyy", "\\d{2}/\\d{2}/\\d{4}"},
                {"dd/MM/yyyy", "\\d{2}/\\d{2}/\\d{4}"},
                {"MM-dd-yyyy", "\\d{2}-\\d{2}-\\d{4}"},
                {"yyMMdd", "\\d{6}"}
        };

        String sourceFormat = null;
        String targetFormat = null;

        for (String[] fmt : dateFormats) {
            if (valuePairs.stream().allMatch(p -> p[0].matches(fmt[1]))) {
                sourceFormat = fmt[0];
                break;
            }
        }
        for (String[] fmt : dateFormats) {
            if (valuePairs.stream().allMatch(p -> p[1].matches(fmt[1]))) {
                targetFormat = fmt[0];
                break;
            }
        }

        if (sourceFormat != null && targetFormat != null && !sourceFormat.equals(targetFormat)) {
            candidate.transform = "DATE_REFORMAT";
            candidate.transformConfig.put("sourceFormat", sourceFormat);
            candidate.transformConfig.put("targetFormat", targetFormat);
            candidate.transformConfig.put("param", sourceFormat + "->" + targetFormat);
            return true;
        }
        return false;
    }

    private boolean detectPaddingTransform(FieldMappingCandidate candidate, List<String[]> valuePairs) {
        boolean allPadded = valuePairs.stream().allMatch(p -> {
            String sv = p[0].trim();
            String tv = p[1].trim();
            return tv.length() > sv.length()
                    && tv.endsWith(sv)
                    && tv.substring(0, tv.length() - sv.length()).chars().allMatch(c -> c == '0');
        });

        if (allPadded && !valuePairs.isEmpty()) {
            int targetLen = valuePairs.get(0)[1].trim().length();
            candidate.transform = "ZERO_PAD";
            candidate.transformConfig.put("length", String.valueOf(targetLen));
            return true;
        }
        return false;
    }

    private boolean detectSubstringTransform(FieldMappingCandidate candidate, List<String[]> valuePairs) {
        if (valuePairs.size() < 2) return false;

        Integer startIdx = null;
        Integer endIdx = null;
        boolean consistent = true;

        for (String[] pair : valuePairs) {
            String sv = pair[0];
            String tv = pair[1];
            int idx = sv.indexOf(tv);
            if (idx < 0 || tv.isEmpty()) {
                consistent = false;
                break;
            }
            int end = idx + tv.length();
            if (startIdx == null) {
                startIdx = idx;
                endIdx = end;
            } else if (idx != startIdx || end != endIdx) {
                consistent = false;
                break;
            }
        }

        if (consistent && startIdx != null) {
            candidate.transform = "SUBSTRING";
            candidate.transformConfig.put("start", String.valueOf(startIdx));
            candidate.transformConfig.put("end", String.valueOf(endIdx));
            return true;
        }
        return false;
    }

    // ===================================================================
    // Loop Detection
    // ===================================================================

    /**
     * Detect repeating field patterns that indicate loops/arrays in the data.
     * Looks for indexed paths like "lineItems[0].qty", "lineItems[1].qty"
     * or segment repetition patterns.
     */
    List<LoopMappingCandidate> detectLoops(List<Map<String, String>> flatSources,
                                            List<Map<String, String>> flatTargets,
                                            List<FieldMappingCandidate> candidates) {
        List<LoopMappingCandidate> loops = new ArrayList<>();

        Pattern indexPattern = Pattern.compile("^(.+?)\\[(\\d+)](.+)$|^(.+?)\\.(\\d+)\\.(.+)$");

        Map<String, List<String>> targetGroups = new LinkedHashMap<>();
        for (Map<String, String> flat : flatTargets) {
            for (String path : flat.keySet()) {
                Matcher m = indexPattern.matcher(path);
                if (m.matches()) {
                    String prefix = m.group(1) != null ? m.group(1) : m.group(4);
                    String suffix = m.group(1) != null ? m.group(3) : ("." + m.group(6));
                    String groupKey = prefix + "[*]" + suffix;
                    targetGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(path);
                }
            }
        }

        Map<String, List<String>> sourceGroups = new LinkedHashMap<>();
        for (Map<String, String> flat : flatSources) {
            for (String path : flat.keySet()) {
                Matcher m = indexPattern.matcher(path);
                if (m.matches()) {
                    String prefix = m.group(1) != null ? m.group(1) : m.group(4);
                    String suffix = m.group(1) != null ? m.group(3) : ("." + m.group(6));
                    String groupKey = prefix + "[*]" + suffix;
                    sourceGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(path);
                }
            }
        }

        for (Map.Entry<String, List<String>> targetGroup : targetGroups.entrySet()) {
            if (targetGroup.getValue().size() < 2) continue;

            String targetPattern = targetGroup.getKey();
            String targetLeaf = extractFieldName(targetPattern);

            String bestSourcePattern = null;
            double bestSim = 0;

            for (String sourcePattern : sourceGroups.keySet()) {
                String sourceLeaf = extractFieldName(sourcePattern);
                double sim = fieldEmbedding.similarity(sourceLeaf, targetLeaf);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestSourcePattern = sourcePattern;
                }
            }

            if (bestSourcePattern != null && bestSim > 0.3) {
                loops.add(new LoopMappingCandidate(
                        bestSourcePattern, targetPattern,
                        sourceGroups.get(bestSourcePattern).size(),
                        targetGroup.getValue().size(),
                        bestSim,
                        "Loop: " + bestSourcePattern + " -> " + targetPattern
                ));
            }
        }

        return loops;
    }

    // ===================================================================
    // Format Detection and Parsing
    // ===================================================================

    /**
     * Detect the document type by calling edi-converter's /detect/type endpoint.
     * Falls back to content-based heuristics if the service is unavailable.
     */
    String detectType(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    ediConverterUrl + "/api/v1/convert/detect/type",
                    Map.of("content", content), Map.class);
            if (result != null && result.containsKey("type")) {
                return (String) result.get("type");
            }
        } catch (Exception e) {
            log.debug("EDI converter type detection unavailable, using heuristics: {}", e.getMessage());
        }

        // Heuristic fallback
        String trimmed = content.trim();
        if (trimmed.startsWith("ISA") || trimmed.contains("~GS*")) return "X12";
        if (trimmed.startsWith("UNA") || trimmed.startsWith("UNB")) return "EDIFACT";
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "JSON";
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) return "XML";
        if (trimmed.contains(",") && trimmed.contains("\n")) return "CSV";
        return "UNKNOWN";
    }

    /**
     * Parse an EDI/structured document into a nested Map.
     * Calls edi-converter /parse endpoint, falls back to local heuristic parsing.
     */
    Map<String, Object> parseDocument(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    ediConverterUrl + "/api/v1/convert/parse",
                    Map.of("content", content), Map.class);
            if (result != null) return result;
        } catch (Exception e) {
            log.debug("EDI converter parse unavailable, using local parser: {}", e.getMessage());
        }
        return localParse(content);
    }

    /**
     * Parse a target document (typically JSON, XML, or CSV).
     */
    Map<String, Object> parseTargetDocument(String content) {
        String trimmed = content.trim();

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Failed to parse target as JSON: {}", e.getMessage());
            }
        }

        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
            return parseDocument(content);
        }

        if (trimmed.contains(",") && trimmed.contains("\n")) {
            return parseCsv(trimmed);
        }

        return parseDocument(content);
    }

    // ===================================================================
    // Local Parsers (fallback when edi-converter is unavailable)
    // ===================================================================

    private Map<String, Object> localParse(String content) {
        String trimmed = content.trim();

        if (trimmed.startsWith("ISA") || trimmed.contains("~GS*")) {
            return parseX12(trimmed);
        }
        if (trimmed.startsWith("UNA") || trimmed.startsWith("UNB")) {
            return parseEdifact(trimmed);
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<>() {});
            } catch (Exception e) {
                return Map.of("_raw", trimmed);
            }
        }
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) {
            return parseSimpleXml(trimmed);
        }
        if (trimmed.contains(",") && trimmed.contains("\n")) {
            return parseCsv(trimmed);
        }
        return Map.of("_raw", trimmed);
    }

    /**
     * Parse X12 EDI into a flat map keyed by segment ID and element index.
     * ISA*01*value~GS*... -> {"ISA*01": "01", "ISA*02": "value", ...}
     */
    private Map<String, Object> parseX12(String content) {
        Map<String, Object> result = new LinkedHashMap<>();

        char elementDelim = content.length() > 3 ? content.charAt(3) : '*';
        char segmentDelim = content.contains("~") ? '~' : '\n';

        String[] segments = content.split(Pattern.quote(String.valueOf(segmentDelim)));
        Map<String, Integer> segmentCounts = new LinkedHashMap<>();

        for (String segment : segments) {
            String seg = segment.trim();
            if (seg.isEmpty()) continue;

            String[] elements = seg.split(Pattern.quote(String.valueOf(elementDelim)));
            String segId = elements[0].trim();

            int occurrence = segmentCounts.merge(segId, 1, Integer::sum);
            String prefix = segmentCounts.get(segId) > 1
                    ? segId + "[" + (occurrence - 1) + "]"
                    : segId;

            for (int i = 1; i < elements.length; i++) {
                result.put(prefix + "*" + String.format("%02d", i), elements[i].trim());
            }
        }

        return result;
    }

    /**
     * Parse EDIFACT into a flat map.
     */
    private Map<String, Object> parseEdifact(String content) {
        Map<String, Object> result = new LinkedHashMap<>();

        String[] segments = content.split("'");
        Map<String, Integer> segmentCounts = new LinkedHashMap<>();

        for (String segment : segments) {
            String seg = segment.trim();
            if (seg.isEmpty() || seg.startsWith("UNA")) continue;

            String[] elements = seg.split("\\+");
            String segId = elements[0].trim();

            int occurrence = segmentCounts.merge(segId, 1, Integer::sum);
            String prefix = segmentCounts.get(segId) > 1
                    ? segId + "[" + (occurrence - 1) + "]"
                    : segId;

            for (int i = 1; i < elements.length; i++) {
                String[] components = elements[i].split(":");
                if (components.length == 1) {
                    result.put(prefix + "." + String.format("%02d", i), components[0].trim());
                } else {
                    for (int j = 0; j < components.length; j++) {
                        result.put(prefix + "." + String.format("%02d", i) + "." + (j + 1),
                                components[j].trim());
                    }
                }
            }
        }

        return result;
    }

    /**
     * Parse CSV into a flat map. First line = headers, subsequent lines = data rows.
     */
    private Map<String, Object> parseCsv(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");

        if (lines.length == 0) return result;

        String[] headers = lines[0].split(",", -1);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].trim().replaceAll("^\"|\"$", "");
        }

        for (int row = 1; row < lines.length; row++) {
            String[] values = lines[row].split(",", -1);
            for (int col = 0; col < Math.min(headers.length, values.length); col++) {
                String key = lines.length > 2
                        ? "rows[" + (row - 1) + "]." + headers[col]
                        : headers[col];
                result.put(key, values[col].trim().replaceAll("^\"|\"$", ""));
            }
        }

        return result;
    }

    /**
     * Simple XML element-text extractor using regex.
     */
    private Map<String, Object> parseSimpleXml(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        String xml = content.replaceAll("<\\?xml[^?]*\\?>", "").trim();

        Pattern elementPattern = Pattern.compile("<([a-zA-Z][a-zA-Z0-9_:.-]*)(?:\\s[^>]*)?>([^<]+)</\\1>");
        Matcher matcher = elementPattern.matcher(xml);

        Map<String, Integer> elementCounts = new LinkedHashMap<>();
        while (matcher.find()) {
            String tag = matcher.group(1);
            String value = matcher.group(2).trim();
            int count = elementCounts.merge(tag, 1, Integer::sum);
            String key = count > 1 ? tag + "[" + (count - 1) + "]" : tag;
            result.put(key, value);
        }

        return result;
    }

    // ===================================================================
    // Flattening
    // ===================================================================

    /**
     * Flatten a nested map to dot-notation paths with string values.
     * e.g., {"buyer": {"name": "ACME"}} -> {"buyer.name": "ACME"}
     */
    Map<String, String> flattenToPathValues(Map<String, Object> nested) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenRecursive("", nested, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flattenRecursive(String prefix, Map<String, Object> map, Map<String, String> flat) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                flattenRecursive(key, (Map<String, Object>) value, flat);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map) {
                        flattenRecursive(key + "[" + i + "]", (Map<String, Object>) item, flat);
                    } else if (item != null) {
                        flat.put(key + "[" + i + "]", item.toString());
                    }
                }
            } else if (value != null) {
                flat.put(key, value.toString());
            }
        }
    }

    // ===================================================================
    // Map Building and Persistence
    // ===================================================================

    /**
     * Detect target type from the structure of flattened target data.
     */
    String detectTargetType(List<Map<String, String>> flatTargets) {
        if (flatTargets.isEmpty()) return "UNKNOWN";

        Set<String> allKeys = flatTargets.stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toSet());

        if (allKeys.stream().anyMatch(k -> k.matches("^[A-Z]{2,3}\\*\\d+.*"))) return "X12";
        if (allKeys.stream().anyMatch(k -> k.matches("^[A-Z]{3}\\.\\d+.*"))) return "EDIFACT";
        if (allKeys.stream().anyMatch(k -> k.startsWith("rows["))) return "CSV";
        if (allKeys.stream().anyMatch(k -> k.contains("."))) return "JSON";

        return "JSON";
    }

    private Map<String, Object> buildMapDefinition(String name, String sourceType, String targetType,
                                                     String partnerId,
                                                     List<FieldMappingCandidate> candidates,
                                                     List<LoopMappingCandidate> loops) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", name);
        def.put("sourceFormat", sourceType);
        def.put("targetFormat", targetType);
        if (partnerId != null) def.put("partnerId", partnerId);

        List<Map<String, Object>> fieldMappings = new ArrayList<>();
        for (FieldMappingCandidate c : candidates) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("sourceField", c.sourcePath);
            fm.put("targetField", c.targetPath);
            fm.put("transform", c.transform);
            if (c.transformConfig != null && !c.transformConfig.isEmpty()) {
                fm.put("transformParam", c.transformConfig.getOrDefault("param",
                        c.transformConfig.values().stream().findFirst().orElse(null)));
                fm.put("transformConfig", c.transformConfig);
            }
            fm.put("confidence", (int) Math.round(c.confidence * 100));
            fm.put("strategy", "SAMPLE_MATCH");
            fm.put("reasoning", c.reason);
            fieldMappings.add(fm);
        }
        def.put("fieldMappings", fieldMappings);

        if (!loops.isEmpty()) {
            List<Map<String, Object>> loopMappings = new ArrayList<>();
            for (LoopMappingCandidate loop : loops) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("sourcePattern", loop.sourcePattern);
                lm.put("targetPattern", loop.targetPattern);
                lm.put("sourceOccurrences", loop.sourceOccurrences);
                lm.put("targetOccurrences", loop.targetOccurrences);
                loopMappings.add(lm);
            }
            def.put("loopMappings", loopMappings);
        }

        return def;
    }

    /**
     * Generate a preview of the conversion result using the last sample's source values.
     */
    private Map<String, Object> generatePreview(List<FieldMappingCandidate> candidates,
                                                  Map<String, String> flatSource) {
        Map<String, Object> preview = new LinkedHashMap<>();
        for (FieldMappingCandidate c : candidates) {
            String sourceValue = flatSource.get(c.sourcePath);
            if (sourceValue != null) {
                String transformed = applyTransformPreview(sourceValue, c.transform, c.transformConfig);
                preview.put(c.targetPath, transformed);
            } else {
                preview.put(c.targetPath, null);
            }
        }
        return preview;
    }

    private String applyTransformPreview(String value, String transform, Map<String, String> config) {
        if (transform == null || "COPY".equals(transform) || "DIRECT".equals(transform)) {
            return value;
        }
        return switch (transform) {
            case "TRIM" -> value.trim();
            case "UPPERCASE" -> value.toUpperCase();
            case "LOWERCASE" -> value.toLowerCase();
            case "ZERO_PAD" -> {
                int len = config != null ? Integer.parseInt(config.getOrDefault("length", "10")) : 10;
                yield String.format("%" + len + "s", value).replace(' ', '0');
            }
            case "SUBSTRING" -> {
                int start = config != null ? Integer.parseInt(config.getOrDefault("start", "0")) : 0;
                int end = config != null ? Integer.parseInt(config.getOrDefault("end",
                        String.valueOf(value.length()))) : value.length();
                yield value.substring(Math.min(start, value.length()), Math.min(end, value.length()));
            }
            case "DATE_REFORMAT" -> value + " (reformatted)";
            default -> value;
        };
    }

    private ConversionMap saveAsDraft(String partnerId, String name, String sourceType,
                                      String targetType, List<FieldMappingCandidate> candidates,
                                      int sampleCount) {
        String sourceFormat = sourceType.contains("_") ? sourceType.split("_")[0] : sourceType;
        String targetFormat = targetType.contains("_") ? targetType.split("_")[0] : targetType;

        String mapKey = TrainedMapStore.buildMapKey(sourceFormat, sourceType, targetFormat, targetType, partnerId);

        List<EdiMapTrainingEngine.FieldMapping> fieldMappings = candidates.stream()
                .map(c -> EdiMapTrainingEngine.FieldMapping.builder()
                        .sourceField(c.sourcePath)
                        .targetField(c.targetPath)
                        .transform(c.transform)
                        .transformParam(c.transformConfig != null
                                ? c.transformConfig.getOrDefault("param", null) : null)
                        .confidence((int) Math.round(c.confidence * 100))
                        .strategy("SAMPLE_MATCH")
                        .reasoning(c.reason)
                        .build())
                .toList();

        String fieldMappingsJson;
        try {
            fieldMappingsJson = objectMapper.writeValueAsString(fieldMappings);
        } catch (Exception e) {
            fieldMappingsJson = "[]";
        }

        int overallConfidence = fieldMappings.isEmpty() ? 0
                : (int) Math.round(fieldMappings.stream()
                .mapToInt(EdiMapTrainingEngine.FieldMapping::getConfidence)
                .average().orElse(0));

        ConversionMap entity = ConversionMap.builder()
                .mapKey(mapKey)
                .name(name != null ? name : "Sample-built: " + sourceType + " -> " + targetType)
                .sourceFormat(sourceFormat)
                .sourceType(sourceType)
                .targetFormat(targetFormat)
                .targetType(targetType)
                .partnerId(partnerId)
                .status("DRAFT")
                .version(1)
                .active(false)
                .confidence(overallConfidence)
                .sampleCount(sampleCount)
                .fieldMappingCount(candidates.size())
                .fieldMappingsJson(fieldMappingsJson)
                .build();

        entity = mapRepo.save(entity);
        log.info("Saved DRAFT map '{}' for partner {} ({} mappings, {}% confidence)",
                mapKey, partnerId, candidates.size(), overallConfidence);
        return entity;
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /**
     * Extract the leaf field name from a path.
     * "buyer.address.city" -> "city",  "N4*01" -> "N4*01",  "lineItems[0].quantity" -> "quantity"
     */
    String extractFieldName(String path) {
        if (path == null) return "";
        String cleaned = path.replaceAll("\\[\\d+]", "").replaceAll("\\[\\*]", "");
        int lastDot = cleaned.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < cleaned.length() - 1) {
            return cleaned.substring(lastDot + 1);
        }
        return cleaned;
    }

    private String normalizeValue(String value) {
        if (value == null) return null;
        return value.trim().toLowerCase();
    }

    // ===================================================================
    // Inner Classes
    // ===================================================================

    /** A pair of input and expected output content for map building. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SamplePair {
        private String input;
        private String output;
    }

    /** A candidate field mapping discovered during sample analysis. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldMappingCandidate {
        String sourcePath;
        String targetPath;
        double confidence;
        String reason;
        String transform;
        Map<String, String> transformConfig;
    }

    /** A loop/repeating-segment mapping discovered during sample analysis. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoopMappingCandidate {
        String sourcePattern;
        String targetPattern;
        int sourceOccurrences;
        int targetOccurrences;
        double confidence;
        String reason;
    }

    /** A field with confidence below the review threshold. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LowConfidenceField {
        private String sourcePath;
        private String targetPath;
        private double confidence;
        private String reason;
    }

    /** Result of building a map from samples. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BuildResult {
        private String mapId;
        private Map<String, Object> mapDefinition;
        private Map<String, Object> preview;
        private double overallConfidence;
        private int totalFields;
        private List<String> unmappedSourceFields;
        private List<String> unmappedTargetFields;
        private List<LowConfidenceField> lowConfidenceFields;
        private int loopMappings;
        private long durationMs;
        private String error;
    }
}
