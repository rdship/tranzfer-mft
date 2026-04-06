package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Robust format detection for EDI documents.
 *
 * Detects: X12, EDIFACT, TRADACOMS, SWIFT_MT, PEPPOL, ISO20022, HL7, NACHA, BAI2, FIX, XML, JSON
 *
 * Production features:
 * - ISA detection with any element separator (not just *)
 * - UNA-aware EDIFACT detection
 * - SWIFT MT block structure validation
 * - NACHA multi-line 94-char validation
 * - Confidence scoring via detectWithConfidence()
 */
@Component @Slf4j
public class FormatDetector {

    /**
     * Detection result with confidence score and reason.
     */
    public record DetectionResult(String format, double confidence, String reason) {}

    /**
     * Detect the EDI format from content. Backward-compatible method.
     */
    public String detect(String content) {
        if (content == null || content.isBlank()) return "UNKNOWN";
        DetectionResult result = detectWithConfidence(content);
        return result.format();
    }

    /**
     * Detect the EDI format with a confidence score and reason.
     * Higher confidence means more certain the detection is correct.
     */
    public DetectionResult detectWithConfidence(String content) {
        if (content == null || content.isBlank()) {
            return new DetectionResult("UNKNOWN", 0.0, "Null or empty content");
        }
        String trimmed = content.trim();

        // --- X12: starts with ISA followed by any non-alphanumeric character ---
        if (trimmed.length() >= 4 && trimmed.startsWith("ISA") && !Character.isLetterOrDigit(trimmed.charAt(3))) {
            // Check if we have a proper 106-character ISA structure
            if (trimmed.length() >= 106) {
                return new DetectionResult("X12", 0.99, "ISA segment at position 0 with 106+ character structure");
            }
            return new DetectionResult("X12", 0.95, "ISA segment at position 0 with non-alphanumeric separator");
        }
        // ISA found but not at position 0
        if (containsIsaSegment(trimmed)) {
            return new DetectionResult("X12", 0.85, "ISA segment found but not at position 0");
        }

        // --- EDIFACT: UNA service string or UNB segment ---
        if (trimmed.startsWith("UNA") && trimmed.length() >= 9) {
            // Check for UNB after UNA (6 chars of service advice after "UNA")
            String afterUna = trimmed.substring(9).trim();
            if (afterUna.startsWith("UNB")) {
                return new DetectionResult("EDIFACT", 0.99, "UNA service string followed by UNB segment");
            }
            return new DetectionResult("EDIFACT", 0.95, "UNA service string present");
        }
        if (trimmed.startsWith("UNB+") || trimmed.startsWith("UNB:")) {
            return new DetectionResult("EDIFACT", 0.98, "UNB segment at position 0");
        }
        // UNB with custom element separator
        if (trimmed.length() >= 4 && trimmed.startsWith("UNB") && !Character.isLetterOrDigit(trimmed.charAt(3))) {
            return new DetectionResult("EDIFACT", 0.90, "UNB segment with custom element separator");
        }
        if (trimmed.contains("UNH+")) {
            return new DetectionResult("EDIFACT", 0.85, "UNH segment found in content");
        }

        // --- TRADACOMS: starts with STX= ---
        if (trimmed.startsWith("STX=") || trimmed.contains("STX=")) {
            double conf = trimmed.startsWith("STX=") ? 0.95 : 0.80;
            return new DetectionResult("TRADACOMS", conf,
                    trimmed.startsWith("STX=") ? "STX= at position 0" : "STX= found in content");
        }

        // --- SWIFT MT: block structure {1:F or {1:A and tag patterns ---
        if (isSwiftMt(trimmed)) {
            double conf = trimmed.startsWith("{1:F") || trimmed.startsWith("{1:A") ? 0.98 : 0.90;
            return new DetectionResult("SWIFT_MT", conf, "SWIFT MT block structure and/or tag patterns detected");
        }

        // --- PEPPOL / UBL: XML with oasis-open or urn:oasis:names ---
        if (isPeppol(trimmed)) {
            return new DetectionResult("PEPPOL", 0.95, "PEPPOL/UBL namespace or root element detected");
        }

        // --- SWIFT MX / ISO 20022: XML with urn:iso:std or <Document> ---
        if (isIso20022(trimmed)) {
            return new DetectionResult("ISO20022", 0.95, "ISO 20022 namespace or element detected");
        }

        // --- HL7 v2: starts with MSH| ---
        if (trimmed.startsWith("MSH|")) {
            return new DetectionResult("HL7", 0.98, "MSH| at position 0");
        }
        if (trimmed.contains("MSH|")) {
            return new DetectionResult("HL7", 0.85, "MSH| found in content");
        }

        // --- NACHA/ACH: fixed-width 94-char records starting with 101 ---
        DetectionResult nachaResult = detectNacha(trimmed);
        if (nachaResult != null) {
            return nachaResult;
        }

        // --- BAI2: starts with 01, or 02, ---
        if (trimmed.startsWith("01,") || trimmed.startsWith("02,")) {
            return new DetectionResult("BAI2", 0.90, "BAI2 record type at position 0");
        }

        // --- FIX: starts with 8=FIX ---
        if (trimmed.startsWith("8=FIX")) {
            return new DetectionResult("FIX", 0.98, "8=FIX at position 0");
        }
        if (trimmed.contains("8=FIX")) {
            return new DetectionResult("FIX", 0.85, "8=FIX found in content");
        }

        // --- XML (generic) ---
        if (trimmed.startsWith("<?xml") || (trimmed.startsWith("<") && !trimmed.startsWith("{"))) {
            return new DetectionResult("XML", 0.70, "XML-like content detected");
        }

        // --- JSON ---
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return new DetectionResult("JSON", 0.70, "JSON-like content detected");
        }

        return new DetectionResult("UNKNOWN", 0.0, "No recognized format pattern found");
    }

    /**
     * Check if content contains an ISA segment (not necessarily at position 0).
     * Looks for "ISA" followed by a non-alphanumeric character.
     */
    private boolean containsIsaSegment(String content) {
        int idx = content.indexOf("ISA");
        while (idx >= 0 && idx < content.length() - 3) {
            if (!Character.isLetterOrDigit(content.charAt(idx + 3))) {
                return true;
            }
            idx = content.indexOf("ISA", idx + 1);
        }
        return false;
    }

    /**
     * Improved SWIFT MT detection.
     * Checks for {1:F or {1:A block structure and multiple SWIFT tag patterns.
     */
    private boolean isSwiftMt(String content) {
        // Strong signal: block structure
        if (content.startsWith("{1:F") || content.startsWith("{1:A")) {
            return true;
        }
        // Legacy check: {1: block
        if (content.startsWith("{1:")) {
            return true;
        }
        // Check for multiple SWIFT tag patterns
        int tagCount = 0;
        if (content.contains(":20:")) tagCount++;
        if (content.contains(":32A:")) tagCount++;
        if (content.contains(":50K:")) tagCount++;
        if (content.contains(":59:")) tagCount++;
        if (content.contains(":71A:")) tagCount++;
        if (content.contains(":23B:")) tagCount++;
        // Need at least 2 tags to identify as SWIFT
        return tagCount >= 2;
    }

    private boolean isPeppol(String content) {
        return content.contains("urn:oasis:names:specification:ubl")
                || content.contains("<Invoice xmlns")
                || content.contains("<CreditNote xmlns")
                || content.contains("<Order xmlns")
                || content.contains("urn:cen.eu:en16931")
                || content.contains("peppol");
    }

    private boolean isIso20022(String content) {
        return content.contains("urn:iso:std:iso:20022")
                || content.contains("<BkToCstmrStmt>")
                || content.contains("pacs.008");
    }

    /**
     * Improved NACHA detection.
     * Verifies:
     * - First line starts with "101" (file header record type 1 + priority code 01)
     * - All lines are exactly 94 characters
     */
    private DetectionResult detectNacha(String content) {
        if (content.length() < 94) return null;

        String[] lines = content.split("\\n");
        if (lines.length == 0) return null;

        String firstLine = lines[0].replace("\r", "");

        // Strong check: first line starts with "101" and is 94 chars
        if (firstLine.length() == 94 && firstLine.startsWith("101")) {
            // Verify all non-empty lines are 94 characters
            boolean allCorrectLength = true;
            for (String line : lines) {
                String cleaned = line.replace("\r", "");
                if (cleaned.isEmpty()) continue;
                if (cleaned.length() != 94) {
                    allCorrectLength = false;
                    break;
                }
            }
            if (allCorrectLength) {
                return new DetectionResult("NACHA", 0.99, "All lines are 94 chars, first line starts with 101");
            }
            return new DetectionResult("NACHA", 0.90, "First line is 94 chars starting with 101, some lines differ");
        }

        // Weaker check: first line starts with "1" and is 94 chars
        if (firstLine.length() == 94 && firstLine.startsWith("1")) {
            return new DetectionResult("NACHA", 0.75, "First line is 94 chars starting with 1");
        }

        return null;
    }
}
