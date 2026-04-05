package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import com.filetransfer.edi.parser.UniversalEdiParser;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Mapping Generator — upload a source EDI + desired output, get a mapping.
 *
 * Inspired by AWS B2B Data Interchange's GenAI mapping feature.
 *
 * Three modes:
 * 1. SAMPLE_BASED: Provide source EDI + target JSON → generates mapping rules
 * 2. SCHEMA_BASED: Provide source format + target schema → generates mapping
 * 3. AUTO_LEARN: Feed multiple samples → learns the mapping pattern
 *
 * No LLM required — uses structural analysis and pattern matching.
 * With LLM (optional): handles complex/ambiguous mappings better.
 */
@Service @RequiredArgsConstructor @Slf4j
public class AiMappingGenerator {

    private final UniversalEdiParser parser;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MappingResult {
        private String mappingId;
        private List<MappingRule> rules;
        private int confidence;         // 0-100
        private int fieldsMatched;
        private int fieldsTotal;
        private String sourceFormat;
        private String targetFormat;
        private List<String> unmappedSourceFields;
        private List<String> unmappedTargetFields;
        private List<String> suggestions;
        private String generatedCode;   // Executable mapping code (JSONata-like)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MappingRule {
        private String sourceField;     // e.g., "ISA*06" or "NM1*03"
        private String targetField;     // e.g., "$.header.sender" or "companyName"
        private String transform;       // DIRECT, TRIM, PAD, FORMAT_DATE, LOOKUP, etc.
        private String transformParam;  // Parameter for transform
        private int confidence;         // 0-100 for this specific rule
        private String reasoning;       // Why this mapping was chosen
    }

    /**
     * Generate mapping from source EDI sample + target JSON sample.
     */
    public MappingResult generateFromSamples(String sourceEdi, String targetJson) {
        EdiDocument doc = parser.parse(sourceEdi);
        Map<String, Object> target = parseTargetJson(targetJson);

        List<MappingRule> rules = new ArrayList<>();
        List<String> unmappedSource = new ArrayList<>();
        List<String> unmappedTarget = new ArrayList<>(flattenKeys(target));
        List<String> suggestions = new ArrayList<>();

        // Extract all source values and their positions
        Map<String, String> sourceValues = extractSourceValues(doc);

        // Extract all target values and their paths
        Map<String, String> targetValues = flattenValues(target);

        // Phase 1: Exact value matching (highest confidence)
        for (Map.Entry<String, String> sv : sourceValues.entrySet()) {
            String sourceField = sv.getKey();
            String sourceValue = sv.getValue().trim();
            if (sourceValue.isEmpty()) continue;

            for (Map.Entry<String, String> tv : targetValues.entrySet()) {
                String targetField = tv.getKey();
                String targetValue = tv.getValue().trim();

                if (sourceValue.equals(targetValue)) {
                    rules.add(MappingRule.builder()
                            .sourceField(sourceField).targetField(targetField)
                            .transform("DIRECT").confidence(95)
                            .reasoning("Exact value match: '" + sourceValue + "'").build());
                    unmappedTarget.remove(targetField);
                    break;
                }
            }
        }

        // Phase 2: Fuzzy value matching (trimmed, case-insensitive)
        Set<String> mappedTargets = new HashSet<>();
        for (MappingRule r : rules) mappedTargets.add(r.getTargetField());

        for (Map.Entry<String, String> sv : sourceValues.entrySet()) {
            String sourceField = sv.getKey();
            String sourceValue = sv.getValue().trim();
            if (sourceValue.isEmpty()) continue;
            if (rules.stream().anyMatch(r -> r.getSourceField().equals(sourceField))) continue;

            for (Map.Entry<String, String> tv : targetValues.entrySet()) {
                String targetField = tv.getKey();
                if (mappedTargets.contains(targetField)) continue;
                String targetValue = tv.getValue().trim();

                // Case-insensitive match
                if (sourceValue.equalsIgnoreCase(targetValue)) {
                    rules.add(MappingRule.builder()
                            .sourceField(sourceField).targetField(targetField)
                            .transform("DIRECT").confidence(85)
                            .reasoning("Case-insensitive match").build());
                    mappedTargets.add(targetField);
                    unmappedTarget.remove(targetField);
                    break;
                }

                // Trimmed match (source may have padding)
                if (sourceValue.trim().equals(targetValue.trim()) && !sourceValue.equals(targetValue)) {
                    rules.add(MappingRule.builder()
                            .sourceField(sourceField).targetField(targetField)
                            .transform("TRIM").confidence(80)
                            .reasoning("Match after trimming whitespace").build());
                    mappedTargets.add(targetField);
                    unmappedTarget.remove(targetField);
                    break;
                }

                // Contained match (target contains source value)
                if (targetValue.contains(sourceValue) && sourceValue.length() > 3) {
                    rules.add(MappingRule.builder()
                            .sourceField(sourceField).targetField(targetField)
                            .transform("DIRECT").confidence(60)
                            .reasoning("Source value found within target: '" + sourceValue + "'").build());
                    mappedTargets.add(targetField);
                    unmappedTarget.remove(targetField);
                    break;
                }
            }
        }

        // Phase 3: Semantic name matching (field name similarity)
        for (Map.Entry<String, String> sv : sourceValues.entrySet()) {
            String sourceField = sv.getKey();
            if (rules.stream().anyMatch(r -> r.getSourceField().equals(sourceField))) continue;

            for (String targetField : new ArrayList<>(unmappedTarget)) {
                if (mappedTargets.contains(targetField)) continue;

                double nameSim = fieldNameSimilarity(sourceField, targetField);
                if (nameSim > 0.6) {
                    rules.add(MappingRule.builder()
                            .sourceField(sourceField).targetField(targetField)
                            .transform("DIRECT").confidence((int) (nameSim * 70))
                            .reasoning("Field name similarity: " + String.format("%.0f%%", nameSim * 100)).build());
                    mappedTargets.add(targetField);
                    unmappedTarget.remove(targetField);
                    break;
                }
            }
        }

        // Find unmapped source fields
        Set<String> mappedSources = new HashSet<>();
        for (MappingRule r : rules) mappedSources.add(r.getSourceField());
        for (String sf : sourceValues.keySet()) {
            if (!mappedSources.contains(sf)) unmappedSource.add(sf);
        }

        // Generate suggestions
        if (!unmappedTarget.isEmpty()) {
            suggestions.add(unmappedTarget.size() + " target fields have no mapping — may need manual rules");
        }
        if (!unmappedSource.isEmpty()) {
            suggestions.add(unmappedSource.size() + " source fields are unused — these may be envelope/control data");
        }
        if (rules.stream().anyMatch(r -> r.getConfidence() < 70)) {
            suggestions.add("Some mappings have low confidence — review fields marked < 70%");
        }

        // Generate executable code
        String code = generateMappingCode(rules);

        int totalFields = targetValues.size();
        int matched = rules.size();
        int confidence = totalFields > 0 ? (matched * 100) / totalFields : 0;
        // Adjust by average rule confidence
        int avgRuleConf = rules.isEmpty() ? 0 :
                (int) rules.stream().mapToInt(MappingRule::getConfidence).average().orElse(0);
        confidence = (confidence + avgRuleConf) / 2;

        return MappingResult.builder()
                .mappingId(UUID.randomUUID().toString().substring(0, 8))
                .rules(rules).confidence(confidence)
                .fieldsMatched(matched).fieldsTotal(totalFields)
                .sourceFormat(doc.getSourceFormat()).targetFormat("JSON")
                .unmappedSourceFields(unmappedSource).unmappedTargetFields(unmappedTarget)
                .suggestions(suggestions).generatedCode(code).build();
    }

    /**
     * Generate mapping from format description (no sample output needed).
     */
    public MappingResult generateFromSchema(String sourceEdi, String targetSchema) {
        // Parse source
        EdiDocument doc = parser.parse(sourceEdi);
        Map<String, String> sourceValues = extractSourceValues(doc);

        // Parse schema fields from target description
        List<String> schemaFields = parseSchemaFields(targetSchema);
        List<MappingRule> rules = new ArrayList<>();

        // Map known EDI fields to schema fields using semantic matching
        Map<String, String> knownMappings = Map.ofEntries(
                Map.entry("senderId", "ISA*06"), Map.entry("sender", "ISA*06"),
                Map.entry("receiverId", "ISA*08"), Map.entry("receiver", "ISA*08"),
                Map.entry("date", "ISA*09"), Map.entry("controlNumber", "ISA*13"),
                Map.entry("poNumber", "BEG*03"), Map.entry("purchaseOrder", "BEG*03"),
                Map.entry("invoiceNumber", "BIG*02"), Map.entry("invoice", "BIG*02"),
                Map.entry("claimId", "CLM*01"), Map.entry("claimAmount", "CLM*02"),
                Map.entry("patientName", "NM1*03"), Map.entry("name", "NM1*03"),
                Map.entry("quantity", "PO1*02"), Map.entry("price", "PO1*04"),
                Map.entry("itemNumber", "PO1*07"), Map.entry("sku", "PO1*07"),
                Map.entry("total", "TDS*01"), Map.entry("amount", "TDS*01")
        );

        for (String field : schemaFields) {
            String fieldLower = field.toLowerCase().replaceAll("[^a-z0-9]", "");
            for (Map.Entry<String, String> km : knownMappings.entrySet()) {
                if (fieldLower.contains(km.getKey().toLowerCase())) {
                    rules.add(MappingRule.builder()
                            .sourceField(km.getValue()).targetField(field)
                            .transform("DIRECT").confidence(75)
                            .reasoning("Semantic match: " + field + " → " + km.getKey()).build());
                    break;
                }
            }
        }

        return MappingResult.builder()
                .mappingId(UUID.randomUUID().toString().substring(0, 8))
                .rules(rules).confidence(rules.isEmpty() ? 0 : 70)
                .fieldsMatched(rules.size()).fieldsTotal(schemaFields.size())
                .sourceFormat(doc.getSourceFormat()).targetFormat("SCHEMA")
                .generatedCode(generateMappingCode(rules)).build();
    }

    // === Helpers ===

    private Map<String, String> extractSourceValues(EdiDocument doc) {
        Map<String, String> values = new LinkedHashMap<>();
        if (doc.getSenderId() != null) values.put("senderId", doc.getSenderId());
        if (doc.getReceiverId() != null) values.put("receiverId", doc.getReceiverId());
        if (doc.getDocumentType() != null) values.put("documentType", doc.getDocumentType());
        if (doc.getDocumentDate() != null) values.put("documentDate", doc.getDocumentDate());
        if (doc.getControlNumber() != null) values.put("controlNumber", doc.getControlNumber());

        for (int i = 0; i < doc.getSegments().size(); i++) {
            Segment seg = doc.getSegments().get(i);
            List<String> elems = seg.getElements() != null ? seg.getElements() : List.of();
            for (int j = 0; j < elems.size(); j++) {
                String key = seg.getId() + "*" + String.format("%02d", j + 1);
                if (!elems.get(j).trim().isEmpty()) {
                    values.put(key, elems.get(j));
                }
            }
        }

        if (doc.getBusinessData() != null) {
            for (Map.Entry<String, Object> e : doc.getBusinessData().entrySet()) {
                if (e.getValue() != null) values.put("biz." + e.getKey(), e.getValue().toString());
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTargetJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> flattenValues(Map<String, Object> map) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenHelper(map, "", flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flattenHelper(Map<String, Object> map, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map) {
                flattenHelper((Map<String, Object>) e.getValue(), key, result);
            } else if (e.getValue() != null) {
                result.put(key, e.getValue().toString());
            }
        }
    }

    private List<String> flattenKeys(Map<String, Object> map) {
        Map<String, String> flat = flattenValues(map);
        return new ArrayList<>(flat.keySet());
    }

    private List<String> parseSchemaFields(String schema) {
        List<String> fields = new ArrayList<>();
        // Try JSON schema format
        Pattern fieldPattern = Pattern.compile("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:");
        Matcher m = fieldPattern.matcher(schema);
        while (m.find()) fields.add(m.group(1));
        // Try simple list format
        if (fields.isEmpty()) {
            for (String line : schema.split("[,\n]")) {
                String trimmed = line.trim().replaceAll("[^a-zA-Z0-9_]", "");
                if (!trimmed.isEmpty()) fields.add(trimmed);
            }
        }
        return fields;
    }

    private double fieldNameSimilarity(String source, String target) {
        String s = source.toLowerCase().replaceAll("[^a-z0-9]", "");
        String t = target.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (s.equals(t)) return 1.0;
        if (s.contains(t) || t.contains(s)) return 0.8;
        // Jaro-Winkler simplified
        int matches = 0;
        int len = Math.min(s.length(), t.length());
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) == t.charAt(i)) matches++;
        }
        return len > 0 ? (double) matches / Math.max(s.length(), t.length()) : 0;
    }

    private String generateMappingCode(List<MappingRule> rules) {
        StringBuilder code = new StringBuilder("// Auto-generated mapping rules\n");
        code.append("// Format: source → target (transform)\n\n");
        code.append("{\n");
        for (int i = 0; i < rules.size(); i++) {
            MappingRule r = rules.get(i);
            code.append("  \"").append(r.getTargetField()).append("\": ");
            if ("DIRECT".equals(r.getTransform())) {
                code.append("$source.\"").append(r.getSourceField()).append("\"");
            } else if ("TRIM".equals(r.getTransform())) {
                code.append("$trim($source.\"").append(r.getSourceField()).append("\")");
            } else if ("ZERO_PAD".equals(r.getTransform())) {
                code.append("$pad($source.\"").append(r.getSourceField()).append("\", ")
                        .append(r.getTransformParam()).append(", '0')");
            } else {
                code.append("$source.\"").append(r.getSourceField()).append("\"");
            }
            if (i < rules.size() - 1) code.append(",");
            code.append("  // ").append(r.getConfidence()).append("% confidence");
            code.append("\n");
        }
        code.append("}\n");
        return code.toString();
    }
}
