package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * EDI Compliance Scorer — rates documents 0-100 against the standard.
 *
 * Checks:
 *   - Structure compliance (required segments, order, nesting)
 *   - Element compliance (data types, lengths, required values)
 *   - Business rule compliance (valid codes, enumerated values)
 *   - Best practice compliance (naming, padding, versioning)
 *
 * Returns a detailed scorecard with per-category breakdown and
 * specific recommendations for improvement.
 */
@Service @Slf4j
public class ComplianceScorer {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ComplianceReport {
        private int overallScore;           // 0-100
        private String grade;               // A+, A, B+, B, C, D, F
        private int structureScore;         // 0-100
        private int elementScore;           // 0-100
        private int businessRuleScore;      // 0-100
        private int bestPracticeScore;      // 0-100
        private String format;
        private String documentType;
        private int totalChecks;
        private int passed;
        private int warnings;
        private int failures;
        private List<CheckResult> details;
        private List<String> recommendations;
        private String verdict;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CheckResult {
        private String category;    // STRUCTURE, ELEMENT, BUSINESS_RULE, BEST_PRACTICE
        private String check;       // What was checked
        private String status;      // PASS, WARN, FAIL
        private String message;
        private int pointsDeducted;
        private String recommendation;
    }

    // Required X12 segments per transaction type
    private static final Map<String, List<String>> X12_REQUIRED = Map.of(
            "837", List.of("ISA", "GS", "ST", "BHT", "NM1", "CLM", "SE", "GE", "IEA"),
            "850", List.of("ISA", "GS", "ST", "BEG", "PO1", "CTT", "SE", "GE", "IEA"),
            "810", List.of("ISA", "GS", "ST", "BIG", "IT1", "TDS", "SE", "GE", "IEA"),
            "856", List.of("ISA", "GS", "ST", "BSN", "HL", "SE", "GE", "IEA"),
            "820", List.of("ISA", "GS", "ST", "BPR", "SE", "GE", "IEA"),
            "270", List.of("ISA", "GS", "ST", "BHT", "HL", "NM1", "SE", "GE", "IEA"),
            "997", List.of("ISA", "GS", "ST", "AK1", "AK9", "SE", "GE", "IEA")
    );

    private static final List<String> X12_SEGMENT_ORDER = List.of(
            "ISA", "GS", "ST", "BHT", "BEG", "BIG", "BSN", "BPR",
            "NM1", "N3", "N4", "PER",
            "HL", "CLM", "SV1", "DTP",
            "PO1", "IT1", "PID", "MEA",
            "CTT", "TDS", "AMT",
            "SE", "GE", "IEA"
    );

    // Valid X12 qualifier codes
    private static final Set<String> VALID_ID_QUALIFIERS = Set.of(
            "01", "02", "03", "04", "08", "09", "12", "13", "14", "15", "16",
            "17", "18", "19", "20", "27", "28", "30", "33", "ZZ"
    );

    public ComplianceReport score(EdiDocument doc) {
        return switch (doc.getSourceFormat()) {
            case "X12" -> scoreX12(doc);
            case "EDIFACT" -> scoreEdifact(doc);
            case "HL7" -> scoreHl7(doc);
            default -> scoreGeneric(doc);
        };
    }

    // === X12 Scoring ===
    private ComplianceReport scoreX12(EdiDocument doc) {
        List<CheckResult> checks = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        String txnType = doc.getDocumentType();

        // --- STRUCTURE CHECKS (40% weight) ---

        // Required segments
        List<String> required = X12_REQUIRED.getOrDefault(txnType,
                List.of("ISA", "GS", "ST", "SE", "GE", "IEA"));
        Set<String> present = new HashSet<>();
        for (Segment seg : doc.getSegments()) present.add(seg.getId());

        for (String req : required) {
            if (present.contains(req)) {
                checks.add(check("STRUCTURE", "Required segment " + req, "PASS", "", 0, null));
            } else {
                checks.add(check("STRUCTURE", "Required segment " + req, "FAIL",
                        "Missing required segment: " + req, 8,
                        "Add " + req + " segment to the transaction"));
            }
        }

        // Segment order check
        List<String> actualOrder = doc.getSegments().stream().map(Segment::getId).toList();
        boolean orderValid = isOrderValid(actualOrder, X12_SEGMENT_ORDER);
        if (orderValid) {
            checks.add(check("STRUCTURE", "Segment ordering", "PASS", "", 0, null));
        } else {
            checks.add(check("STRUCTURE", "Segment ordering", "WARN",
                    "Segments may not be in standard order", 3,
                    "Reorder segments: ISA → GS → ST → data → SE → GE → IEA"));
        }

        // ISA/IEA matching
        long isaCount = actualOrder.stream().filter("ISA"::equals).count();
        long ieaCount = actualOrder.stream().filter("IEA"::equals).count();
        if (isaCount == ieaCount && isaCount > 0) {
            checks.add(check("STRUCTURE", "ISA/IEA pairing", "PASS", "", 0, null));
        } else {
            checks.add(check("STRUCTURE", "ISA/IEA pairing", "FAIL",
                    "ISA count (" + isaCount + ") != IEA count (" + ieaCount + ")", 10,
                    "Each ISA must have a matching IEA"));
        }

        // GS/GE matching
        long gsCount = actualOrder.stream().filter("GS"::equals).count();
        long geCount = actualOrder.stream().filter("GE"::equals).count();
        if (gsCount == geCount) {
            checks.add(check("STRUCTURE", "GS/GE pairing", "PASS", "", 0, null));
        } else {
            checks.add(check("STRUCTURE", "GS/GE pairing", "FAIL",
                    "GS/GE mismatch", 10, "Each GS must have a matching GE"));
        }

        // ST/SE matching
        long stCount = actualOrder.stream().filter("ST"::equals).count();
        long seCount = actualOrder.stream().filter("SE"::equals).count();
        if (stCount == seCount) {
            checks.add(check("STRUCTURE", "ST/SE pairing", "PASS", "", 0, null));
        } else {
            checks.add(check("STRUCTURE", "ST/SE pairing", "FAIL",
                    "ST/SE mismatch", 10, "Each ST must have a matching SE"));
        }

        // --- ELEMENT CHECKS (30% weight) ---

        for (Segment seg : doc.getSegments()) {
            List<String> e = seg.getElements() != null ? seg.getElements() : List.of();
            switch (seg.getId()) {
                case "ISA" -> {
                    // ISA must have exactly 16 elements (excluding the ID itself)
                    if (e.size() == 16) {
                        checks.add(check("ELEMENT", "ISA element count", "PASS", "", 0, null));
                    } else {
                        checks.add(check("ELEMENT", "ISA element count", "FAIL",
                                "ISA has " + e.size() + " elements, expected 16", 8,
                                "ISA must have exactly 16 elements"));
                    }
                    // ISA01 and ISA03: qualifier codes
                    if (e.size() > 0 && VALID_ID_QUALIFIERS.contains(e.get(0).trim())) {
                        checks.add(check("ELEMENT", "ISA01 auth qualifier", "PASS", "", 0, null));
                    } else if (e.size() > 0) {
                        checks.add(check("ELEMENT", "ISA01 auth qualifier", "WARN",
                                "Non-standard qualifier: " + (e.size() > 0 ? e.get(0) : ""), 2,
                                "Use a standard qualifier code (00, 01, 03)"));
                    }
                    // ISA05/ISA07: ID qualifiers
                    if (e.size() > 4 && VALID_ID_QUALIFIERS.contains(e.get(4).trim())) {
                        checks.add(check("ELEMENT", "ISA05 sender qualifier", "PASS", "", 0, null));
                    }
                    if (e.size() > 6 && VALID_ID_QUALIFIERS.contains(e.get(6).trim())) {
                        checks.add(check("ELEMENT", "ISA07 receiver qualifier", "PASS", "", 0, null));
                    }
                    // ISA12: version
                    if (e.size() > 11) {
                        String ver = e.get(11).trim();
                        if (ver.equals("00501") || ver.equals("00401") || ver.equals("00801")) {
                            checks.add(check("ELEMENT", "ISA12 version", "PASS", "", 0, null));
                        } else {
                            checks.add(check("ELEMENT", "ISA12 version", "WARN",
                                    "Non-standard version: " + ver, 2, "Use 00501 or 00401"));
                        }
                    }
                }
                case "GS" -> {
                    if (e.size() >= 7) {
                        checks.add(check("ELEMENT", "GS element count", "PASS", "", 0, null));
                    } else {
                        checks.add(check("ELEMENT", "GS element count", "FAIL",
                                "GS has " + e.size() + " elements, need >=7", 5, ""));
                    }
                }
                case "ST" -> {
                    if (e.size() >= 1 && txnType != null && !txnType.isEmpty()) {
                        checks.add(check("ELEMENT", "ST transaction type", "PASS", "", 0, null));
                    }
                }
                case "SE" -> {
                    // Validate segment count
                    if (e.size() > 0) {
                        try {
                            int claimed = Integer.parseInt(e.get(0).trim());
                            long actual = doc.getSegments().stream()
                                    .dropWhile(s -> !"ST".equals(s.getId()))
                                    .takeWhile(s -> !"SE".equals(s.getId()) || s == seg)
                                    .count();
                            if (claimed == actual) {
                                checks.add(check("ELEMENT", "SE segment count", "PASS", "", 0, null));
                            } else {
                                checks.add(check("ELEMENT", "SE segment count", "FAIL",
                                        "Claims " + claimed + " segments but found " + actual, 5,
                                        "Fix SE01 to match actual segment count"));
                            }
                        } catch (Exception ex) {
                            checks.add(check("ELEMENT", "SE segment count", "FAIL",
                                    "Non-numeric value: " + e.get(0), 5, "SE01 must be numeric"));
                        }
                    }
                }
                default -> {}
            }

            // Check for empty required elements
            if (e.stream().anyMatch(el -> el != null && el.contains("  "))) {
                checks.add(check("ELEMENT", seg.getId() + " double-space",
                        "WARN", "Double spaces in elements", 1,
                        "Remove extra spaces from " + seg.getId()));
            }
        }

        // --- BUSINESS RULE CHECKS (20% weight) ---

        // Date validation
        for (Segment seg : doc.getSegments()) {
            if ("DTP".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 2) {
                String date = seg.getElements().get(2);
                if (isValidDate(date)) {
                    checks.add(check("BUSINESS_RULE", "DTP date validity", "PASS", "", 0, null));
                } else {
                    checks.add(check("BUSINESS_RULE", "DTP date validity", "FAIL",
                            "Invalid date: " + date, 5, "Use CCYYMMDD format"));
                }
            }
        }

        // NM1 entity type validation
        for (Segment seg : doc.getSegments()) {
            if ("NM1".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1) {
                String entityType = seg.getElements().get(1);
                if ("1".equals(entityType) || "2".equals(entityType)) {
                    checks.add(check("BUSINESS_RULE", "NM1 entity type", "PASS", "", 0, null));
                } else {
                    checks.add(check("BUSINESS_RULE", "NM1 entity type", "WARN",
                            "Unusual entity type: " + entityType, 2, "Use 1 (person) or 2 (organization)"));
                }
            }
        }

        // --- BEST PRACTICE CHECKS (10% weight) ---
        if (doc.getSegments().size() > 3) {
            checks.add(check("BEST_PRACTICE", "Minimum content", "PASS", "", 0, null));
        }
        if (doc.getSegments().size() > 1000) {
            checks.add(check("BEST_PRACTICE", "Document size", "WARN",
                    "Very large document (" + doc.getSegments().size() + " segments)", 2,
                    "Consider splitting into multiple transactions"));
        }

        return buildReport(checks, recommendations, doc.getSourceFormat(), txnType);
    }

    // === EDIFACT Scoring ===
    private ComplianceReport scoreEdifact(EdiDocument doc) {
        List<CheckResult> checks = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (Segment seg : doc.getSegments()) present.add(seg.getId());

        // Structure
        for (String req : List.of("UNB", "UNH", "UNT", "UNZ")) {
            checks.add(present.contains(req)
                    ? check("STRUCTURE", "Required " + req, "PASS", "", 0, null)
                    : check("STRUCTURE", "Required " + req, "FAIL", "Missing " + req, 10, "Add " + req));
        }

        // UNB/UNZ pairing
        long unbCount = doc.getSegments().stream().filter(s -> "UNB".equals(s.getId())).count();
        long unzCount = doc.getSegments().stream().filter(s -> "UNZ".equals(s.getId())).count();
        checks.add(unbCount == unzCount
                ? check("STRUCTURE", "UNB/UNZ pairing", "PASS", "", 0, null)
                : check("STRUCTURE", "UNB/UNZ pairing", "FAIL", "Mismatch", 10, ""));

        // Element checks
        for (Segment seg : doc.getSegments()) {
            if ("UNB".equals(seg.getId()) && seg.getElements() != null) {
                checks.add(seg.getElements().size() >= 4
                        ? check("ELEMENT", "UNB elements", "PASS", "", 0, null)
                        : check("ELEMENT", "UNB elements", "FAIL", "Too few elements", 5, "UNB needs >=4"));
            }
        }

        checks.add(check("BEST_PRACTICE", "Minimum content", "PASS", "", 0, null));
        return buildReport(checks, List.of(), "EDIFACT", doc.getDocumentType());
    }

    // === HL7 Scoring ===
    private ComplianceReport scoreHl7(EdiDocument doc) {
        List<CheckResult> checks = new ArrayList<>();
        List<String> order = doc.getSegments().stream().map(Segment::getId).toList();

        // MSH must be first
        if (!order.isEmpty() && "MSH".equals(order.get(0))) {
            checks.add(check("STRUCTURE", "MSH first segment", "PASS", "", 0, null));
        } else {
            checks.add(check("STRUCTURE", "MSH first segment", "FAIL",
                    "MSH must be the first segment", 15, "Move MSH to position 1"));
        }

        // MSH field count
        for (Segment seg : doc.getSegments()) {
            if ("MSH".equals(seg.getId())) {
                int fieldCount = seg.getElements() != null ? seg.getElements().size() : 0;
                checks.add(fieldCount >= 11
                        ? check("ELEMENT", "MSH field count", "PASS", "", 0, null)
                        : check("ELEMENT", "MSH field count", "WARN",
                        "MSH has " + fieldCount + " fields, recommend >=12", 3, ""));
            }
        }

        // PID required for ADT
        if (doc.getDocumentType() != null && doc.getDocumentType().contains("ADT")) {
            checks.add(order.contains("PID")
                    ? check("STRUCTURE", "PID for ADT", "PASS", "", 0, null)
                    : check("STRUCTURE", "PID for ADT", "FAIL", "ADT messages require PID", 10, ""));
        }

        checks.add(check("BEST_PRACTICE", "Minimum content", "PASS", "", 0, null));
        return buildReport(checks, List.of(), "HL7", doc.getDocumentType());
    }

    // === Generic ===
    private ComplianceReport scoreGeneric(EdiDocument doc) {
        List<CheckResult> checks = new ArrayList<>();
        checks.add(doc.getSegments().size() > 0
                ? check("STRUCTURE", "Has content", "PASS", "", 0, null)
                : check("STRUCTURE", "Has content", "FAIL", "Empty document", 50, ""));
        return buildReport(checks, List.of(), doc.getSourceFormat(), doc.getDocumentType());
    }

    // === Report Builder ===
    private ComplianceReport buildReport(List<CheckResult> checks, List<String> recs,
                                          String format, String docType) {
        int structPts = 100, elemPts = 100, bizPts = 100, bestPts = 100;
        int pass = 0, warn = 0, fail = 0;

        for (CheckResult c : checks) {
            switch (c.getStatus()) {
                case "PASS" -> pass++;
                case "WARN" -> warn++;
                case "FAIL" -> fail++;
            }
            switch (c.getCategory()) {
                case "STRUCTURE" -> structPts = Math.max(0, structPts - c.getPointsDeducted());
                case "ELEMENT" -> elemPts = Math.max(0, elemPts - c.getPointsDeducted());
                case "BUSINESS_RULE" -> bizPts = Math.max(0, bizPts - c.getPointsDeducted());
                case "BEST_PRACTICE" -> bestPts = Math.max(0, bestPts - c.getPointsDeducted());
            }
            if (c.getRecommendation() != null && !c.getRecommendation().isEmpty()) {
                recs.add(c.getRecommendation());
            }
        }

        // Weighted: 40% structure, 30% element, 20% business, 10% best practice
        int overall = (int) (structPts * 0.40 + elemPts * 0.30 + bizPts * 0.20 + bestPts * 0.10);
        String grade = overall >= 97 ? "A+" : overall >= 93 ? "A" : overall >= 90 ? "A-"
                : overall >= 87 ? "B+" : overall >= 83 ? "B" : overall >= 80 ? "B-"
                : overall >= 70 ? "C" : overall >= 60 ? "D" : "F";

        String verdict = switch (grade.charAt(0)) {
            case 'A' -> "Excellent — production ready";
            case 'B' -> "Good — minor issues to address";
            case 'C' -> "Acceptable — several issues need attention";
            case 'D' -> "Poor — significant compliance gaps";
            default -> "Failing — major structural issues";
        };

        return ComplianceReport.builder()
                .overallScore(overall).grade(grade)
                .structureScore(structPts).elementScore(elemPts)
                .businessRuleScore(bizPts).bestPracticeScore(bestPts)
                .format(format).documentType(docType)
                .totalChecks(checks.size()).passed(pass).warnings(warn).failures(fail)
                .details(checks).recommendations(recs).verdict(verdict).build();
    }

    private CheckResult check(String cat, String check, String status, String msg, int pts, String rec) {
        return CheckResult.builder().category(cat).check(check).status(status)
                .message(msg).pointsDeducted(pts).recommendation(rec != null ? rec : "").build();
    }

    private boolean isOrderValid(List<String> actual, List<String> expected) {
        int lastIndex = -1;
        for (String seg : actual) {
            int idx = expected.indexOf(seg);
            if (idx >= 0 && idx < lastIndex) return false;
            if (idx >= 0) lastIndex = idx;
        }
        return true;
    }

    private boolean isValidDate(String date) {
        if (date == null) return false;
        String clean = date.replaceAll("[^0-9]", "");
        if (clean.length() == 8) {
            try {
                int y = Integer.parseInt(clean.substring(0, 4));
                int m = Integer.parseInt(clean.substring(4, 6));
                int d = Integer.parseInt(clean.substring(6, 8));
                return y >= 1900 && y <= 2099 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
            } catch (Exception e) { return false; }
        }
        return clean.length() == 6; // YYMMDD also accepted
    }
}
