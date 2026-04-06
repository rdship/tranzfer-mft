package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets natural language mapping correction instructions.
 *
 * Primary path: Claude API for intelligent interpretation.
 * Fallback path: keyword-based regex parsing when no API key is configured.
 *
 * Supported actions: MODIFY (change source field), ADD (new mapping),
 * REMOVE (delete mapping), CHANGE_TRANSFORM (change transform/format).
 */
@Service
@Slf4j
public class MappingCorrectionInterpreter {

    @Value("${ai.claude.api-key:}")
    private String apiKey;

    @Value("${ai.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${ai.claude.base-url:https://api.anthropic.com}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Interpret a natural language correction instruction against current field mappings.
     */
    public CorrectionInterpretation interpretCorrection(String instruction,
                                                         List<FieldMappingDto> currentMappings,
                                                         String sourceFormat,
                                                         String sourceType,
                                                         String sampleInput) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallbackInterpret(instruction, currentMappings);
        }

        try {
            return claudeInterpret(instruction, currentMappings, sourceFormat, sourceType, sampleInput);
        } catch (Exception e) {
            log.warn("Claude API call failed for correction interpretation: {}", e.getMessage());
            return fallbackInterpret(instruction, currentMappings);
        }
    }

    // ===================================================================
    // Claude API interpretation
    // ===================================================================

    private CorrectionInterpretation claudeInterpret(String instruction,
                                                      List<FieldMappingDto> currentMappings,
                                                      String sourceFormat,
                                                      String sourceType,
                                                      String sampleInput) throws Exception {
        String mappingsJson;
        try {
            mappingsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentMappings);
        } catch (Exception e) {
            mappingsJson = currentMappings.toString();
        }

        String systemPrompt = """
            You are the TranzFer MFT field mapping correction assistant.

            You are given:
            1. Current field mapping rules (source field -> target field with optional transform)
            2. The source EDI format (%s %s)
            3. A sample EDI input document
            4. The user's natural language correction instruction

            Your job: interpret the user's instruction and output ONLY valid JSON describing the changes.

            Output format (no markdown, no explanation, ONLY this JSON):
            {
              "understood": true,
              "changes": [
                {
                  "action": "MODIFY|ADD|REMOVE|CHANGE_TRANSFORM",
                  "targetField": "the target field being affected",
                  "oldSourceField": "current source field (for MODIFY, null otherwise)",
                  "newSourceField": "new source field (for MODIFY/ADD, null for REMOVE)",
                  "oldTransform": "current transform (for CHANGE_TRANSFORM, null otherwise)",
                  "newTransform": "DIRECT|TRIM|UPPERCASE|LOWERCASE|ZERO_PAD|DATE_REFORMAT",
                  "newTransformParam": "parameter for transform (e.g., 'yyyyMMdd->MM/dd/yyyy')",
                  "reasoning": "brief explanation of why this change was made"
                }
              ],
              "clarificationNeeded": false,
              "clarificationQuestion": null,
              "summary": "Human-readable summary of what will change"
            }

            Rules:
            - Source fields use EDI segment notation: SEGMENT_ID*ELEMENT_NUMBER (e.g., NM1*03, BEG*03, ISA*06)
            - Target fields use dot notation for nested JSON (e.g., header.documentNumber, parties.sender.name)
            - Available transforms: DIRECT (no change), TRIM, UPPERCASE, LOWERCASE, ZERO_PAD, DATE_REFORMAT
            - DATE_REFORMAT param format: "inputFormat->outputFormat" (e.g., "yyyyMMdd->MM/dd/yyyy")
            - If the instruction is unclear, set clarificationNeeded=true with a question
            - For "undo" instructions, reverse the last change from the correction history

            Current field mappings:
            %s
            """.formatted(sourceFormat, sourceType != null ? sourceType : "", mappingsJson);

        String userMessage = "Instruction: " + instruction;
        if (sampleInput != null && sampleInput.length() < 5000) {
            userMessage += "\n\nSample EDI input:\n" + sampleInput;
        }

        String response = callClaude(systemPrompt, userMessage);

        // Extract JSON from response
        int jsonStart = response.indexOf('{');
        int jsonEnd = response.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return CorrectionInterpretation.builder()
                    .understood(false)
                    .summary("Could not parse AI response. Try rephrasing your correction.")
                    .build();
        }

        String json = response.substring(jsonStart, jsonEnd + 1);
        return parseInterpretation(json);
    }

    private CorrectionInterpretation parseInterpretation(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            boolean understood = root.path("understood").asBoolean(false);
            boolean clarificationNeeded = root.path("clarificationNeeded").asBoolean(false);
            String clarificationQuestion = root.path("clarificationQuestion").asText(null);
            String summary = root.path("summary").asText("");

            List<MappingChange> changes = new ArrayList<>();
            JsonNode changesNode = root.path("changes");
            if (changesNode.isArray()) {
                for (JsonNode ch : changesNode) {
                    changes.add(MappingChange.builder()
                            .action(ch.path("action").asText("MODIFY"))
                            .targetField(ch.path("targetField").asText(null))
                            .oldSourceField(ch.path("oldSourceField").asText(null))
                            .newSourceField(ch.path("newSourceField").asText(null))
                            .oldTransform(ch.path("oldTransform").asText(null))
                            .newTransform(ch.path("newTransform").asText(null))
                            .newTransformParam(ch.path("newTransformParam").asText(null))
                            .reasoning(ch.path("reasoning").asText(null))
                            .build());
                }
            }

            return CorrectionInterpretation.builder()
                    .understood(understood)
                    .changes(changes)
                    .clarificationNeeded(clarificationNeeded)
                    .clarificationQuestion(clarificationQuestion)
                    .summary(summary)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Claude correction response: {}", e.getMessage());
            return CorrectionInterpretation.builder()
                    .understood(false)
                    .summary("Failed to parse correction. Try rephrasing.")
                    .build();
        }
    }

    private String callClaude(String systemPrompt, String userMessage) throws Exception {
        URL url = new URL(baseUrl + "/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        conn.getOutputStream().write(mapper.writeValueAsBytes(body));

        if (conn.getResponseCode() != 200) {
            String error = new String(conn.getErrorStream().readAllBytes());
            throw new RuntimeException("Claude API error " + conn.getResponseCode() + ": " + error);
        }

        JsonNode resp = mapper.readTree(conn.getInputStream());
        return resp.get("content").get(0).get("text").asText();
    }

    // ===================================================================
    // Fallback: keyword-based interpretation (no API key)
    // ===================================================================

    // Pattern: "X not Y" or "from X not Y" — swap source field
    private static final Pattern SWAP_PATTERN = Pattern.compile(
            "(?:from\\s+)?(\\w+\\*\\d+)\\s+(?:not|instead of|rather than)\\s+(\\w+\\*\\d+)", Pattern.CASE_INSENSITIVE);

    // Pattern: "Map X to Y" or "X should map to Y"
    private static final Pattern MAP_TO_PATTERN = Pattern.compile(
            "(?:map|set|assign)\\s+(\\w+\\*\\d+)\\s+(?:to|as|->)\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);

    // Pattern: "should come from X" — change source for a target
    private static final Pattern COME_FROM_PATTERN = Pattern.compile(
            "([\\w.]+)\\s+should\\s+(?:come|be taken|be read)\\s+from\\s+(\\w+\\*\\d+)", Pattern.CASE_INSENSITIVE);

    // Pattern: date format detection
    private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile(
            "(?:format|date).*?(MM/dd/yyyy|yyyy-MM-dd|dd/MM/yyyy|MM-dd-yyyy|yyyyMMdd)", Pattern.CASE_INSENSITIVE);

    // Pattern: "remove X" or "delete X mapping"
    private static final Pattern REMOVE_PATTERN = Pattern.compile(
            "(?:remove|delete|drop)\\s+(?:the\\s+)?(?:mapping\\s+(?:for\\s+)?)?([\\w.*]+)", Pattern.CASE_INSENSITIVE);

    // Pattern: "uppercase X" or "make X uppercase"
    private static final Pattern CASE_PATTERN = Pattern.compile(
            "(?:make\\s+)?([\\w.]+)\\s+(?:be\\s+)?(uppercase|lowercase|upper case|lower case)", Pattern.CASE_INSENSITIVE);

    // Pattern: "trim X" or "trim whitespace from X"
    private static final Pattern TRIM_PATTERN = Pattern.compile(
            "trim\\s+(?:whitespace\\s+from\\s+)?([\\w.]+)", Pattern.CASE_INSENSITIVE);

    private CorrectionInterpretation fallbackInterpret(String instruction, List<FieldMappingDto> currentMappings) {
        List<MappingChange> changes = new ArrayList<>();
        String lower = instruction.toLowerCase();

        // Try swap pattern: "NM1*03 not NM1*02"
        Matcher swapMatcher = SWAP_PATTERN.matcher(instruction);
        if (swapMatcher.find()) {
            String newSource = swapMatcher.group(1);
            String oldSource = swapMatcher.group(2);
            // Find the mapping that uses oldSource
            for (FieldMappingDto m : currentMappings) {
                if (m.getSourceField().equalsIgnoreCase(oldSource)) {
                    changes.add(MappingChange.builder()
                            .action("MODIFY").targetField(m.getTargetField())
                            .oldSourceField(oldSource).newSourceField(newSource)
                            .reasoning("Keyword match: swap source field from " + oldSource + " to " + newSource)
                            .build());
                    break;
                }
            }
        }

        // Try "should come from" pattern
        Matcher comeFromMatcher = COME_FROM_PATTERN.matcher(instruction);
        if (comeFromMatcher.find() && changes.isEmpty()) {
            String targetField = comeFromMatcher.group(1);
            String newSource = comeFromMatcher.group(2);
            changes.add(MappingChange.builder()
                    .action("MODIFY").targetField(targetField).newSourceField(newSource)
                    .reasoning("Keyword match: target " + targetField + " should come from " + newSource)
                    .build());
        }

        // Try "map X to Y" pattern
        Matcher mapToMatcher = MAP_TO_PATTERN.matcher(instruction);
        if (mapToMatcher.find() && changes.isEmpty()) {
            String sourceField = mapToMatcher.group(1);
            String targetField = mapToMatcher.group(2);
            // Check if target already exists
            boolean exists = currentMappings.stream()
                    .anyMatch(m -> m.getTargetField().equalsIgnoreCase(targetField));
            changes.add(MappingChange.builder()
                    .action(exists ? "MODIFY" : "ADD")
                    .targetField(targetField).newSourceField(sourceField)
                    .reasoning("Keyword match: map " + sourceField + " to " + targetField)
                    .build());
        }

        // Try date format pattern
        Matcher dateMatcher = DATE_FORMAT_PATTERN.matcher(instruction);
        if (dateMatcher.find()) {
            String targetDateFormat = dateMatcher.group(1);
            // Find date-related mappings to update
            for (FieldMappingDto m : currentMappings) {
                if (m.getTargetField().toLowerCase().contains("date")
                        || (m.getTransform() != null && m.getTransform().equals("DATE_REFORMAT"))) {
                    changes.add(MappingChange.builder()
                            .action("CHANGE_TRANSFORM").targetField(m.getTargetField())
                            .newTransform("DATE_REFORMAT")
                            .newTransformParam("yyyyMMdd->" + targetDateFormat)
                            .reasoning("Keyword match: change date format to " + targetDateFormat)
                            .build());
                    break;
                }
            }
        }

        // Try remove pattern
        Matcher removeMatcher = REMOVE_PATTERN.matcher(instruction);
        if (removeMatcher.find() && changes.isEmpty()) {
            String field = removeMatcher.group(1);
            changes.add(MappingChange.builder()
                    .action("REMOVE").targetField(field)
                    .reasoning("Keyword match: remove mapping for " + field)
                    .build());
        }

        // Try case transform pattern
        Matcher caseMatcher = CASE_PATTERN.matcher(instruction);
        if (caseMatcher.find() && changes.isEmpty()) {
            String field = caseMatcher.group(1);
            String caseType = caseMatcher.group(2).toUpperCase().replace(" ", "");
            changes.add(MappingChange.builder()
                    .action("CHANGE_TRANSFORM").targetField(field)
                    .newTransform(caseType)
                    .reasoning("Keyword match: apply " + caseType + " to " + field)
                    .build());
        }

        // Try trim pattern
        Matcher trimMatcher = TRIM_PATTERN.matcher(instruction);
        if (trimMatcher.find() && changes.isEmpty()) {
            String field = trimMatcher.group(1);
            changes.add(MappingChange.builder()
                    .action("CHANGE_TRANSFORM").targetField(field).newTransform("TRIM")
                    .reasoning("Keyword match: trim " + field)
                    .build());
        }

        if (changes.isEmpty()) {
            return CorrectionInterpretation.builder()
                    .understood(false)
                    .clarificationNeeded(true)
                    .clarificationQuestion("I couldn't understand the correction. Try one of these formats:\n"
                            + "- \"NM1*03 not NM1*02\" (swap source field)\n"
                            + "- \"Map NM1*03 to companyName\" (add/modify mapping)\n"
                            + "- \"companyName should come from NM1*03\" (change source)\n"
                            + "- \"Format date as MM/dd/yyyy\" (change date format)\n"
                            + "- \"Remove companyName\" (delete mapping)\n"
                            + "- \"Make companyName uppercase\" (add transform)\n"
                            + "Set CLAUDE_API_KEY for full natural language support.")
                    .summary("Could not parse instruction")
                    .build();
        }

        String summary = changes.size() == 1
                ? changes.get(0).getReasoning()
                : changes.size() + " changes detected from keyword matching";

        return CorrectionInterpretation.builder()
                .understood(true)
                .changes(changes)
                .summary(summary + " (keyword fallback — set CLAUDE_API_KEY for full NLP)")
                .build();
    }

    // ===================================================================
    // DTOs
    // ===================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CorrectionInterpretation {
        @Builder.Default
        private boolean understood = false;
        @Builder.Default
        private List<MappingChange> changes = new ArrayList<>();
        @Builder.Default
        private boolean clarificationNeeded = false;
        private String clarificationQuestion;
        private String summary;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MappingChange {
        private String action;       // MODIFY, ADD, REMOVE, CHANGE_TRANSFORM
        private String targetField;
        private String oldSourceField;
        private String newSourceField;
        private String oldTransform;
        private String newTransform;
        private String newTransformParam;
        private String reasoning;
    }

    /** Simple DTO for passing current mappings to the interpreter */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FieldMappingDto {
        private String sourceField;
        private String targetField;
        private String transform;
        private String transformParam;
        private int confidence;
    }
}
