package com.filetransfer.edi.map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversionMapDefinition {
    private String mapId;
    private String name;
    private String version;
    private String sourceType;      // e.g., "X12_850"
    private String targetType;      // e.g., "PURCHASE_ORDER_INH"
    private String sourceStandard;  // e.g., "X12"
    private String targetStandard;  // e.g., "INHOUSE"
    private boolean bidirectional;
    private String description;
    private String status;          // ACTIVE, DRAFT, DEPRECATED
    private double confidence;      // 0.0-1.0 for trained maps
    private List<FieldMapping> fieldMappings;
    private List<LoopMapping> loopMappings;
    private Map<String, List<CodeTableEntry>> codeTables;
    private Map<String, Object> metadata;

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FieldMapping {
        private String sourcePath;     // e.g., "BEG.03" or "header.buyer.id"
        private String targetPath;     // e.g., "documentNumber" or "N1[BY].04"
        private String transform;      // COPY, TRIM, PAD, DATE_FORMAT, SUBSTRING, CONCAT, LOOKUP, CONDITIONAL
        private Map<String, String> transformConfig; // e.g., {"sourceFormat":"yyyyMMdd","targetFormat":"yyyy-MM-dd"}
        private String defaultValue;
        private String condition;      // e.g., "sourcePath != null"
        private boolean required;
        private double confidence;     // for AI-trained mappings
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LoopMapping {
        private String sourceLoop;     // e.g., "PO1" (X12 loop identifier)
        private String targetArray;    // e.g., "lineItems"
        private List<FieldMapping> fieldMappings; // mappings WITHIN the loop
        private String filter;         // optional: only include segments matching condition
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CodeTableEntry {
        private String sourceCode;
        private String targetCode;
        private String description;
    }
}
