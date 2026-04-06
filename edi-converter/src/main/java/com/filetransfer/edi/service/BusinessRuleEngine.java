package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import lombok.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;

@Service
public class BusinessRuleEngine {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RuleResult {
        private String ruleId;
        private String ruleName;
        private String category;    // FINANCIAL, HEALTHCARE, LOGISTICS, COMPLIANCE, DATA_QUALITY
        private String severity;    // ERROR, WARNING, INFO
        private boolean passed;
        private String message;
        private String recommendation;
        private String affectedSegment;
    }

    @Data @AllArgsConstructor
    public static class BusinessRule {
        private String ruleId;
        private String name;
        private String category;
        private String severity;
        private String description;
        private Function<EdiDocument, RuleResult> evaluator;
    }

    private final Map<String, List<BusinessRule>> rulesByFormat = new HashMap<>();
    private final Map<String, List<BusinessRule>> rulesByTxnType = new HashMap<>();

    public BusinessRuleEngine() {
        registerX12Rules();
        registerEdifactRules();
        registerHl7Rules();
    }

    public List<RuleResult> evaluate(EdiDocument doc) {
        List<RuleResult> results = new ArrayList<>();
        String format = doc.getSourceFormat();
        String txnType = doc.getDocumentType();

        // Run format-level rules
        List<BusinessRule> formatRules = rulesByFormat.getOrDefault(format, List.of());
        for (BusinessRule rule : formatRules) {
            try {
                results.add(rule.getEvaluator().apply(doc));
            } catch (Exception e) {
                results.add(RuleResult.builder().ruleId(rule.getRuleId()).ruleName(rule.getName())
                    .passed(false).severity("WARNING").message("Rule evaluation error: " + e.getMessage()).build());
            }
        }

        // Run transaction-type-specific rules
        if (txnType != null) {
            String key = format + ":" + txnType;
            List<BusinessRule> txnRules = rulesByTxnType.getOrDefault(key, List.of());
            for (BusinessRule rule : txnRules) {
                try {
                    results.add(rule.getEvaluator().apply(doc));
                } catch (Exception e) {
                    results.add(RuleResult.builder().ruleId(rule.getRuleId()).ruleName(rule.getName())
                        .passed(false).severity("WARNING").message("Rule evaluation error: " + e.getMessage()).build());
                }
            }
        }

        return results;
    }

    private void registerX12Rules() {
        // === Format-Level X12 Rules ===

        // X12-001: ISA/IEA control numbers must match
        addFormatRule("X12", new BusinessRule("X12-001", "ISA/IEA Control Number Match", "COMPLIANCE", "ERROR",
            "ISA13 and IEA02 must contain the same control number",
            doc -> {
                String isaCtrl = null, ieaCtrl = null;
                for (var seg : doc.getSegments()) {
                    if ("ISA".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 12)
                        isaCtrl = seg.getElements().get(12).trim();
                    if ("IEA".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        ieaCtrl = seg.getElements().get(1).trim();
                }
                if (isaCtrl == null || ieaCtrl == null)
                    return RuleResult.builder().ruleId("X12-001").ruleName("ISA/IEA Control Number Match")
                        .category("COMPLIANCE").severity("ERROR").passed(false)
                        .message("Missing ISA or IEA segment").build();
                boolean match = isaCtrl.equals(ieaCtrl);
                return RuleResult.builder().ruleId("X12-001").ruleName("ISA/IEA Control Number Match")
                    .category("COMPLIANCE").severity("ERROR").passed(match)
                    .message(match ? "Control numbers match" : "ISA13=" + isaCtrl + " but IEA02=" + ieaCtrl)
                    .recommendation(match ? null : "Set IEA02 to match ISA13").build();
            }));

        // X12-002: GS/GE control numbers must match
        addFormatRule("X12", new BusinessRule("X12-002", "GS/GE Control Number Match", "COMPLIANCE", "ERROR",
            "GS06 and GE02 must contain the same control number",
            doc -> {
                String gsCtrl = null, geCtrl = null;
                for (var seg : doc.getSegments()) {
                    if ("GS".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 5)
                        gsCtrl = seg.getElements().get(5).trim();
                    if ("GE".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        geCtrl = seg.getElements().get(1).trim();
                }
                boolean match = gsCtrl != null && gsCtrl.equals(geCtrl);
                return RuleResult.builder().ruleId("X12-002").ruleName("GS/GE Control Number Match")
                    .category("COMPLIANCE").severity("ERROR").passed(match || gsCtrl == null)
                    .message(match ? "Control numbers match" : "GS06=" + gsCtrl + " but GE02=" + geCtrl).build();
            }));

        // X12-003: ST/SE control numbers must match
        addFormatRule("X12", new BusinessRule("X12-003", "ST/SE Control Number Match", "COMPLIANCE", "ERROR",
            "ST02 and SE02 must contain the same control number",
            doc -> {
                String stCtrl = null, seCtrl = null;
                for (var seg : doc.getSegments()) {
                    if ("ST".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        stCtrl = seg.getElements().get(1).trim();
                    if ("SE".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        seCtrl = seg.getElements().get(1).trim();
                }
                boolean match = stCtrl != null && stCtrl.equals(seCtrl);
                return RuleResult.builder().ruleId("X12-003").ruleName("ST/SE Control Number Match")
                    .category("COMPLIANCE").severity("ERROR").passed(match || stCtrl == null)
                    .message(match ? "Control numbers match" : "ST02=" + stCtrl + " but SE02=" + seCtrl).build();
            }));

        // X12-004: No duplicate control numbers across ISA envelopes
        addFormatRule("X12", new BusinessRule("X12-004", "Unique Control Numbers", "DATA_QUALITY", "WARNING",
            "Control numbers should be unique",
            doc -> {
                Set<String> seen = new HashSet<>();
                boolean unique = true;
                for (var seg : doc.getSegments()) {
                    if ("ISA".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 12) {
                        if (!seen.add(seg.getElements().get(12).trim())) unique = false;
                    }
                }
                return RuleResult.builder().ruleId("X12-004").ruleName("Unique Control Numbers")
                    .category("DATA_QUALITY").severity("WARNING").passed(unique)
                    .message(unique ? "All control numbers unique" : "Duplicate control numbers detected").build();
            }));

        // X12-005: Date fields should be valid dates
        addFormatRule("X12", new BusinessRule("X12-005", "Valid Date Fields", "DATA_QUALITY", "ERROR",
            "Date fields must contain valid dates",
            doc -> {
                List<String> badDates = new ArrayList<>();
                for (var seg : doc.getSegments()) {
                    if (seg.getElements() == null) continue;
                    // Check ISA09 (date)
                    if ("ISA".equals(seg.getId()) && seg.getElements().size() > 8) {
                        String d = seg.getElements().get(8).trim();
                        if (!d.isEmpty() && !isValidDate6(d)) badDates.add("ISA09=" + d);
                    }
                    // Check DTP03, DTM02
                    if ("DTP".equals(seg.getId()) && seg.getElements().size() > 2) {
                        String d = seg.getElements().get(2).trim();
                        if (!d.isEmpty() && !isValidDate8(d) && !isValidDateRange(d)) badDates.add("DTP03=" + d);
                    }
                    if ("DTM".equals(seg.getId()) && seg.getElements().size() > 1) {
                        String d = seg.getElements().get(1).trim();
                        if (!d.isEmpty() && !isValidDate8(d)) badDates.add("DTM02=" + d);
                    }
                }
                boolean ok = badDates.isEmpty();
                return RuleResult.builder().ruleId("X12-005").ruleName("Valid Date Fields")
                    .category("DATA_QUALITY").severity("ERROR").passed(ok)
                    .message(ok ? "All dates valid" : "Invalid dates: " + String.join(", ", badDates))
                    .recommendation(ok ? null : "Fix date format to CCYYMMDD or YYMMDD").build();
            }));

        // X12-006: NM1 person (type=1) must have last+first name, org (type=2) must have org name
        addFormatRule("X12", new BusinessRule("X12-006", "NM1 Name Completeness", "DATA_QUALITY", "WARNING",
            "Person names need last+first, organizations need org name",
            doc -> {
                List<String> issues = new ArrayList<>();
                for (var seg : doc.getSegments()) {
                    if (!"NM1".equals(seg.getId()) || seg.getElements() == null || seg.getElements().size() < 3) continue;
                    String entityType = seg.getElements().size() > 1 ? seg.getElements().get(1).trim() : "";
                    String lastName = seg.getElements().size() > 2 ? seg.getElements().get(2).trim() : "";
                    String firstName = seg.getElements().size() > 3 ? seg.getElements().get(3).trim() : "";
                    if ("1".equals(entityType)) {
                        if (lastName.isEmpty()) issues.add("NM1 person missing last name");
                        if (firstName.isEmpty()) issues.add("NM1 person missing first name");
                    } else if ("2".equals(entityType)) {
                        if (lastName.isEmpty()) issues.add("NM1 org missing organization name");
                    }
                }
                return RuleResult.builder().ruleId("X12-006").ruleName("NM1 Name Completeness")
                    .category("DATA_QUALITY").severity("WARNING").passed(issues.isEmpty())
                    .message(issues.isEmpty() ? "All NM1 names complete" : String.join("; ", issues)).build();
            }));

        // === Transaction-Type-Specific Rules ===

        // 837-001: CLM amount must be positive
        addTxnRule("X12", "837", new BusinessRule("837-001", "Claim Amount Positive", "FINANCIAL", "ERROR",
            "CLM02 monetary amount must be > 0",
            doc -> {
                for (var seg : doc.getSegments()) {
                    if ("CLM".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1) {
                        try {
                            double amt = Double.parseDouble(seg.getElements().get(1).trim());
                            if (amt <= 0) return RuleResult.builder().ruleId("837-001").ruleName("Claim Amount Positive")
                                .category("FINANCIAL").severity("ERROR").passed(false)
                                .message("CLM amount is " + amt + " — must be positive")
                                .affectedSegment("CLM").build();
                        } catch (NumberFormatException e) {
                            return RuleResult.builder().ruleId("837-001").ruleName("Claim Amount Positive")
                                .category("FINANCIAL").severity("ERROR").passed(false)
                                .message("CLM amount is not numeric").affectedSegment("CLM").build();
                        }
                    }
                }
                return RuleResult.builder().ruleId("837-001").ruleName("Claim Amount Positive")
                    .category("FINANCIAL").severity("ERROR").passed(true).message("All claim amounts valid").build();
            }));

        // 837-002: NM109 with qualifier XX must be 10-digit NPI
        addTxnRule("X12", "837", new BusinessRule("837-002", "NPI Format Validation", "HEALTHCARE", "ERROR",
            "NM109 with qualifier XX must be a valid 10-digit NPI",
            doc -> {
                List<String> issues = new ArrayList<>();
                for (var seg : doc.getSegments()) {
                    if (!"NM1".equals(seg.getId()) || seg.getElements() == null || seg.getElements().size() < 9) continue;
                    String qual = seg.getElements().get(7).trim();
                    String code = seg.getElements().get(8).trim();
                    if ("XX".equals(qual)) {
                        if (!code.matches("\\d{10}"))
                            issues.add("NPI '" + code + "' is not 10 digits");
                    }
                }
                return RuleResult.builder().ruleId("837-002").ruleName("NPI Format Validation")
                    .category("HEALTHCARE").severity("ERROR").passed(issues.isEmpty())
                    .message(issues.isEmpty() ? "All NPIs valid" : String.join("; ", issues)).build();
            }));

        // 837-003: Must have at least one SV1 or SV2 (service line)
        addTxnRule("X12", "837", new BusinessRule("837-003", "Service Line Required", "HEALTHCARE", "ERROR",
            "837 claims must have at least one service line (SV1 or SV2)",
            doc -> {
                boolean hasSV = doc.getSegments().stream().anyMatch(s ->
                    "SV1".equals(s.getId()) || "SV2".equals(s.getId()));
                return RuleResult.builder().ruleId("837-003").ruleName("Service Line Required")
                    .category("HEALTHCARE").severity("ERROR").passed(hasSV)
                    .message(hasSV ? "Service lines present" : "No SV1/SV2 segments found")
                    .recommendation(hasSV ? null : "Add at least one SV1 (professional) or SV2 (institutional) segment").build();
            }));

        // 850-001: PO1 line items must have positive quantities
        addTxnRule("X12", "850", new BusinessRule("850-001", "Line Item Quantity Positive", "LOGISTICS", "ERROR",
            "PO1 line item quantities must be > 0",
            doc -> {
                List<String> issues = new ArrayList<>();
                int lineNum = 0;
                for (var seg : doc.getSegments()) {
                    if (!"PO1".equals(seg.getId()) || seg.getElements() == null) continue;
                    lineNum++;
                    if (seg.getElements().size() > 1) {
                        try {
                            double qty = Double.parseDouble(seg.getElements().get(1).trim());
                            if (qty <= 0) issues.add("Line " + lineNum + " qty=" + qty);
                        } catch (NumberFormatException e) {
                            issues.add("Line " + lineNum + " qty not numeric");
                        }
                    }
                }
                return RuleResult.builder().ruleId("850-001").ruleName("Line Item Quantity Positive")
                    .category("LOGISTICS").severity("ERROR").passed(issues.isEmpty())
                    .message(issues.isEmpty() ? "All quantities valid" : "Invalid: " + String.join(", ", issues)).build();
            }));

        // 850-002: Must have BEG segment
        addTxnRule("X12", "850", new BusinessRule("850-002", "BEG Segment Required", "COMPLIANCE", "ERROR",
            "850 Purchase Orders must have a BEG segment",
            doc -> {
                boolean has = doc.getSegments().stream().anyMatch(s -> "BEG".equals(s.getId()));
                return RuleResult.builder().ruleId("850-002").ruleName("BEG Segment Required")
                    .category("COMPLIANCE").severity("ERROR").passed(has)
                    .message(has ? "BEG present" : "Missing BEG segment").build();
            }));

        // 850-003: CTT line count should match actual PO1 count
        addTxnRule("X12", "850", new BusinessRule("850-003", "CTT Count Matches PO1 Count", "DATA_QUALITY", "WARNING",
            "CTT01 should equal the number of PO1 segments",
            doc -> {
                long po1Count = doc.getSegments().stream().filter(s -> "PO1".equals(s.getId())).count();
                for (var seg : doc.getSegments()) {
                    if ("CTT".equals(seg.getId()) && seg.getElements() != null && !seg.getElements().isEmpty()) {
                        try {
                            int claimed = Integer.parseInt(seg.getElements().get(0).trim());
                            if (claimed != po1Count) {
                                return RuleResult.builder().ruleId("850-003").ruleName("CTT Count Matches PO1 Count")
                                    .category("DATA_QUALITY").severity("WARNING").passed(false)
                                    .message("CTT01=" + claimed + " but found " + po1Count + " PO1 segments").build();
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                return RuleResult.builder().ruleId("850-003").ruleName("CTT Count Matches PO1 Count")
                    .category("DATA_QUALITY").severity("WARNING").passed(true).message("Line counts match").build();
            }));

        // 835-001: CLP payment amounts should be <= charge amounts
        addTxnRule("X12", "835", new BusinessRule("835-001", "Payment <= Charge", "FINANCIAL", "WARNING",
            "CLP04 (payment) should not exceed CLP03 (charge)",
            doc -> {
                List<String> issues = new ArrayList<>();
                for (var seg : doc.getSegments()) {
                    if (!"CLP".equals(seg.getId()) || seg.getElements() == null || seg.getElements().size() < 4) continue;
                    try {
                        double charge = Double.parseDouble(seg.getElements().get(2).trim());
                        double payment = Double.parseDouble(seg.getElements().get(3).trim());
                        if (Math.abs(payment) > Math.abs(charge))
                            issues.add("Claim " + seg.getElements().get(0) + ": payment $" + payment + " > charge $" + charge);
                    } catch (NumberFormatException ignored) {}
                }
                return RuleResult.builder().ruleId("835-001").ruleName("Payment <= Charge")
                    .category("FINANCIAL").severity("WARNING").passed(issues.isEmpty())
                    .message(issues.isEmpty() ? "All payments valid" : String.join("; ", issues)).build();
            }));

        // 810-001: TDS total should match sum of IT1 amounts
        addTxnRule("X12", "810", new BusinessRule("810-001", "Invoice Total Reconciliation", "FINANCIAL", "WARNING",
            "TDS total should match the sum of IT1 line amounts",
            doc -> {
                double lineTotal = 0;
                for (var seg : doc.getSegments()) {
                    if ("IT1".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 3) {
                        try {
                            double qty = Double.parseDouble(seg.getElements().get(1).trim());
                            double price = Double.parseDouble(seg.getElements().get(3).trim());
                            lineTotal += qty * price;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                for (var seg : doc.getSegments()) {
                    if ("TDS".equals(seg.getId()) && seg.getElements() != null && !seg.getElements().isEmpty()) {
                        try {
                            double tds = Double.parseDouble(seg.getElements().get(0).trim()) / 100.0; // TDS in cents
                            if (Math.abs(tds - lineTotal) > 0.01) {
                                return RuleResult.builder().ruleId("810-001").ruleName("Invoice Total Reconciliation")
                                    .category("FINANCIAL").severity("WARNING").passed(false)
                                    .message("TDS=" + tds + " but line total=" + lineTotal).build();
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                return RuleResult.builder().ruleId("810-001").ruleName("Invoice Total Reconciliation")
                    .category("FINANCIAL").severity("WARNING").passed(true).message("Totals reconciled").build();
            }));
    }

    private void registerEdifactRules() {
        // EDIFACT-001: UNB/UNZ interchange reference must match
        addFormatRule("EDIFACT", new BusinessRule("EDIFACT-001", "UNB/UNZ Reference Match", "COMPLIANCE", "ERROR",
            "UNB interchange reference must match UNZ",
            doc -> {
                String unbRef = null, unzRef = null;
                for (var seg : doc.getSegments()) {
                    if ("UNB".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 4)
                        unbRef = seg.getElements().get(4).trim();
                    if ("UNZ".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        unzRef = seg.getElements().get(1).trim();
                }
                boolean match = unbRef != null && unbRef.equals(unzRef);
                return RuleResult.builder().ruleId("EDIFACT-001").ruleName("UNB/UNZ Reference Match")
                    .category("COMPLIANCE").severity("ERROR").passed(match || unbRef == null)
                    .message(match ? "References match" : "UNB ref=" + unbRef + " UNZ ref=" + unzRef).build();
            }));

        // EDIFACT-002: UNH/UNT message reference must match
        addFormatRule("EDIFACT", new BusinessRule("EDIFACT-002", "UNH/UNT Reference Match", "COMPLIANCE", "ERROR",
            "UNH message reference must match UNT",
            doc -> {
                String unhRef = null, untRef = null;
                for (var seg : doc.getSegments()) {
                    if ("UNH".equals(seg.getId()) && seg.getElements() != null && !seg.getElements().isEmpty())
                        unhRef = seg.getElements().get(0).trim();
                    if ("UNT".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 1)
                        untRef = seg.getElements().get(1).trim();
                }
                boolean match = unhRef != null && unhRef.equals(untRef);
                return RuleResult.builder().ruleId("EDIFACT-002").ruleName("UNH/UNT Reference Match")
                    .category("COMPLIANCE").severity("ERROR").passed(match || unhRef == null)
                    .message(match ? "References match" : "UNH ref=" + unhRef + " UNT ref=" + untRef).build();
            }));

        // EDIFACT-003: MOA amounts must be numeric
        addFormatRule("EDIFACT", new BusinessRule("EDIFACT-003", "MOA Amount Numeric", "DATA_QUALITY", "ERROR",
            "MOA monetary amounts must be valid numbers",
            doc -> {
                for (var seg : doc.getSegments()) {
                    if ("MOA".equals(seg.getId()) && seg.getElements() != null && !seg.getElements().isEmpty()) {
                        String val = seg.getElements().get(0);
                        // MOA format: qualifier:amount:currency — extract amount from composite
                        String[] parts = val.split(":");
                        if (parts.length > 1) {
                            try { Double.parseDouble(parts[1]); }
                            catch (NumberFormatException e) {
                                return RuleResult.builder().ruleId("EDIFACT-003").ruleName("MOA Amount Numeric")
                                    .category("DATA_QUALITY").severity("ERROR").passed(false)
                                    .message("Non-numeric amount: " + parts[1]).build();
                            }
                        }
                    }
                }
                return RuleResult.builder().ruleId("EDIFACT-003").ruleName("MOA Amount Numeric")
                    .category("DATA_QUALITY").severity("ERROR").passed(true).message("All amounts numeric").build();
            }));
    }

    private void registerHl7Rules() {
        // HL7-001: MSH must be first segment
        addFormatRule("HL7", new BusinessRule("HL7-001", "MSH First Segment", "COMPLIANCE", "ERROR",
            "MSH must be the first segment in HL7 messages",
            doc -> {
                boolean first = !doc.getSegments().isEmpty() && "MSH".equals(doc.getSegments().get(0).getId());
                return RuleResult.builder().ruleId("HL7-001").ruleName("MSH First Segment")
                    .category("COMPLIANCE").severity("ERROR").passed(first)
                    .message(first ? "MSH is first" : "MSH is not the first segment").build();
            }));

        // HL7-002: PID required for ADT messages
        addFormatRule("HL7", new BusinessRule("HL7-002", "PID Required for ADT", "HEALTHCARE", "ERROR",
            "ADT messages must contain a PID segment",
            doc -> {
                if (doc.getDocumentType() == null || !doc.getDocumentType().contains("ADT"))
                    return RuleResult.builder().ruleId("HL7-002").ruleName("PID Required for ADT")
                        .category("HEALTHCARE").severity("ERROR").passed(true).message("Not an ADT message").build();
                boolean hasPid = doc.getSegments().stream().anyMatch(s -> "PID".equals(s.getId()));
                return RuleResult.builder().ruleId("HL7-002").ruleName("PID Required for ADT")
                    .category("HEALTHCARE").severity("ERROR").passed(hasPid)
                    .message(hasPid ? "PID present" : "Missing PID in ADT message").build();
            }));
    }

    // Helper methods
    private void addFormatRule(String format, BusinessRule rule) {
        rulesByFormat.computeIfAbsent(format, k -> new ArrayList<>()).add(rule);
    }

    private void addTxnRule(String format, String txnType, BusinessRule rule) {
        rulesByTxnType.computeIfAbsent(format + ":" + txnType, k -> new ArrayList<>()).add(rule);
    }

    private static boolean isValidDate6(String date) {
        if (date == null || date.length() != 6) return false;
        try {
            int y = Integer.parseInt(date.substring(0, 2));
            int m = Integer.parseInt(date.substring(2, 4));
            int d = Integer.parseInt(date.substring(4, 6));
            return m >= 1 && m <= 12 && d >= 1 && d <= 31;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean isValidDate8(String date) {
        if (date == null || date.length() != 8) return false;
        try {
            int y = Integer.parseInt(date.substring(0, 4));
            int m = Integer.parseInt(date.substring(4, 6));
            int d = Integer.parseInt(date.substring(6, 8));
            return y >= 1900 && y <= 2099 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean isValidDateRange(String date) {
        // RD8 format: CCYYMMDD-CCYYMMDD
        if (date != null && date.contains("-") && date.length() == 17) {
            return isValidDate8(date.substring(0, 8)) && isValidDate8(date.substring(9));
        }
        return false;
    }

    // Public API for getting registered rules
    public List<String> getRegisteredRuleIds(String format) {
        List<String> ids = new ArrayList<>();
        rulesByFormat.getOrDefault(format, List.of()).forEach(r -> ids.add(r.getRuleId()));
        return ids;
    }

    public int getTotalRuleCount() {
        int count = 0;
        for (var rules : rulesByFormat.values()) count += rules.size();
        for (var rules : rulesByTxnType.values()) count += rules.size();
        return count;
    }
}
