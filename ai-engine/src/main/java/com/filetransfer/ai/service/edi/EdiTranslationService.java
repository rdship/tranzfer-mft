package com.filetransfer.ai.service.edi;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * EDI/X12 Translation Service.
 * Detects, validates, and translates EDI files.
 *
 * Supported formats: X12 837 (claims), 835 (payments), 850 (purchase orders),
 * 856 (ship notice), 270/271 (eligibility), SWIFT MT messages.
 */
@Service @Slf4j
public class EdiTranslationService {

    private static final Map<String, String> EDI_TYPES = Map.of(
            "837", "Health Care Claim", "835", "Health Care Payment",
            "850", "Purchase Order", "856", "Ship Notice",
            "270", "Eligibility Inquiry", "271", "Eligibility Response",
            "997", "Functional Acknowledgment", "810", "Invoice",
            "820", "Payment Order", "834", "Benefit Enrollment"
    );

    /** Auto-detect EDI type from file content */
    public EdiDetectionResult detect(String content) {
        if (content == null || content.isBlank()) return EdiDetectionResult.builder().detected(false).build();

        // X12 detection: look for ISA/GS/ST segments
        if (content.contains("ISA*") || content.contains("ISA~")) {
            String transactionType = null;
            for (String line : content.split("~|\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ST*")) {
                    transactionType = trimmed.split("\\*")[1];
                    break;
                }
            }
            return EdiDetectionResult.builder().detected(true).format("X12")
                    .transactionType(transactionType)
                    .transactionName(EDI_TYPES.getOrDefault(transactionType, "Unknown"))
                    .build();
        }

        // SWIFT MT detection
        if (content.contains("{1:F") || content.contains(":20:") && content.contains(":32A:")) {
            return EdiDetectionResult.builder().detected(true).format("SWIFT")
                    .transactionType("MT").transactionName("SWIFT Message").build();
        }

        // EDIFACT detection
        if (content.contains("UNB+") || content.contains("UNH+")) {
            return EdiDetectionResult.builder().detected(true).format("EDIFACT")
                    .transactionType("EDIFACT").transactionName("UN/EDIFACT Message").build();
        }

        return EdiDetectionResult.builder().detected(false).build();
    }

    /** Validate EDI structure */
    public EdiValidationResult validate(String content) {
        EdiDetectionResult detection = detect(content);
        if (!detection.detected) return EdiValidationResult.builder().valid(false).errors(List.of("Not an EDI file")).build();

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if ("X12".equals(detection.format)) {
            if (!content.contains("ISA*")) errors.add("Missing ISA (Interchange Control Header)");
            if (!content.contains("GS*")) errors.add("Missing GS (Functional Group Header)");
            if (!content.contains("ST*")) errors.add("Missing ST (Transaction Set Header)");
            if (!content.contains("SE*")) warnings.add("Missing SE (Transaction Set Trailer)");
            if (!content.contains("GE*")) warnings.add("Missing GE (Functional Group Trailer)");
            if (!content.contains("IEA*")) warnings.add("Missing IEA (Interchange Control Trailer)");
        }

        return EdiValidationResult.builder()
                .valid(errors.isEmpty()).format(detection.format)
                .transactionType(detection.transactionType).errors(errors).warnings(warnings).build();
    }

    /** Translate EDI X12 to JSON */
    public Map<String, Object> translateToJson(String content) {
        EdiDetectionResult det = detect(content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", det.format);
        result.put("transactionType", det.transactionType);
        result.put("transactionName", det.transactionName);

        if ("X12".equals(det.format)) {
            List<Map<String, Object>> segments = new ArrayList<>();
            String delimiter = content.contains("~") ? "~" : "\n";
            for (String seg : content.split(delimiter)) {
                String trimmed = seg.trim();
                if (trimmed.isEmpty()) continue;
                String[] elements = trimmed.split("\\*");
                Map<String, Object> segMap = new LinkedHashMap<>();
                segMap.put("segmentId", elements[0]);
                List<String> values = new ArrayList<>();
                for (int i = 1; i < elements.length; i++) values.add(elements[i]);
                segMap.put("elements", values);
                segments.add(segMap);
            }
            result.put("segments", segments);
            result.put("segmentCount", segments.size());
        }

        return result;
    }

    /** Translate EDI X12 to CSV */
    public String translateToCsv(String content) {
        Map<String, Object> json = translateToJson(content);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) json.get("segments");
        if (segments == null) return "";

        StringBuilder csv = new StringBuilder("segment_id,element_1,element_2,element_3,element_4,element_5\n");
        for (Map<String, Object> seg : segments) {
            @SuppressWarnings("unchecked")
            List<String> elements = (List<String>) seg.get("elements");
            csv.append(seg.get("segmentId"));
            for (int i = 0; i < 5; i++) {
                csv.append(",");
                if (elements != null && i < elements.size()) csv.append(elements.get(i));
            }
            csv.append("\n");
        }
        return csv.toString();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EdiDetectionResult {
        private boolean detected;
        private String format;
        private String transactionType;
        private String transactionName;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EdiValidationResult {
        private boolean valid;
        private String format;
        private String transactionType;
        private List<String> errors;
        private List<String> warnings;
    }
}
