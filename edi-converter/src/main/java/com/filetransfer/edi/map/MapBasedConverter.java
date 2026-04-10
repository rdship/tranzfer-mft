package com.filetransfer.edi.map;

import com.filetransfer.edi.model.EdiDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies a ConversionMapDefinition to an EdiDocument to produce structured output.
 * Supports field-level transforms (COPY, TRIM, DATE_FORMAT, LOOKUP, PAD, etc.)
 * and loop mappings for repeating segments.
 */
@Service
@Slf4j
public class MapBasedConverter {

    /**
     * Apply a conversion map to an EdiDocument.
     * Returns the converted content as a structured Map (can be serialized to JSON/XML/etc).
     */
    public Map<String, Object> convert(EdiDocument source, ConversionMapDefinition map) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Apply field mappings
        if (map.getFieldMappings() != null) {
            for (var fm : map.getFieldMappings()) {
                Object value = resolveSourceValue(source, fm.getSourcePath());
                if (value == null && fm.getDefaultValue() != null) {
                    value = fm.getDefaultValue();
                }
                if (value == null && !fm.isRequired()) continue;

                // Apply transform
                value = applyTransform(value, fm, map.getCodeTables());

                // Set in result
                if (value != null) {
                    setNestedValue(result, fm.getTargetPath(), value);
                }
            }
        }

        // Apply loop mappings
        if (map.getLoopMappings() != null) {
            for (var lm : map.getLoopMappings()) {
                List<Map<String, Object>> items = convertLoop(source, lm, map.getCodeTables());
                if (!items.isEmpty()) {
                    setNestedValue(result, lm.getTargetArray(), items);
                }
            }
        }

        return result;
    }

    private Object resolveSourceValue(EdiDocument doc, String path) {
        // Navigate the EdiDocument segments/elements by path
        // Path format: "SEGMENT.elementIndex" (e.g., "BEG.03") or "SEGMENT[qualifier].elementIndex"
        if (path == null || path.isEmpty()) return null;
        if (doc.getSegments() == null) return null;

        String[] parts = path.split("\\.");
        String segmentId = parts[0];
        int elementIndex = parts.length > 1 ? parseElementIndex(parts[1]) : 0;

        // Handle qualified segment lookup: "N1[BY]" means N1 where element 01 = "BY"
        String qualifier = null;
        if (segmentId.contains("[")) {
            int bracketStart = segmentId.indexOf('[');
            int bracketEnd = segmentId.indexOf(']');
            qualifier = segmentId.substring(bracketStart + 1, bracketEnd);
            segmentId = segmentId.substring(0, bracketStart);
        }

        // Find matching segment in document
        for (var segment : doc.getSegments()) {
            if (segment.getId().equals(segmentId)) {
                // Check qualifier if specified
                if (qualifier != null) {
                    List<String> elements = segment.getElements();
                    if (elements == null || elements.isEmpty() || !qualifier.equals(elements.get(0))) {
                        continue; // not the right qualified segment
                    }
                }
                List<String> elements = segment.getElements();
                if (elements != null && elementIndex >= 0 && elementIndex < elements.size()) {
                    String val = elements.get(elementIndex);
                    return (val != null && !val.isEmpty()) ? val : null;
                }
            }
        }
        return null;
    }

    private int parseElementIndex(String indexStr) {
        try {
            return Integer.parseInt(indexStr) - 1; // Convert 1-based to 0-based
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Object applyTransform(Object value, ConversionMapDefinition.FieldMapping fm,
                                   Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables) {
        if (value == null || fm.getTransform() == null) return value;
        String s = String.valueOf(value);
        Map<String, String> cfg = fm.getTransformConfig() != null ? fm.getTransformConfig() : Map.of();

        return switch (fm.getTransform().toUpperCase()) {
            case "COPY" -> s;
            case "TRIM" -> s.trim();
            case "PAD" -> padValue(s, cfg);
            case "SUBSTRING" -> substringValue(s, cfg);
            case "CONCAT" -> concatValue(s, cfg);
            case "DATE_FORMAT" -> formatDate(s, cfg);
            case "LOOKUP" -> lookupCode(s, cfg, codeTables);
            case "UPPERCASE" -> s.toUpperCase();
            case "LOWERCASE" -> s.toLowerCase();
            case "DEFAULT_IF_EMPTY" -> s.isEmpty() ? cfg.getOrDefault("default", "") : s;
            case "SPLIT" -> s.split(cfg.getOrDefault("delimiter", ","));
            case "CONDITIONAL" -> evaluateConditional(s, cfg);
            default -> s;
        };
    }

    private String formatDate(String value, Map<String, String> cfg) {
        try {
            String srcFmt = cfg.getOrDefault("sourceFormat", "yyyyMMdd");
            String tgtFmt = cfg.getOrDefault("targetFormat", "yyyy-MM-dd");
            LocalDate date = LocalDate.parse(value, DateTimeFormatter.ofPattern(srcFmt));
            return date.format(DateTimeFormatter.ofPattern(tgtFmt));
        } catch (Exception e) {
            return value; // Return as-is if date parsing fails
        }
    }

    private String lookupCode(String value, Map<String, String> cfg,
                               Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables) {
        String tableName = cfg.get("table");
        if (tableName == null || codeTables == null) return value;
        List<ConversionMapDefinition.CodeTableEntry> entries = codeTables.get(tableName);
        if (entries == null) return value;
        for (var entry : entries) {
            if (entry.getSourceCode().equals(value)) return entry.getTargetCode();
        }
        return cfg.getOrDefault("default", value); // fallback
    }

    private String padValue(String s, Map<String, String> cfg) {
        int length = Integer.parseInt(cfg.getOrDefault("length", "0"));
        String padChar = cfg.getOrDefault("padChar", " ");
        String side = cfg.getOrDefault("side", "right");
        if (length <= 0) return s;
        if ("left".equals(side)) {
            return String.format("%" + length + "s", s).replace(' ', padChar.charAt(0));
        }
        return String.format("%-" + length + "s", s).replace(' ', padChar.charAt(0));
    }

    private String substringValue(String s, Map<String, String> cfg) {
        int start = Integer.parseInt(cfg.getOrDefault("start", "0"));
        int end = cfg.containsKey("end") ? Integer.parseInt(cfg.get("end")) : s.length();
        return s.substring(Math.min(start, s.length()), Math.min(end, s.length()));
    }

    private String concatValue(String s, Map<String, String> cfg) {
        String prefix = cfg.getOrDefault("prefix", "");
        String suffix = cfg.getOrDefault("suffix", "");
        return prefix + s + suffix;
    }

    private String evaluateConditional(String s, Map<String, String> cfg) {
        // Simple conditional: if value matches "when" return "then", else return "otherwise"
        String when = cfg.get("when");
        String then = cfg.get("then");
        String otherwise = cfg.getOrDefault("otherwise", s);
        if (when != null && when.equals(s)) return then != null ? then : s;
        return otherwise;
    }

    private List<Map<String, Object>> convertLoop(EdiDocument doc, ConversionMapDefinition.LoopMapping lm,
                                                     Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (doc.getSegments() == null) return items;

        // Find all segments that belong to this loop
        String loopId = lm.getSourceLoop();
        List<EdiDocument.Segment> loopSegments = doc.getSegments().stream()
            .filter(s -> s.getId().equals(loopId))
            .collect(Collectors.toList());

        for (var seg : loopSegments) {
            // Apply optional filter
            if (lm.getFilter() != null && !lm.getFilter().isEmpty()) {
                if (!matchesFilter(seg, lm.getFilter())) continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            EdiDocument singleDoc = EdiDocument.builder()
                .segments(List.of(seg))
                .sourceFormat(doc.getSourceFormat())
                .build();
            if (lm.getFieldMappings() != null) {
                for (var fm : lm.getFieldMappings()) {
                    Object value = resolveSourceValue(singleDoc, fm.getSourcePath());
                    if (value == null && fm.getDefaultValue() != null) value = fm.getDefaultValue();
                    if (value != null) {
                        value = applyTransform(value, fm, codeTables);
                        setNestedValue(item, fm.getTargetPath(), value);
                    }
                }
            }
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private boolean matchesFilter(EdiDocument.Segment seg, String filter) {
        // Simple filter: "elementIndex=value" e.g., "01=BY"
        try {
            String[] parts = filter.split("=", 2);
            int idx = Integer.parseInt(parts[0].trim()) - 1;
            String expected = parts[1].trim();
            List<String> elements = seg.getElements();
            return elements != null && idx >= 0 && idx < elements.size()
                && expected.equals(elements.get(idx));
        } catch (Exception e) {
            return true; // If filter is unparseable, include everything
        }
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
        }
        current.put(parts[parts.length - 1], value);
    }
}
