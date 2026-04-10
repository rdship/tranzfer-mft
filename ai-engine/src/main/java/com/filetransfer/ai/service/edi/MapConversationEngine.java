package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Conversational AI engine for map editing.
 * Understands natural language requests and translates them to map actions.
 *
 * Intent categories:
 *   ADD       - "add sender name", "map BEG*03 to poNumber", "include the date"
 *   REMOVE    - "remove the address", "delete sender mapping", "disconnect city"
 *   MODIFY    - "change the source for name", "fix the invoice number", "update target"
 *   FORMAT    - "format date as MM/dd/yyyy", "dates should be yyyy-MM-dd"
 *   COMBINE   - "combine first name and last name", "merge address lines"
 *   COMPUTE   - "calculate total from qty * price", "sum all line amounts"
 *   CONDITION - "if qualifier is BY then map to buyer", "only when type = 00"
 *   QUERY     - "show unmapped fields", "which fields are mapped?", "what's missing?"
 *   GENERAL   - anything else: best-effort interpretation
 *
 * Pure Java NLP using keyword extraction, regex patterns, and
 * {@link FieldEmbeddingEngine} for semantic field matching.
 * No external LLM required.
 */
@Service
@Slf4j
public class MapConversationEngine {

    private final SchemaMapGenerator schemaGenerator;
    private final FieldEmbeddingEngine fieldEmbedding;
    private final TrainedMapStore trainedMapStore;
    private final ObjectMapper objectMapper;

    // --- Regex patterns for field extraction ---
    private static final Pattern FIELD_REF = Pattern.compile(
            "([A-Z]{2,4}[*.]\\d{1,3}(?:[*.]\\d{1,3})?|[a-zA-Z][a-zA-Z0-9_.]+)");
    private static final Pattern MAP_TO = Pattern.compile(
            "(?:map|set|assign|link|connect)\\s+(.+?)\\s+(?:to|->|=>|as)\\s+(.+?)(?:\\s*$|\\s+(?:with|using))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COME_FROM = Pattern.compile(
            "(.+?)\\s+(?:should|must|needs to)\\s+(?:come|be taken|be read|map)\\s+from\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_FMT = Pattern.compile(
            "(MM/dd/yyyy|yyyy-MM-dd|dd/MM/yyyy|MM-dd-yyyy|yyyyMMdd|dd\\.MM\\.yyyy)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMBINE_PATTERN = Pattern.compile(
            "(?:combine|concat|merge|join)\\s+(.+?)\\s+(?:and|with|&|\\+)\\s+(.+?)(?:\\s+(?:into|as|to)\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPUTE_PATTERN = Pattern.compile(
            "(?:calculate|compute|sum|total|multiply|divide)\\s+(.+?)(?:\\s+(?:from|as|using)\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "(?:if|when|only when|only if)\\s+(.+?)\\s+(?:then|,)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE_PATTERN = Pattern.compile(
            "(?:remove|delete|drop|disconnect|unmap)\\s+(?:the\\s+)?(?:mapping\\s+(?:for\\s+)?)?(.+)",
            Pattern.CASE_INSENSITIVE);

    public MapConversationEngine(SchemaMapGenerator schemaGenerator,
                                  FieldEmbeddingEngine fieldEmbedding,
                                  TrainedMapStore trainedMapStore,
                                  ObjectMapper objectMapper) {
        this.schemaGenerator = schemaGenerator;
        this.fieldEmbedding = fieldEmbedding;
        this.trainedMapStore = trainedMapStore;
        this.objectMapper = objectMapper;
    }

    // ===================================================================
    // Public API
    // ===================================================================

    /**
     * Process a chat message and return actions + reply.
     */
    public ChatResponse processMessage(ChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return ChatResponse.builder()
                    .reply("Please describe what you'd like to change in the map.")
                    .actions(List.of())
                    .confidence(0)
                    .build();
        }

        log.info("Processing chat for map={}: '{}'", request.getMapId(), truncate(message, 80));

        String lower = message.toLowerCase().trim();

        // Parse intent and delegate to handler
        if (containsAny(lower, "add", "map", "include", "connect", "link")
                && !containsAny(lower, "remove", "delete", "show", "what")) {
            return handleAddMapping(request);
        } else if (containsAny(lower, "remove", "delete", "disconnect", "unmap", "drop")) {
            return handleRemoveMapping(request);
        } else if (containsAny(lower, "change", "rename", "update", "modify", "fix", "replace", "swap")) {
            return handleModifyMapping(request);
        } else if (containsAny(lower, "format", "date", "should be") && DATE_FMT.matcher(lower).find()) {
            return handleFormatChange(request);
        } else if (containsAny(lower, "combine", "concat", "merge", "join")) {
            return handleCombineFields(request);
        } else if (containsAny(lower, "calculate", "sum", "total", "compute", "multiply")) {
            return handleComputeField(request);
        } else if (CONDITION_PATTERN.matcher(lower).find()) {
            return handleConditionalMapping(request);
        } else if (containsAny(lower, "what", "show", "which", "list", "unmapped", "missing", "how many")) {
            return handleQuery(request);
        } else {
            return handleGenericRequest(request);
        }
    }

    // ===================================================================
    // Intent Handlers
    // ===================================================================

    private ChatResponse handleAddMapping(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        // Try "map X to Y" pattern
        Matcher mapTo = MAP_TO.matcher(msg);
        if (mapTo.find()) {
            String sourceRef = mapTo.group(1).trim();
            String targetRef = mapTo.group(2).trim();
            String sourceField = resolveField(sourceRef, getSourceFields(request), true);
            String targetField = resolveField(targetRef, getTargetFields(request), false);

            actions.add(MapAction.builder()
                    .type("ADD")
                    .sourceField(sourceField)
                    .targetField(targetField)
                    .transform("DIRECT")
                    .confidence(0.9)
                    .reasoning("Parsed 'map " + sourceRef + " to " + targetRef + "'")
                    .build());

            return ChatResponse.builder()
                    .reply("Added mapping: " + sourceField + " -> " + targetField + ".")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.9)
                    .suggestedFollowUp("Would you like to add more mappings or see the full preview?")
                    .build();
        }

        // Try "Y should come from X" pattern
        Matcher comeFrom = COME_FROM.matcher(msg);
        if (comeFrom.find()) {
            String targetRef = comeFrom.group(1).trim();
            String sourceRef = comeFrom.group(2).trim();
            String sourceField = resolveField(sourceRef, getSourceFields(request), true);
            String targetField = resolveField(targetRef, getTargetFields(request), false);

            actions.add(MapAction.builder()
                    .type("ADD")
                    .sourceField(sourceField)
                    .targetField(targetField)
                    .transform("DIRECT")
                    .confidence(0.85)
                    .reasoning("Parsed '" + targetRef + " should come from " + sourceRef + "'")
                    .build());

            return ChatResponse.builder()
                    .reply("Added mapping: " + sourceField + " -> " + targetField + ".")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.85)
                    .suggestedFollowUp("Does this look correct? You can say 'show mappings' to see all.")
                    .build();
        }

        // Extract field references from the message and try to find matches
        List<String> fieldRefs = extractFieldReferences(msg);
        if (!fieldRefs.isEmpty()) {
            String fieldRef = fieldRefs.get(0);
            String sourceField = resolveField(fieldRef, getSourceFields(request), true);
            // Auto-suggest a target field using semantic similarity
            String targetField = suggestTargetField(sourceField, getTargetFields(request), request);

            if (targetField != null) {
                actions.add(MapAction.builder()
                        .type("ADD")
                        .sourceField(sourceField)
                        .targetField(targetField)
                        .transform("DIRECT")
                        .confidence(0.7)
                        .reasoning("Field reference extracted, target auto-suggested by similarity")
                        .build());

                return ChatResponse.builder()
                        .reply("I'll add a mapping from " + sourceField + " to " + targetField
                                + ". Does this look right?")
                        .actions(toActionMaps(actions))
                        .preview(generateActionPreview(request, actions))
                        .confidence(0.7)
                        .suggestedFollowUp("Say 'yes' to confirm, or specify the correct target field.")
                        .build();
            }
        }

        return ChatResponse.builder()
                .reply("I want to add a mapping, but I need more detail. "
                        + "Try: 'Map BEG*03 to poNumber' or 'Add sender name from NM1*02'.")
                .actions(List.of())
                .confidence(0.3)
                .suggestedFollowUp("Which source field should I map, and where should it go?")
                .build();
    }

    private ChatResponse handleRemoveMapping(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        Matcher removeMatcher = REMOVE_PATTERN.matcher(msg);
        if (removeMatcher.find()) {
            String fieldRef = removeMatcher.group(1).trim();
            // Try to match against existing mappings
            List<Map<String, Object>> currentMappings = getCurrentMappings(request);
            Map<String, Object> matched = findMappingByRef(fieldRef, currentMappings);

            if (matched != null) {
                String targetField = stringVal(matched, "targetField");
                String sourceField = stringVal(matched, "sourceField");

                actions.add(MapAction.builder()
                        .type("REMOVE")
                        .sourceField(sourceField)
                        .targetField(targetField)
                        .confidence(0.9)
                        .reasoning("Matched '" + fieldRef + "' to existing mapping")
                        .build());

                return ChatResponse.builder()
                        .reply("Removed mapping: " + sourceField + " -> " + targetField + ".")
                        .actions(toActionMaps(actions))
                        .preview(generateActionPreview(request, actions))
                        .confidence(0.9)
                        .suggestedFollowUp("Need to remove anything else, or shall I show the remaining mappings?")
                        .build();
            }

            // Field ref didn't match any existing mapping
            return ChatResponse.builder()
                    .reply("I couldn't find a mapping for '" + fieldRef
                            + "'. Say 'show mappings' to see the current list.")
                    .actions(List.of())
                    .confidence(0.4)
                    .suggestedFollowUp("Which field mapping would you like to remove?")
                    .build();
        }

        return ChatResponse.builder()
                .reply("Which mapping should I remove? "
                        + "Try: 'Remove the city mapping' or 'Delete NM1*03'.")
                .actions(List.of())
                .confidence(0.3)
                .build();
    }

    private ChatResponse handleModifyMapping(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();
        String lower = msg.toLowerCase();

        // Try "change X to Y" or "replace X with Y"
        Pattern changePattern = Pattern.compile(
                "(?:change|replace|swap|switch)\\s+(.+?)\\s+(?:to|with|for)\\s+(.+)",
                Pattern.CASE_INSENSITIVE);
        Matcher changeMatcher = changePattern.matcher(msg);

        if (changeMatcher.find()) {
            String oldRef = changeMatcher.group(1).trim();
            String newRef = changeMatcher.group(2).trim();

            List<Map<String, Object>> currentMappings = getCurrentMappings(request);
            Map<String, Object> matched = findMappingByRef(oldRef, currentMappings);

            if (matched != null) {
                String targetField = stringVal(matched, "targetField");
                String newSourceField = resolveField(newRef, getSourceFields(request), true);

                actions.add(MapAction.builder()
                        .type("MODIFY")
                        .sourceField(newSourceField)
                        .targetField(targetField)
                        .oldSourceField(stringVal(matched, "sourceField"))
                        .transform("DIRECT")
                        .confidence(0.85)
                        .reasoning("Changed source for " + targetField + " from "
                                + stringVal(matched, "sourceField") + " to " + newSourceField)
                        .build());

                return ChatResponse.builder()
                        .reply("Updated: " + targetField + " now maps from " + newSourceField
                                + " (was " + stringVal(matched, "sourceField") + ").")
                        .actions(toActionMaps(actions))
                        .preview(generateActionPreview(request, actions))
                        .confidence(0.85)
                        .suggestedFollowUp("Does this look right? Make more changes or say 'preview'.")
                        .build();
            }
        }

        // Try "fix X" or "update X"
        List<String> fieldRefs = extractFieldReferences(msg);
        if (!fieldRefs.isEmpty()) {
            String fieldRef = fieldRefs.get(0);
            List<Map<String, Object>> currentMappings = getCurrentMappings(request);
            Map<String, Object> matched = findMappingByRef(fieldRef, currentMappings);

            if (matched != null) {
                return ChatResponse.builder()
                        .reply("I found the mapping for '" + fieldRef + "': "
                                + stringVal(matched, "sourceField") + " -> "
                                + stringVal(matched, "targetField")
                                + ". What would you like to change about it?")
                        .actions(List.of())
                        .confidence(0.6)
                        .suggestedFollowUp("Specify the new source field or transform.")
                        .build();
            }
        }

        return ChatResponse.builder()
                .reply("I want to modify a mapping, but need more detail. "
                        + "Try: 'Change NM1*02 to NM1*03' or 'Fix the sender mapping'.")
                .actions(List.of())
                .confidence(0.3)
                .build();
    }

    private ChatResponse handleFormatChange(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        Matcher dateMatcher = DATE_FMT.matcher(msg);
        if (dateMatcher.find()) {
            String targetFormat = dateMatcher.group(1);

            // Find date-related mappings
            List<Map<String, Object>> currentMappings = getCurrentMappings(request);
            List<Map<String, Object>> dateFields = currentMappings.stream()
                    .filter(m -> {
                        String tf = stringVal(m, "targetField");
                        String sf = stringVal(m, "sourceField");
                        String tr = stringVal(m, "transform");
                        return (tf != null && tf.toLowerCase().contains("date"))
                                || (sf != null && sf.toLowerCase().contains("date"))
                                || "DATE_REFORMAT".equals(tr);
                    })
                    .toList();

            if (dateFields.isEmpty()) {
                // Apply to all fields that look date-like from context
                return ChatResponse.builder()
                        .reply("No date fields found in current mappings. "
                                + "Add a date mapping first, then I can format it.")
                        .actions(List.of())
                        .confidence(0.5)
                        .suggestedFollowUp("Which field contains the date?")
                        .build();
            }

            for (Map<String, Object> dateField : dateFields) {
                actions.add(MapAction.builder()
                        .type("CHANGE_TRANSFORM")
                        .sourceField(stringVal(dateField, "sourceField"))
                        .targetField(stringVal(dateField, "targetField"))
                        .transform("DATE_REFORMAT")
                        .transformParam("yyyyMMdd->" + targetFormat)
                        .confidence(0.9)
                        .reasoning("Date format set to " + targetFormat)
                        .build());
            }

            String fieldList = dateFields.stream()
                    .map(m -> stringVal(m, "targetField"))
                    .collect(Collectors.joining(", "));

            return ChatResponse.builder()
                    .reply("Updated date format to " + targetFormat + " for: " + fieldList + ".")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.9)
                    .suggestedFollowUp("Need to change the format for a specific field only?")
                    .build();
        }

        return ChatResponse.builder()
                .reply("What format should I use? Examples: MM/dd/yyyy, yyyy-MM-dd, yyyyMMdd.")
                .actions(List.of())
                .confidence(0.4)
                .build();
    }

    private ChatResponse handleCombineFields(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        Matcher combineMatcher = COMBINE_PATTERN.matcher(msg);
        if (combineMatcher.find()) {
            String field1Ref = combineMatcher.group(1).trim();
            String field2Ref = combineMatcher.group(2).trim();
            String targetRef = combineMatcher.group(3);

            String source1 = resolveField(field1Ref, getSourceFields(request), true);
            String source2 = resolveField(field2Ref, getSourceFields(request), true);
            String target = targetRef != null
                    ? resolveField(targetRef.trim(), getTargetFields(request), false)
                    : source1 + "_" + source2;

            actions.add(MapAction.builder()
                    .type("ADD")
                    .sourceField(source1 + " + " + source2)
                    .targetField(target)
                    .transform("CONCAT")
                    .transformParam(source1 + "," + source2)
                    .confidence(0.85)
                    .reasoning("Combine " + source1 + " and " + source2 + " into " + target)
                    .build());

            return ChatResponse.builder()
                    .reply("Created concatenation: " + source1 + " + " + source2
                            + " -> " + target + " (separated by space).")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.85)
                    .suggestedFollowUp("Need a different separator? Say 'use comma separator' or 'no separator'.")
                    .build();
        }

        return ChatResponse.builder()
                .reply("Which fields should I combine? "
                        + "Try: 'Combine firstName and lastName into fullName'.")
                .actions(List.of())
                .confidence(0.3)
                .build();
    }

    private ChatResponse handleComputeField(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        Matcher computeMatcher = COMPUTE_PATTERN.matcher(msg);
        if (computeMatcher.find()) {
            String targetRef = computeMatcher.group(1).trim();
            String formulaRef = computeMatcher.group(2);

            String target = resolveField(targetRef, getTargetFields(request), false);

            // Parse simple arithmetic: "qty * price" or "sum of lineAmounts"
            String formula;
            String transform;
            if (msg.toLowerCase().contains("sum")) {
                transform = "SUM";
                formula = formulaRef != null ? formulaRef.trim() : targetRef;
            } else if (msg.toLowerCase().contains("*") || msg.toLowerCase().contains("multiply")) {
                transform = "MULTIPLY";
                formula = formulaRef != null ? formulaRef.trim() : extractArithmeticFields(msg);
            } else {
                transform = "COMPUTE";
                formula = formulaRef != null ? formulaRef.trim() : targetRef;
            }

            actions.add(MapAction.builder()
                    .type("ADD")
                    .sourceField(formula)
                    .targetField(target)
                    .transform(transform)
                    .transformParam(formula)
                    .confidence(0.75)
                    .reasoning("Computed field: " + transform + "(" + formula + ") -> " + target)
                    .build());

            return ChatResponse.builder()
                    .reply("Added computed field: " + target + " = " + transform + "(" + formula + ").")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.75)
                    .suggestedFollowUp("Is the formula correct? You can refine it.")
                    .build();
        }

        return ChatResponse.builder()
                .reply("What should I calculate? "
                        + "Try: 'Calculate total from quantity * price' or 'Sum all line amounts'.")
                .actions(List.of())
                .confidence(0.3)
                .build();
    }

    private ChatResponse handleConditionalMapping(ChatRequest request) {
        String msg = request.getMessage();
        List<MapAction> actions = new ArrayList<>();

        Matcher condMatcher = CONDITION_PATTERN.matcher(msg);
        if (condMatcher.find()) {
            String condition = condMatcher.group(1).trim();
            String thenAction = condMatcher.group(2).trim();

            // Parse condition: "qualifier is BY" -> field=qualifier, value=BY
            String condField = null;
            String condValue = null;
            Pattern condValuePattern = Pattern.compile("(\\w+)\\s+(?:is|=|equals|==)\\s+['\"]?(\\w+)['\"]?",
                    Pattern.CASE_INSENSITIVE);
            Matcher condValueMatcher = condValuePattern.matcher(condition);
            if (condValueMatcher.find()) {
                condField = resolveField(condValueMatcher.group(1), getSourceFields(request), true);
                condValue = condValueMatcher.group(2);
            }

            // Parse then-action: "map to buyer" or "use NM1*02 for buyerName"
            List<String> thenFields = extractFieldReferences(thenAction);
            String targetField = !thenFields.isEmpty()
                    ? resolveField(thenFields.get(0), getTargetFields(request), false)
                    : thenAction;

            String conditionExpr = condField != null
                    ? condField + "==" + condValue
                    : condition;

            actions.add(MapAction.builder()
                    .type("ADD")
                    .sourceField(condField != null ? condField : condition)
                    .targetField(targetField)
                    .transform("CONDITIONAL")
                    .transformParam(conditionExpr)
                    .confidence(0.7)
                    .reasoning("Conditional: when " + conditionExpr + " then map to " + targetField)
                    .build());

            return ChatResponse.builder()
                    .reply("Added conditional mapping: when " + conditionExpr
                            + ", map to " + targetField + ".")
                    .actions(toActionMaps(actions))
                    .preview(generateActionPreview(request, actions))
                    .confidence(0.7)
                    .suggestedFollowUp("What should happen when the condition is NOT met?")
                    .build();
        }

        return ChatResponse.builder()
                .reply("I see a condition, but need more clarity. "
                        + "Try: 'If qualifier is BY then map to buyer.name'.")
                .actions(List.of())
                .confidence(0.3)
                .build();
    }

    private ChatResponse handleQuery(ChatRequest request) {
        String msg = request.getMessage().toLowerCase();
        List<Map<String, Object>> currentMappings = getCurrentMappings(request);

        // "Show all mappings"
        if (containsAny(msg, "all", "mappings", "list", "current", "fields")) {
            StringBuilder sb = new StringBuilder();
            sb.append("Current mappings (").append(currentMappings.size()).append("):\n");
            for (int i = 0; i < currentMappings.size(); i++) {
                Map<String, Object> m = currentMappings.get(i);
                sb.append(String.format("  %d. %s -> %s [%s, %s%%]\n",
                        i + 1,
                        stringVal(m, "sourceField"),
                        stringVal(m, "targetField"),
                        stringVal(m, "transform"),
                        stringVal(m, "confidence")));
            }

            return ChatResponse.builder()
                    .reply(sb.toString())
                    .actions(List.of())
                    .confidence(1.0)
                    .suggestedFollowUp("Want to modify any of these, or see unmapped fields?")
                    .build();
        }

        // "What's unmapped?" / "What's missing?"
        if (containsAny(msg, "unmapped", "missing", "left", "remaining")) {
            List<String> sourceFields = getSourceFields(request);
            List<String> targetFields = getTargetFields(request);

            Set<String> mappedSources = currentMappings.stream()
                    .map(m -> stringVal(m, "sourceField"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<String> mappedTargets = currentMappings.stream()
                    .map(m -> stringVal(m, "targetField"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<String> unmappedSrc = sourceFields.stream()
                    .filter(f -> !mappedSources.contains(f)).toList();
            List<String> unmappedTgt = targetFields.stream()
                    .filter(f -> !mappedTargets.contains(f)).toList();

            StringBuilder sb = new StringBuilder();
            if (!unmappedSrc.isEmpty()) {
                sb.append("Unmapped source fields (").append(unmappedSrc.size()).append("): ");
                sb.append(String.join(", ", unmappedSrc.subList(0, Math.min(10, unmappedSrc.size()))));
                if (unmappedSrc.size() > 10) sb.append("... and ").append(unmappedSrc.size() - 10).append(" more");
                sb.append("\n");
            }
            if (!unmappedTgt.isEmpty()) {
                sb.append("Unmapped target fields (").append(unmappedTgt.size()).append("): ");
                sb.append(String.join(", ", unmappedTgt.subList(0, Math.min(10, unmappedTgt.size()))));
                if (unmappedTgt.size() > 10) sb.append("... and ").append(unmappedTgt.size() - 10).append(" more");
            }
            if (unmappedSrc.isEmpty() && unmappedTgt.isEmpty()) {
                sb.append("All fields are mapped!");
            }

            return ChatResponse.builder()
                    .reply(sb.toString())
                    .actions(List.of())
                    .confidence(1.0)
                    .suggestedFollowUp(unmappedTgt.isEmpty()
                            ? "Everything looks mapped. Ready to preview?"
                            : "Want me to suggest mappings for the unmapped fields?")
                    .build();
        }

        // "How many fields?" / count query
        if (containsAny(msg, "how many", "count", "total")) {
            return ChatResponse.builder()
                    .reply("There are " + currentMappings.size() + " field mappings in this map.")
                    .actions(List.of())
                    .confidence(1.0)
                    .suggestedFollowUp("Want to see the full list?")
                    .build();
        }

        // Generic show
        return ChatResponse.builder()
                .reply("This map has " + currentMappings.size() + " field mappings. "
                        + "Say 'show all mappings' to see them, or 'what's unmapped?' for gaps.")
                .actions(List.of())
                .confidence(0.6)
                .build();
    }

    private ChatResponse handleGenericRequest(ChatRequest request) {
        String msg = request.getMessage();

        // Try to extract any field references and provide helpful context
        List<String> fieldRefs = extractFieldReferences(msg);

        if (!fieldRefs.isEmpty()) {
            String ref = fieldRefs.get(0);
            List<Map<String, Object>> currentMappings = getCurrentMappings(request);
            Map<String, Object> matched = findMappingByRef(ref, currentMappings);

            if (matched != null) {
                return ChatResponse.builder()
                        .reply("I found a mapping involving '" + ref + "': "
                                + stringVal(matched, "sourceField") + " -> "
                                + stringVal(matched, "targetField")
                                + " [" + stringVal(matched, "transform") + "]. "
                                + "What would you like to do with it?")
                        .actions(List.of())
                        .confidence(0.5)
                        .suggestedFollowUp("You can say 'change it', 'remove it', or 'add a format'.")
                        .build();
            }

            return ChatResponse.builder()
                    .reply("I see '" + ref + "' in your message. "
                            + "Would you like to add it as a mapping, or did you mean something else?")
                    .actions(List.of())
                    .confidence(0.4)
                    .suggestedFollowUp("Try: 'Map " + ref + " to <target_field>'.")
                    .build();
        }

        return ChatResponse.builder()
                .reply("I'm not sure what you'd like to do. Here are some things I can help with:\n"
                        + "  - 'Map BEG*03 to poNumber' -- add a mapping\n"
                        + "  - 'Remove the city field' -- delete a mapping\n"
                        + "  - 'Change NM1*02 to NM1*03' -- modify a mapping\n"
                        + "  - 'Format date as MM/dd/yyyy' -- change date format\n"
                        + "  - 'Combine first and last name' -- concatenate fields\n"
                        + "  - 'Show unmapped fields' -- see what's missing\n")
                .actions(List.of())
                .confidence(0.2)
                .suggestedFollowUp("What would you like to do?")
                .build();
    }

    // ===================================================================
    // Field Resolution
    // ===================================================================

    /**
     * Resolve a natural language field reference to an actual field name.
     * Uses exact match first, then semantic similarity via FieldEmbeddingEngine.
     */
    private String resolveField(String ref, List<String> knownFields, boolean isSource) {
        if (ref == null || ref.isBlank()) return ref;

        String cleaned = ref.trim().replaceAll("^['\"]|['\"]$", "");

        // Exact match
        for (String field : knownFields) {
            if (field.equalsIgnoreCase(cleaned)) return field;
        }

        // Partial match (field contains the reference or vice versa)
        for (String field : knownFields) {
            if (field.toLowerCase().contains(cleaned.toLowerCase())
                    || cleaned.toLowerCase().contains(field.toLowerCase())) {
                return field;
            }
        }

        // Semantic similarity
        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(cleaned, knownFields, 0.4);
        if (best.isPresent()) {
            return best.get().targetField();
        }

        // Return as-is if no match found (user may know the exact field name)
        return cleaned;
    }

    private String suggestTargetField(String sourceField, List<String> targetFields,
                                       ChatRequest request) {
        if (targetFields.isEmpty()) return null;
        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(sourceField, targetFields, 0.3);
        return best.map(FieldEmbeddingEngine.FieldMatch::targetField).orElse(null);
    }

    /**
     * Extract field-like references from a natural language message.
     * Matches EDI segment paths (NM1*03) and dot-path fields (buyer.name).
     */
    private List<String> extractFieldReferences(String message) {
        List<String> refs = new ArrayList<>();
        Matcher m = FIELD_REF.matcher(message);
        while (m.find()) {
            String ref = m.group(1);
            // Filter out common English words that match the pattern
            if (!isCommonWord(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private String extractArithmeticFields(String msg) {
        // Extract "X * Y" or "X + Y" patterns
        Pattern arith = Pattern.compile("(\\w+)\\s*([*+\\-/])\\s*(\\w+)");
        Matcher m = arith.matcher(msg);
        if (m.find()) {
            return m.group(1) + " " + m.group(2) + " " + m.group(3);
        }
        return msg;
    }

    // ===================================================================
    // Mapping Lookup
    // ===================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCurrentMappings(ChatRequest request) {
        if (request.getCurrentMappings() != null) {
            Object fm = request.getCurrentMappings().get("fieldMappings");
            if (fm instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(m -> (Map<String, Object>) m)
                        .toList();
            }
            // If currentMappings itself is the list
            return List.of(request.getCurrentMappings());
        }

        // Try loading from store
        if (request.getMapId() != null) {
            try {
                Optional<ConversionMap> map = trainedMapStore.getActiveMap(request.getMapId());
                if (map.isPresent()) {
                    return objectMapper.readValue(map.get().getFieldMappingsJson(),
                            new TypeReference<List<Map<String, Object>>>() {});
                }
            } catch (Exception e) {
                log.debug("Could not load mappings for map {}: {}", request.getMapId(), e.getMessage());
            }
        }

        return List.of();
    }

    private List<String> getSourceFields(ChatRequest request) {
        return getCurrentMappings(request).stream()
                .map(m -> stringVal(m, "sourceField"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<String> getTargetFields(ChatRequest request) {
        return getCurrentMappings(request).stream()
                .map(m -> stringVal(m, "targetField"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Find an existing mapping that matches a field reference (source or target).
     */
    private Map<String, Object> findMappingByRef(String ref, List<Map<String, Object>> mappings) {
        String lower = ref.toLowerCase().trim();

        // Exact match on source or target
        for (Map<String, Object> m : mappings) {
            String sf = stringVal(m, "sourceField");
            String tf = stringVal(m, "targetField");
            if ((sf != null && sf.equalsIgnoreCase(lower))
                    || (tf != null && tf.equalsIgnoreCase(lower))) {
                return m;
            }
        }

        // Partial / contains match
        for (Map<String, Object> m : mappings) {
            String sf = stringVal(m, "sourceField");
            String tf = stringVal(m, "targetField");
            if ((sf != null && sf.toLowerCase().contains(lower))
                    || (tf != null && tf.toLowerCase().contains(lower))
                    || (sf != null && lower.contains(sf.toLowerCase()))
                    || (tf != null && lower.contains(tf.toLowerCase()))) {
                return m;
            }
        }

        // Semantic match
        List<String> allFields = new ArrayList<>();
        Map<String, Map<String, Object>> fieldToMapping = new HashMap<>();
        for (Map<String, Object> m : mappings) {
            String tf = stringVal(m, "targetField");
            String sf = stringVal(m, "sourceField");
            if (tf != null) {
                allFields.add(tf);
                fieldToMapping.put(tf, m);
            }
            if (sf != null) {
                allFields.add(sf);
                fieldToMapping.put(sf, m);
            }
        }

        Optional<FieldEmbeddingEngine.FieldMatch> best =
                fieldEmbedding.findBestMatch(lower, allFields, 0.5);
        if (best.isPresent()) {
            return fieldToMapping.get(best.get().targetField());
        }

        return null;
    }

    // ===================================================================
    // Preview Generation
    // ===================================================================

    private Map<String, Object> generateActionPreview(ChatRequest request, List<MapAction> actions) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("actionsApplied", actions.size());
        List<Map<String, String>> changes = new ArrayList<>();
        for (MapAction action : actions) {
            Map<String, String> change = new LinkedHashMap<>();
            change.put("type", action.type);
            change.put("source", action.sourceField);
            change.put("target", action.targetField);
            if (action.transform != null) change.put("transform", action.transform);
            changes.add(change);
        }
        preview.put("changes", changes);
        return preview;
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static final Set<String> COMMON_WORDS = Set.of(
            "the", "map", "add", "remove", "delete", "change", "update", "show", "what",
            "from", "to", "and", "or", "with", "for", "is", "are", "this", "that",
            "should", "field", "mapping", "combine", "fix", "not", "into", "then",
            "if", "when", "only", "all", "it", "make", "set", "use");

    private boolean isCommonWord(String word) {
        return COMMON_WORDS.contains(word.toLowerCase());
    }

    private List<Map<String, Object>> toActionMaps(List<MapAction> actions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MapAction a : actions) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", a.type);
            map.put("sourceField", a.sourceField);
            map.put("targetField", a.targetField);
            if (a.oldSourceField != null) map.put("oldSourceField", a.oldSourceField);
            if (a.transform != null) map.put("transform", a.transform);
            if (a.transformParam != null) map.put("transformParam", a.transformParam);
            map.put("confidence", a.confidence);
            map.put("reasoning", a.reasoning);
            result.add(map);
        }
        return result;
    }

    private String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ===================================================================
    // Inner Classes
    // ===================================================================

    /** An action generated by the conversation engine. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class MapAction {
        String type;         // ADD, REMOVE, MODIFY, CHANGE_TRANSFORM
        String sourceField;
        String targetField;
        String oldSourceField;
        String transform;
        String transformParam;
        double confidence;
        String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String mapId;
        private String message;
        private Map<String, Object> currentMappings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        private String reply;
        private List<Map<String, Object>> actions;
        private Map<String, Object> preview;
        private double confidence;
        private String suggestedFollowUp;
    }
}
