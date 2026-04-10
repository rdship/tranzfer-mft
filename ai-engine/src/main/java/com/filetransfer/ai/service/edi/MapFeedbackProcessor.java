package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes partner feedback on generated/edited EDI maps.
 *
 * The feedback loop:
 *   1. Partner reviews generated map preview
 *   2. Provides comments ("the date format is wrong") and/or structured corrections
 *   3. This service parses the feedback, applies corrections to the map
 *   4. Returns updated map + new preview
 *   5. Repeat until partner approves
 *
 * Supports both natural language comments (parsed via keyword/pattern NLP)
 * and structured field-level corrections. Each iteration targets sub-10-second
 * turnaround. After 2-3 iterations the map should be perfect.
 *
 * Pure Java NLP -- no external LLM required.
 */
@Service
@Slf4j
public class MapFeedbackProcessor {

    private final ConversionMapRepository mapRepo;
    private final TrainedMapStore trainedMapStore;
    private final FieldEmbeddingEngine fieldEmbedding;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${platform.services.edi-converter.url:http://edi-converter:8095}")
    private String ediConverterUrl;

    // --- NLP patterns for parsing natural language feedback ---

    /** "X is wrong" / "X is incorrect" / "X has the wrong value" */
    private static final Pattern WRONG_PATTERN = Pattern.compile(
            "(?:the\\s+)?([\\w.*]+)\\s+(?:is|has|looks|seems)\\s+(?:wrong|incorrect|bad|broken|off)",
            Pattern.CASE_INSENSITIVE);

    /** "X should be Y" / "X needs to be Y" */
    private static final Pattern SHOULD_BE_PATTERN = Pattern.compile(
            "(?:the\\s+)?([\\w.*]+)\\s+(?:should|needs to|must)\\s+(?:be|equal|show|read)\\s+['\"]?(.+?)['\"]?$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** "wrong date format" / "date format should be X" */
    private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile(
            "(?:date|format).*?(MM/dd/yyyy|yyyy-MM-dd|dd/MM/yyyy|MM-dd-yyyy|yyyyMMdd|dd\\.MM\\.yyyy)",
            Pattern.CASE_INSENSITIVE);

    /** "X should come from Y" / "X maps from the wrong field" */
    private static final Pattern WRONG_SOURCE_PATTERN = Pattern.compile(
            "(?:the\\s+)?([\\w.*]+)\\s+(?:should|needs to)\\s+(?:come|be taken|be read|map)\\s+from\\s+([\\w.*]+)",
            Pattern.CASE_INSENSITIVE);

    /** "swap X and Y" / "X and Y are swapped" */
    private static final Pattern SWAP_PATTERN = Pattern.compile(
            "(?:swap|switch|flip)\\s+([\\w.*]+)\\s+(?:and|with)\\s+([\\w.*]+)",
            Pattern.CASE_INSENSITIVE);

    /** "remove X" / "don't need X" / "X is not needed" */
    private static final Pattern REMOVE_PATTERN = Pattern.compile(
            "(?:remove|delete|drop|don'?t\\s+(?:need|want|include))\\s+(?:the\\s+)?([\\w.*]+)",
            Pattern.CASE_INSENSITIVE);

    /** "add X" / "missing X" / "X is missing" */
    private static final Pattern MISSING_PATTERN = Pattern.compile(
            "(?:add|include|missing|need)\\s+(?:the\\s+)?(?:field\\s+)?([\\w.*]+)",
            Pattern.CASE_INSENSITIVE);

    /** "uppercase X" / "make X uppercase" / "X should be uppercase" */
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "(?:make\\s+)?(?:the\\s+)?([\\w.*]+)\\s+(?:should\\s+be\\s+)?(?:be\\s+)?(uppercase|lowercase|upper|lower)",
            Pattern.CASE_INSENSITIVE);

    /** "trim X" / "X has extra spaces" */
    private static final Pattern TRIM_PATTERN = Pattern.compile(
            "(?:trim|strip)\\s+(?:the\\s+)?([\\w.*]+)|([\\w.*]+)\\s+(?:has|have)\\s+(?:extra|leading|trailing)\\s+(?:spaces|whitespace)",
            Pattern.CASE_INSENSITIVE);

    /** "pad X to N digits" / "X should be N characters" */
    private static final Pattern PAD_PATTERN = Pattern.compile(
            "(?:pad|zero-?pad)\\s+(?:the\\s+)?([\\w.*]+)\\s+(?:to\\s+)?(\\d+)",
            Pattern.CASE_INSENSITIVE);

    public MapFeedbackProcessor(ConversionMapRepository mapRepo,
                                 TrainedMapStore trainedMapStore,
                                 FieldEmbeddingEngine fieldEmbedding,
                                 ObjectMapper objectMapper) {
        this.mapRepo = mapRepo;
        this.trainedMapStore = trainedMapStore;
        this.fieldEmbedding = fieldEmbedding;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    // ===================================================================
    // Public API
    // ===================================================================

    /**
     * Process partner feedback using raw map-based corrections (controller-friendly).
     * Converts {@code List<Map<String,String>>} to {@link FieldCorrection} objects.
     */
    @Transactional
    public FeedbackResult processFeedback(String mapId, String comments,
                                           List<Map<String, String>> corrections) {
        List<FieldCorrection> typed = new ArrayList<>();
        if (corrections != null) {
            for (Map<String, String> c : corrections) {
                typed.add(new FieldCorrection(
                        c.getOrDefault("targetField", c.getOrDefault("field", "")),
                        c.get("newSourceField"),
                        c.getOrDefault("expectedValue", c.get("expected")),
                        c.getOrDefault("gotValue", c.get("got")),
                        c.get("action"),
                        c.get("transformParam")
                ));
            }
        }
        return processFeedbackTyped(mapId, comments, typed);
    }

    /**
     * Process partner feedback and return corrected map.
     *
     * @param mapId       ID of the map being reviewed
     * @param comments    free-form natural language comments (may be null)
     * @param corrections structured field-level corrections (may be empty)
     * @return result with AI reply, updated map, new preview, and remaining issues
     */
    @Transactional
    public FeedbackResult processFeedbackTyped(String mapId, String comments,
                                                List<FieldCorrection> corrections) {
        Instant start = Instant.now();

        log.info("Processing feedback for map={}: comments='{}', corrections={}",
                mapId, truncate(comments, 80), corrections != null ? corrections.size() : 0);

        // Load current map
        ConversionMap map = loadMap(mapId);
        List<MappingEntry> currentMappings = deserializeMappings(map.getFieldMappingsJson());

        // Phase 1: Parse natural language comments into correction actions
        List<CorrectionAction> actions = new ArrayList<>();
        if (comments != null && !comments.isBlank()) {
            actions.addAll(parseComments(comments, currentMappings));
        }

        // Phase 2: Convert structured corrections into actions
        if (corrections != null) {
            actions.addAll(fromStructuredCorrections(corrections, currentMappings));
        }

        if (actions.isEmpty()) {
            return FeedbackResult.builder()
                    .reply("I didn't detect specific corrections in your feedback. Try phrases like:\n"
                            + "  - 'The date format is wrong, should be MM/dd/yyyy'\n"
                            + "  - 'Sender name should come from NM1*03'\n"
                            + "  - 'Remove the address field'\n"
                            + "  - 'Add the missing ZIP code'\n"
                            + "Or provide structured corrections with field/expected/got values.")
                    .updatedMap(toMapSummary(map))
                    .preview(Map.of())
                    .correctionsApplied(0)
                    .remainingIssues(List.of())
                    .build();
        }

        // Phase 3: Apply corrections to the field mappings
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (CorrectionAction action : actions) {
            boolean success = applyAction(action, currentMappings);
            if (success) {
                applied.add(action.description);
            } else {
                skipped.add(action.description + " (could not find matching field)");
            }
        }

        // Phase 4: Serialize updated mappings and persist
        String updatedJson = serializeMappings(currentMappings);
        map.setFieldMappingsJson(updatedJson);
        map.setFieldMappingCount(currentMappings.size());
        map.setVersion(map.getVersion() + 1);
        map.setStatus("DRAFT");
        map = mapRepo.save(map);

        // Phase 5: Generate preview
        Map<String, Object> preview = generatePreview(map);

        // Phase 6: Detect remaining issues
        List<String> remaining = detectRemainingIssues(currentMappings);

        // Phase 7: Build reply
        String reply = buildReply(applied, skipped, remaining);

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        log.info("Feedback processed for map={}: {} applied, {} skipped, {} remaining, {}ms",
                mapId, applied.size(), skipped.size(), remaining.size(), durationMs);

        return FeedbackResult.builder()
                .reply(reply)
                .updatedMap(toMapSummary(map))
                .preview(preview)
                .correctionsApplied(applied.size())
                .appliedDescriptions(applied)
                .skippedDescriptions(skipped)
                .remainingIssues(remaining)
                .newVersion(map.getVersion())
                .durationMs(durationMs)
                .build();
    }

    /**
     * Approve a map after feedback iterations. Sets status to ACTIVE.
     */
    @Transactional
    public ApprovalResult approve(String mapId) {
        ConversionMap map = loadMap(mapId);

        // Deactivate previous versions for same mapKey
        mapRepo.deactivateAllByMapKey(map.getMapKey());

        map.setActive(true);
        map.setStatus("ACTIVE");
        map = mapRepo.save(map);

        trainedMapStore.refreshCache();

        log.info("Map {} approved and activated (v{}, {} mappings)",
                mapId, map.getVersion(), map.getFieldMappingCount());

        return ApprovalResult.builder()
                .mapId(mapId)
                .mapKey(map.getMapKey())
                .version(map.getVersion())
                .status("ACTIVE")
                .fieldMappingCount(map.getFieldMappingCount())
                .message("Map approved and activated. It will be used for all future conversions"
                        + (map.getPartnerId() != null ? " for partner " + map.getPartnerId() : "") + ".")
                .build();
    }

    /**
     * Reject a map -- marks it as rejected so it is not used.
     */
    @Transactional
    public void reject(String mapId, String reason) {
        ConversionMap map = loadMap(mapId);
        map.setStatus("REJECTED");
        map.setActive(false);
        mapRepo.save(map);
        log.info("Map {} rejected: {}", mapId, reason);
    }

    // ===================================================================
    // Comment Parsing (Natural Language -> CorrectionActions)
    // ===================================================================

    /**
     * Parse free-form natural language comments into actionable corrections.
     * Processes each sentence independently, supports multiple corrections per comment.
     */
    List<CorrectionAction> parseComments(String comments, List<MappingEntry> currentMappings) {
        List<CorrectionAction> actions = new ArrayList<>();

        // Split on sentence boundaries
        String[] sentences = comments.split("[.!;\\n]+");

        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;

            // Try each pattern in priority order

            // "X should come from Y" -> change source field
            Matcher wrongSource = WRONG_SOURCE_PATTERN.matcher(s);
            if (wrongSource.find()) {
                String targetRef = wrongSource.group(1);
                String newSourceRef = wrongSource.group(2);
                String targetField = resolveFieldRef(targetRef, currentMappings, false);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.CHANGE_SOURCE)
                        .targetField(targetField)
                        .newValue(newSourceRef)
                        .description("Change source for " + targetField + " to " + newSourceRef)
                        .build());
                continue;
            }

            // "swap X and Y"
            Matcher swap = SWAP_PATTERN.matcher(s);
            if (swap.find()) {
                String field1 = resolveFieldRef(swap.group(1), currentMappings, true);
                String field2 = resolveFieldRef(swap.group(2), currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.SWAP_FIELDS)
                        .targetField(field1)
                        .newValue(field2)
                        .description("Swap mappings for " + field1 + " and " + field2)
                        .build());
                continue;
            }

            // "X should be Y" -> set expected value / change target
            Matcher shouldBe = SHOULD_BE_PATTERN.matcher(s);
            if (shouldBe.find()) {
                String fieldRef = shouldBe.group(1);
                String expectedValue = shouldBe.group(2).trim();
                String field = resolveFieldRef(fieldRef, currentMappings, true);

                // If the expected value looks like a field path, treat as source change
                if (expectedValue.contains("*") || expectedValue.contains(".")) {
                    actions.add(CorrectionAction.builder()
                            .type(ActionType.CHANGE_SOURCE)
                            .targetField(field)
                            .newValue(expectedValue)
                            .description("Change source for " + field + " to " + expectedValue)
                            .build());
                } else {
                    // Treat as a constant/default value
                    actions.add(CorrectionAction.builder()
                            .type(ActionType.SET_DEFAULT)
                            .targetField(field)
                            .newValue(expectedValue)
                            .description("Set default value for " + field + " to '" + expectedValue + "'")
                            .build());
                }
                continue;
            }

            // Date format change
            Matcher dateFormat = DATE_FORMAT_PATTERN.matcher(s);
            if (dateFormat.find()) {
                String targetDateFormat = dateFormat.group(1);
                // Find date fields to apply to
                List<String> dateFields = findDateFields(currentMappings);
                for (String df : dateFields) {
                    actions.add(CorrectionAction.builder()
                            .type(ActionType.CHANGE_TRANSFORM)
                            .targetField(df)
                            .newValue("DATE_REFORMAT")
                            .transformParam("yyyyMMdd->" + targetDateFormat)
                            .description("Change date format for " + df + " to " + targetDateFormat)
                            .build());
                }
                if (dateFields.isEmpty()) {
                    actions.add(CorrectionAction.builder()
                            .type(ActionType.CHANGE_TRANSFORM)
                            .targetField("*date*")
                            .newValue("DATE_REFORMAT")
                            .transformParam("yyyyMMdd->" + targetDateFormat)
                            .description("Change date format to " + targetDateFormat
                                    + " (no date fields found yet)")
                            .build());
                }
                continue;
            }

            // "X is wrong" -> flag for review
            Matcher wrong = WRONG_PATTERN.matcher(s);
            if (wrong.find()) {
                String fieldRef = wrong.group(1);
                String field = resolveFieldRef(fieldRef, currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.FLAG_WRONG)
                        .targetField(field)
                        .description(field + " is wrong (needs manual review or more detail)")
                        .build());
                continue;
            }

            // "remove X"
            Matcher remove = REMOVE_PATTERN.matcher(s);
            if (remove.find()) {
                String fieldRef = remove.group(1);
                String field = resolveFieldRef(fieldRef, currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.REMOVE)
                        .targetField(field)
                        .description("Remove mapping for " + field)
                        .build());
                continue;
            }

            // "add X" / "missing X"
            Matcher missing = MISSING_PATTERN.matcher(s);
            if (missing.find()) {
                String fieldRef = missing.group(1);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.ADD)
                        .targetField(fieldRef)
                        .description("Add missing field: " + fieldRef)
                        .build());
                continue;
            }

            // "make X uppercase/lowercase"
            Matcher caseChange = CASE_PATTERN.matcher(s);
            if (caseChange.find()) {
                String fieldRef = caseChange.group(1);
                String caseType = caseChange.group(2).toUpperCase();
                if (caseType.equals("UPPER")) caseType = "UPPERCASE";
                if (caseType.equals("LOWER")) caseType = "LOWERCASE";
                String field = resolveFieldRef(fieldRef, currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.CHANGE_TRANSFORM)
                        .targetField(field)
                        .newValue(caseType)
                        .description("Apply " + caseType + " to " + field)
                        .build());
                continue;
            }

            // "trim X"
            Matcher trim = TRIM_PATTERN.matcher(s);
            if (trim.find()) {
                String fieldRef = trim.group(1) != null ? trim.group(1) : trim.group(2);
                String field = resolveFieldRef(fieldRef, currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.CHANGE_TRANSFORM)
                        .targetField(field)
                        .newValue("TRIM")
                        .description("Trim whitespace from " + field)
                        .build());
                continue;
            }

            // "pad X to N"
            Matcher pad = PAD_PATTERN.matcher(s);
            if (pad.find()) {
                String fieldRef = pad.group(1);
                String length = pad.group(2);
                String field = resolveFieldRef(fieldRef, currentMappings, true);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.CHANGE_TRANSFORM)
                        .targetField(field)
                        .newValue("ZERO_PAD")
                        .transformParam(length)
                        .description("Zero-pad " + field + " to " + length + " characters")
                        .build());
            }
        }

        return actions;
    }

    // ===================================================================
    // Structured Corrections
    // ===================================================================

    private List<CorrectionAction> fromStructuredCorrections(List<FieldCorrection> corrections,
                                                              List<MappingEntry> currentMappings) {
        List<CorrectionAction> actions = new ArrayList<>();
        for (FieldCorrection c : corrections) {
            if (c.getAction() != null && !c.getAction().isBlank()) {
                // Explicit action type
                ActionType type = parseActionType(c.getAction());
                actions.add(CorrectionAction.builder()
                        .type(type)
                        .targetField(c.getTargetField())
                        .newValue(c.getNewSourceField() != null ? c.getNewSourceField() : c.getExpectedValue())
                        .transformParam(c.getTransformParam())
                        .description("Structured correction: " + c.getAction()
                                + " on " + c.getTargetField())
                        .build());
            } else if (c.getNewSourceField() != null) {
                // Change source field
                actions.add(CorrectionAction.builder()
                        .type(ActionType.CHANGE_SOURCE)
                        .targetField(c.getTargetField())
                        .newValue(c.getNewSourceField())
                        .description("Change source for " + c.getTargetField()
                                + " to " + c.getNewSourceField())
                        .build());
            } else if (c.getExpectedValue() != null && c.getGotValue() != null) {
                // Value mismatch -- the source mapping is probably wrong
                String targetField = resolveFieldRef(c.getTargetField(), currentMappings, false);
                actions.add(CorrectionAction.builder()
                        .type(ActionType.FLAG_WRONG)
                        .targetField(targetField)
                        .newValue(c.getExpectedValue())
                        .description("Value mismatch on " + targetField
                                + ": expected '" + c.getExpectedValue()
                                + "', got '" + c.getGotValue() + "'")
                        .build());
            }
        }
        return actions;
    }

    // ===================================================================
    // Action Application
    // ===================================================================

    /**
     * Apply a single correction action to the mapping list. Returns true if applied.
     */
    private boolean applyAction(CorrectionAction action, List<MappingEntry> mappings) {
        return switch (action.type) {
            case CHANGE_SOURCE -> {
                Optional<MappingEntry> entry = findMapping(action.targetField, mappings);
                if (entry.isPresent()) {
                    entry.get().sourceField = action.newValue;
                    entry.get().confidence = 100;
                    entry.get().strategy = "PARTNER_FEEDBACK";
                    entry.get().reasoning = action.description;
                    yield true;
                }
                yield false;
            }

            case CHANGE_TRANSFORM -> {
                Optional<MappingEntry> entry = findMapping(action.targetField, mappings);
                if (entry.isPresent()) {
                    entry.get().transform = action.newValue;
                    if (action.transformParam != null) {
                        entry.get().transformParam = action.transformParam;
                    }
                    entry.get().confidence = 100;
                    yield true;
                }
                yield false;
            }

            case SET_DEFAULT -> {
                Optional<MappingEntry> entry = findMapping(action.targetField, mappings);
                if (entry.isPresent()) {
                    entry.get().transform = "CONSTANT";
                    entry.get().transformParam = action.newValue;
                    entry.get().confidence = 100;
                    entry.get().reasoning = "Partner set constant value: " + action.newValue;
                    yield true;
                } else {
                    // Add new mapping with constant value
                    MappingEntry newEntry = new MappingEntry();
                    newEntry.sourceField = "_constant";
                    newEntry.targetField = action.targetField;
                    newEntry.transform = "CONSTANT";
                    newEntry.transformParam = action.newValue;
                    newEntry.confidence = 100;
                    newEntry.strategy = "PARTNER_FEEDBACK";
                    newEntry.reasoning = "Partner set constant value: " + action.newValue;
                    mappings.add(newEntry);
                    yield true;
                }
            }

            case REMOVE -> {
                boolean removed = mappings.removeIf(m ->
                        m.targetField.equalsIgnoreCase(action.targetField)
                                || m.sourceField.equalsIgnoreCase(action.targetField));
                yield removed;
            }

            case ADD -> {
                // Try to auto-suggest a source field using semantic similarity
                String suggestedSource = suggestSourceField(action.targetField, mappings);
                MappingEntry newEntry = new MappingEntry();
                newEntry.sourceField = suggestedSource != null ? suggestedSource : action.targetField;
                newEntry.targetField = action.targetField;
                newEntry.transform = "DIRECT";
                newEntry.confidence = suggestedSource != null ? 70 : 50;
                newEntry.strategy = "PARTNER_FEEDBACK";
                newEntry.reasoning = suggestedSource != null
                        ? "Added per partner request, source auto-suggested"
                        : "Added per partner request, source needs verification";
                mappings.add(newEntry);
                yield true;
            }

            case SWAP_FIELDS -> {
                Optional<MappingEntry> entry1 = findMapping(action.targetField, mappings);
                Optional<MappingEntry> entry2 = findMapping(action.newValue, mappings);
                if (entry1.isPresent() && entry2.isPresent()) {
                    String tempSource = entry1.get().sourceField;
                    entry1.get().sourceField = entry2.get().sourceField;
                    entry2.get().sourceField = tempSource;
                    entry1.get().confidence = 100;
                    entry2.get().confidence = 100;
                    yield true;
                }
                yield false;
            }

            case FLAG_WRONG -> {
                // Lower confidence and add note -- partner needs to provide more detail
                Optional<MappingEntry> entry = findMapping(action.targetField, mappings);
                if (entry.isPresent()) {
                    entry.get().confidence = Math.min(entry.get().confidence, 30);
                    entry.get().reasoning = "Flagged as wrong by partner: " + action.description;
                    yield true;
                }
                yield false;
            }
        };
    }

    // ===================================================================
    // Field Resolution and Lookup
    // ===================================================================

    /**
     * Resolve a field reference from natural language to an actual field in the mappings.
     * Uses exact match, partial match, then semantic similarity.
     *
     * @param ref         the reference from the partner's comment
     * @param mappings    current mapping entries
     * @param checkSource if true, also matches against source fields
     */
    private String resolveFieldRef(String ref, List<MappingEntry> mappings, boolean checkSource) {
        if (ref == null) return ref;
        String cleaned = ref.trim().replaceAll("^['\"]|['\"]$", "");

        // Collect all known fields
        List<String> allFields = new ArrayList<>();
        for (MappingEntry m : mappings) {
            allFields.add(m.targetField);
            if (checkSource) allFields.add(m.sourceField);
        }

        // Exact match
        for (String field : allFields) {
            if (field.equalsIgnoreCase(cleaned)) return field;
        }

        // Partial match
        for (String field : allFields) {
            if (field.toLowerCase().contains(cleaned.toLowerCase())
                    || cleaned.toLowerCase().contains(field.toLowerCase())) {
                return field;
            }
        }

        // Semantic similarity
        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(cleaned, allFields, 0.4);
        if (best.isPresent()) {
            return best.get().targetField();
        }

        // Return as-is
        return cleaned;
    }

    private Optional<MappingEntry> findMapping(String fieldRef, List<MappingEntry> mappings) {
        String lower = fieldRef.toLowerCase().trim();

        // Exact match on target or source
        for (MappingEntry m : mappings) {
            if (m.targetField.equalsIgnoreCase(lower) || m.sourceField.equalsIgnoreCase(lower)) {
                return Optional.of(m);
            }
        }

        // Partial match
        for (MappingEntry m : mappings) {
            if (m.targetField.toLowerCase().contains(lower)
                    || lower.contains(m.targetField.toLowerCase())
                    || m.sourceField.toLowerCase().contains(lower)
                    || lower.contains(m.sourceField.toLowerCase())) {
                return Optional.of(m);
            }
        }

        // Semantic match
        List<String> targetFields = mappings.stream().map(m -> m.targetField).toList();
        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(lower, targetFields, 0.4);
        if (best.isPresent()) {
            String matched = best.get().targetField();
            return mappings.stream()
                    .filter(m -> m.targetField.equals(matched))
                    .findFirst();
        }

        return Optional.empty();
    }

    private List<String> findDateFields(List<MappingEntry> mappings) {
        return mappings.stream()
                .filter(m -> m.targetField.toLowerCase().contains("date")
                        || m.sourceField.toLowerCase().contains("date")
                        || "DATE_REFORMAT".equals(m.transform))
                .map(m -> m.targetField)
                .toList();
    }

    /**
     * Auto-suggest a source field for a target field name using semantic similarity
     * against existing mapped source fields.
     */
    private String suggestSourceField(String targetField, List<MappingEntry> mappings) {
        // Use existing mapped source fields as candidates
        List<String> sourceFields = mappings.stream()
                .map(m -> m.sourceField)
                .distinct()
                .toList();

        if (sourceFields.isEmpty()) return null;

        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(targetField, sourceFields, 0.3);
        return best.map(FieldEmbeddingEngine.FieldMatch::targetField).orElse(null);
    }

    // ===================================================================
    // Preview and Issue Detection
    // ===================================================================

    /**
     * Generate a preview by calling edi-converter's test-mappings endpoint.
     * Falls back to a summary if the converter is unavailable.
     */
    private Map<String, Object> generatePreview(ConversionMap map) {
        try {
            List<Map<String, Object>> fieldMappings = objectMapper.readValue(
                    map.getFieldMappingsJson(), new TypeReference<>() {});

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldMappings", fieldMappings);
            body.put("targetFormat", map.getTargetFormat());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    ediConverterUrl + "/api/v1/convert/test-mappings", body, Map.class);
            if (result != null) return result;
        } catch (Exception e) {
            log.debug("Preview generation via converter unavailable: {}", e.getMessage());
        }

        // Fallback: just show the mapping summary
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("note", "Live preview unavailable (edi-converter not reachable)");
        preview.put("mappingCount", map.getFieldMappingCount());
        preview.put("status", map.getStatus());
        preview.put("version", map.getVersion());
        return preview;
    }

    /**
     * Detect remaining issues in the mappings that the partner should review.
     */
    List<String> detectRemainingIssues(List<MappingEntry> mappings) {
        List<String> issues = new ArrayList<>();

        // Low confidence fields
        List<MappingEntry> lowConf = mappings.stream()
                .filter(m -> m.confidence < 50)
                .toList();
        for (MappingEntry m : lowConf) {
            issues.add("Low confidence (" + m.confidence + "%): "
                    + m.sourceField + " -> " + m.targetField);
        }

        // Duplicate target fields
        Map<String, Long> targetCounts = mappings.stream()
                .collect(Collectors.groupingBy(m -> m.targetField.toLowerCase(), Collectors.counting()));
        for (Map.Entry<String, Long> entry : targetCounts.entrySet()) {
            if (entry.getValue() > 1) {
                issues.add("Duplicate target field: " + entry.getKey()
                        + " (mapped " + entry.getValue() + " times)");
            }
        }

        // Fields marked as wrong
        for (MappingEntry m : mappings) {
            if (m.reasoning != null && m.reasoning.contains("Flagged as wrong")) {
                issues.add("Flagged: " + m.targetField + " needs correction");
            }
        }

        // Fields with CONSTANT transform that look like they should be dynamic
        for (MappingEntry m : mappings) {
            if ("CONSTANT".equals(m.transform) && m.confidence < 80) {
                issues.add("Constant value for " + m.targetField
                        + " -- verify this shouldn't be dynamic");
            }
        }

        return issues;
    }

    // ===================================================================
    // Serialization
    // ===================================================================

    private ConversionMap loadMap(String mapId) {
        UUID id;
        try {
            id = UUID.fromString(mapId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid map ID: " + mapId);
        }
        return mapRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));
    }

    private List<MappingEntry> deserializeMappings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<MappingEntry>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize mappings: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serializeMappings(List<MappingEntry> mappings) {
        try {
            return objectMapper.writeValueAsString(mappings);
        } catch (Exception e) {
            log.warn("Failed to serialize mappings: {}", e.getMessage());
            return "[]";
        }
    }

    private Map<String, Object> toMapSummary(ConversionMap map) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mapId", map.getId().toString());
        summary.put("mapKey", map.getMapKey());
        summary.put("name", map.getName());
        summary.put("version", map.getVersion());
        summary.put("status", map.getStatus());
        summary.put("fieldMappingCount", map.getFieldMappingCount());
        summary.put("confidence", map.getConfidence());
        return summary;
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private ActionType parseActionType(String action) {
        return switch (action.toUpperCase()) {
            case "CHANGE_SOURCE", "MODIFY" -> ActionType.CHANGE_SOURCE;
            case "CHANGE_TRANSFORM", "FORMAT" -> ActionType.CHANGE_TRANSFORM;
            case "SET_DEFAULT", "CONSTANT" -> ActionType.SET_DEFAULT;
            case "REMOVE", "DELETE" -> ActionType.REMOVE;
            case "ADD" -> ActionType.ADD;
            case "SWAP" -> ActionType.SWAP_FIELDS;
            default -> ActionType.FLAG_WRONG;
        };
    }

    private String buildReply(List<String> applied, List<String> skipped, List<String> remaining) {
        StringBuilder sb = new StringBuilder();

        if (!applied.isEmpty()) {
            sb.append("Applied ").append(applied.size()).append(" correction(s):\n");
            for (String a : applied) {
                sb.append("  - ").append(a).append("\n");
            }
        }

        if (!skipped.isEmpty()) {
            sb.append("\nCould not apply ").append(skipped.size()).append(" correction(s):\n");
            for (String s : skipped) {
                sb.append("  - ").append(s).append("\n");
            }
        }

        if (remaining.isEmpty()) {
            sb.append("\nAll fields look good. Ready for approval.");
        } else {
            sb.append("\n").append(remaining.size()).append(" issue(s) remain:\n");
            for (String r : remaining) {
                sb.append("  - ").append(r).append("\n");
            }
        }

        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ===================================================================
    // Inner Classes
    // ===================================================================

    enum ActionType {
        CHANGE_SOURCE,
        CHANGE_TRANSFORM,
        SET_DEFAULT,
        REMOVE,
        ADD,
        SWAP_FIELDS,
        FLAG_WRONG
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class CorrectionAction {
        ActionType type;
        String targetField;
        String newValue;
        String transformParam;
        String description;
    }

    /** Mutable mapping entry for in-place modification during feedback processing. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingEntry {
        String sourceField;
        String targetField;
        String transform;
        String transformParam;
        int confidence;
        String strategy;
        String reasoning;
    }

    /** Structured field-level correction from the partner. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldCorrection {
        /** The target field being corrected */
        private String targetField;
        /** New source field to map from (if changing the source) */
        private String newSourceField;
        /** Expected value (for mismatch reporting) */
        private String expectedValue;
        /** Value that was produced (for mismatch reporting) */
        private String gotValue;
        /** Explicit action: CHANGE_SOURCE, CHANGE_TRANSFORM, REMOVE, ADD, SWAP */
        private String action;
        /** Transform parameter (e.g., date format pattern) */
        private String transformParam;
    }

    /** Result of processing feedback. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedbackResult {
        private String reply;
        private Map<String, Object> updatedMap;
        private Map<String, Object> preview;
        private int correctionsApplied;
        private List<String> appliedDescriptions;
        private List<String> skippedDescriptions;
        private List<String> remainingIssues;
        private int newVersion;
        private long durationMs;
    }

    /** Result of approving a map. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalResult {
        private String mapId;
        private String mapKey;
        private int version;
        private String status;
        private int fieldMappingCount;
        private String message;
    }
}
