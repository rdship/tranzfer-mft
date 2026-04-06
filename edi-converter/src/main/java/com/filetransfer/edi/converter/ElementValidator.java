package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.EdifactSegmentDefinitions;
import com.filetransfer.edi.parser.X12SegmentDefinitions;
import lombok.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Element-level validator that checks individual segment elements against
 * the X12 and EDIFACT segment definition registries.
 *
 * For each element in a segment, validates:
 * - Required elements are present and non-empty
 * - Data type matches (AN=any string, N/N0/N2=numeric, ID=in code set, DT=valid date, TM=valid time, R=decimal)
 * - Length within min/max bounds
 * - ID type values are in the valid set (if validValues is specified)
 */
@Service
public class ElementValidator {

    private final X12SegmentDefinitions x12Defs;
    private final EdifactSegmentDefinitions edifactDefs;

    public ElementValidator(X12SegmentDefinitions x12Defs, EdifactSegmentDefinitions edifactDefs) {
        this.x12Defs = x12Defs;
        this.edifactDefs = edifactDefs;
    }

    /**
     * Validate a single segment against its definition.
     *
     * @param segment      the parsed segment
     * @param format       "X12" or "EDIFACT"
     * @param segmentIndex which occurrence in the document (0-based)
     * @return list of issues found (empty if segment passes all checks or has no definition)
     */
    public List<ElementIssue> validateSegment(EdiDocument.Segment segment, String format, int segmentIndex) {
        List<ElementIssue> issues = new ArrayList<>();
        String segId = segment.getId();

        if ("X12".equalsIgnoreCase(format)) {
            X12SegmentDefinitions.SegmentDef def = x12Defs.getDefinition(segId);
            if (def == null) {
                // Unknown segment — skip gracefully
                return issues;
            }
            validateX12Segment(segment, def, segmentIndex, issues);
        } else if ("EDIFACT".equalsIgnoreCase(format)) {
            EdifactSegmentDefinitions.SegmentDef def = edifactDefs.getDefinition(segId);
            if (def == null) {
                return issues;
            }
            validateEdifactSegment(segment, def, segmentIndex, issues);
        }

        return issues;
    }

    /**
     * Validate all segments in the document.
     */
    public List<ElementIssue> validateDocument(EdiDocument doc) {
        List<ElementIssue> issues = new ArrayList<>();
        if (doc == null || doc.getSegments() == null) {
            return issues;
        }
        String format = doc.getSourceFormat();
        List<EdiDocument.Segment> segments = doc.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            issues.addAll(validateSegment(segments.get(i), format, i));
        }
        return issues;
    }

    // -------------------------------------------------------
    //  X12 element validation
    // -------------------------------------------------------

    private void validateX12Segment(EdiDocument.Segment segment, X12SegmentDefinitions.SegmentDef def,
                                     int segmentIndex, List<ElementIssue> issues) {
        List<String> elements = segment.getElements();
        List<X12SegmentDefinitions.ElementDef> elementDefs = def.getElements();

        if (elements == null) {
            elements = List.of();
        }

        // Check each defined element
        for (X12SegmentDefinitions.ElementDef elemDef : elementDefs) {
            int pos = elemDef.getPosition(); // 1-based
            int idx = pos - 1;               // 0-based index into elements list

            String value = (idx < elements.size()) ? elements.get(idx) : null;
            // Present means non-null and non-zero-length.
            // In X12, space-padded fields (e.g. ISA02 "          ") are valid — not "empty".
            boolean present = value != null && !value.isEmpty();

            // Required check
            if (elemDef.isRequired() && !present) {
                issues.add(ElementIssue.builder()
                        .segmentId(segment.getId())
                        .segmentIndex(segmentIndex)
                        .elementPosition(pos)
                        .elementName(elemDef.getName())
                        .severity("ERROR")
                        .problem("Required element is missing or empty")
                        .expected("Non-empty " + elemDef.getDataType() + " value")
                        .actual(value == null ? "(absent)" : "(empty)")
                        .build());
                continue; // Skip further checks for absent required element
            }

            if (!present) {
                // Optional and not present — fine
                continue;
            }

            String trimmed = value.trim();

            // Length checks
            validateLength(segment.getId(), segmentIndex, pos, elemDef.getName(),
                    elemDef.getMinLength(), elemDef.getMaxLength(), value, issues);

            // Data type checks — for types that need numeric validation, use trimmed;
            // for AN/B/ID types that could be space-padded (like ISA fields), skip type
            // check on all-space values
            if (trimmed.isEmpty()) {
                // All-space value — skip data type check (passes for AN, will fail for
                // numeric types but that is expected only if someone misconfigures data)
                continue;
            }
            validateX12DataType(segment.getId(), segmentIndex, pos, elemDef.getName(),
                    elemDef.getDataType(), trimmed, elemDef.getValidValues(), issues);
        }
    }

    private void validateX12DataType(String segId, int segIdx, int pos, String name,
                                      X12SegmentDefinitions.ElementDef.DataType dataType,
                                      String value, Set<String> validValues,
                                      List<ElementIssue> issues) {
        switch (dataType) {
            case N:
            case N0:
                if (!isInteger(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be a whole number")
                            .expected("Numeric integer").actual(value).build());
                }
                break;

            case N2:
                if (!isNumeric(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be numeric (2 implied decimal places)")
                            .expected("Numeric value").actual(value).build());
                }
                break;

            case R:
                if (!isDecimal(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be a decimal number")
                            .expected("Decimal number").actual(value).build());
                }
                break;

            case DT:
                if (!isValidDate(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Invalid date format")
                            .expected("CCYYMMDD (8 digits) or YYMMDD (6 digits)").actual(value).build());
                }
                break;

            case TM:
                if (!isValidTime(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Invalid time format")
                            .expected("HHMM (4 digits) or HHMMSS (6 digits) or HHMMSSD.. (up to 8 digits)").actual(value).build());
                }
                break;

            case ID:
                if (validValues != null && !validValues.isEmpty() && !validValues.contains(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value not in valid code set")
                            .expected("One of: " + validValues).actual(value).build());
                }
                break;

            case AN:
            case B:
                // Alphanumeric / Binary — any value is fine (length already checked)
                break;
        }
    }

    // -------------------------------------------------------
    //  EDIFACT element validation
    // -------------------------------------------------------

    private void validateEdifactSegment(EdiDocument.Segment segment, EdifactSegmentDefinitions.SegmentDef def,
                                         int segmentIndex, List<ElementIssue> issues) {
        List<String> elements = segment.getElements();
        List<EdifactSegmentDefinitions.ElementDef> elementDefs = def.getElements();

        if (elements == null) {
            elements = List.of();
        }

        for (EdifactSegmentDefinitions.ElementDef elemDef : elementDefs) {
            int pos = elemDef.getPosition();
            int idx = pos - 1;

            String value = (idx < elements.size()) ? elements.get(idx) : null;
            boolean present = value != null && !value.isEmpty();

            if (elemDef.isRequired() && !present) {
                issues.add(ElementIssue.builder()
                        .segmentId(segment.getId())
                        .segmentIndex(segmentIndex)
                        .elementPosition(pos)
                        .elementName(elemDef.getName())
                        .severity("ERROR")
                        .problem("Required element is missing or empty")
                        .expected("Non-empty " + elemDef.getDataType() + " value")
                        .actual(value == null ? "(absent)" : "(empty)")
                        .build());
                continue;
            }

            if (!present) {
                continue;
            }

            String trimmed = value.trim();

            // Length checks (for COMP elements, check the whole composite string)
            validateLength(segment.getId(), segmentIndex, pos, elemDef.getName(),
                    elemDef.getMinLength(), elemDef.getMaxLength(), value, issues);

            // Data type checks — skip for all-space values
            if (trimmed.isEmpty()) {
                continue;
            }
            validateEdifactDataType(segment.getId(), segmentIndex, pos, elemDef.getName(),
                    elemDef.getDataType(), trimmed, elemDef.getValidValues(), issues);
        }
    }

    private void validateEdifactDataType(String segId, int segIdx, int pos, String name,
                                          EdifactSegmentDefinitions.ElementDef.DataType dataType,
                                          String value, Set<String> validValues,
                                          List<ElementIssue> issues) {
        switch (dataType) {
            case N:
                if (!isInteger(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be numeric")
                            .expected("Numeric integer").actual(value).build());
                }
                break;

            case R:
                if (!isDecimal(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be a decimal number")
                            .expected("Decimal number").actual(value).build());
                }
                break;

            case DT:
                if (!isValidDate(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Invalid date format")
                            .expected("CCYYMMDD or YYMMDD").actual(value).build());
                }
                break;

            case TM:
                if (!isValidTime(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Invalid time format")
                            .expected("HHMM or HHMMSS").actual(value).build());
                }
                break;

            case A:
                if (!value.matches("[A-Za-z ]*")) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value must be alphabetic only")
                            .expected("Alphabetic characters").actual(value).build());
                }
                break;

            case ID:
                if (validValues != null && !validValues.isEmpty() && !validValues.contains(value)) {
                    issues.add(ElementIssue.builder()
                            .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                            .severity("ERROR").problem("Value not in valid code set")
                            .expected("One of: " + validValues).actual(value).build());
                }
                break;

            case AN:
            case COMP:
                // Alphanumeric / Composite — any value OK (length already checked)
                break;
        }
    }

    // -------------------------------------------------------
    //  Common validation helpers
    // -------------------------------------------------------

    private void validateLength(String segId, int segIdx, int pos, String name,
                                 int minLength, int maxLength, String value,
                                 List<ElementIssue> issues) {
        int len = value.length();
        if (len < minLength) {
            issues.add(ElementIssue.builder()
                    .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                    .severity("ERROR").problem("Element too short")
                    .expected("Min length " + minLength).actual("Length " + len + " ('" + value + "')").build());
        }
        if (len > maxLength) {
            issues.add(ElementIssue.builder()
                    .segmentId(segId).segmentIndex(segIdx).elementPosition(pos).elementName(name)
                    .severity("ERROR").problem("Element too long")
                    .expected("Max length " + maxLength).actual("Length " + len + " ('" + value + "')").build());
        }
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 0 && c == '-') continue;
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        boolean hasDot = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 0 && c == '-') continue;
            if (c == '.') {
                if (hasDot) return false;
                hasDot = true;
                continue;
            }
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isDecimal(String value) {
        return isNumeric(value);
    }

    private static boolean isValidDate(String value) {
        if (value == null) return false;
        // Accept 6-digit (YYMMDD) or 8-digit (CCYYMMDD)
        if (value.length() != 6 && value.length() != 8) return false;
        if (!isInteger(value)) return false;
        // Basic month/day range check
        int offset = (value.length() == 8) ? 4 : 2;
        int month = Integer.parseInt(value.substring(offset, offset + 2));
        int day = Integer.parseInt(value.substring(offset + 2, offset + 4));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    private static boolean isValidTime(String value) {
        if (value == null) return false;
        // Accept 4 (HHMM), 6 (HHMMSS), or up to 8 digits
        if (value.length() < 4 || value.length() > 8) return false;
        if (!isInteger(value)) return false;
        int hour = Integer.parseInt(value.substring(0, 2));
        int minute = Integer.parseInt(value.substring(2, 4));
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ElementIssue {
        private String segmentId;
        private int segmentIndex;     // which occurrence in the document (0-based)
        private int elementPosition;  // 1-based position within the segment
        private String elementName;
        private String severity;      // ERROR, WARNING
        private String problem;
        private String expected;
        private String actual;
    }
}
