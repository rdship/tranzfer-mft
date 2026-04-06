package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.X12VersionRegistry;
import com.filetransfer.edi.service.BusinessRuleEngine;
import lombok.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Smart EDI validator — doesn't just say "invalid",
 * tells you EXACTLY what's wrong and HOW to fix it.
 */
@Service
@RequiredArgsConstructor
public class SmartValidator {

    private final BusinessRuleEngine businessRuleEngine;
    private final X12VersionRegistry versionRegistry;

    public ValidationReport validate(EdiDocument doc) {
        List<Issue> issues = new ArrayList<>();

        if (doc.getSegments() == null || doc.getSegments().isEmpty()) {
            issues.add(Issue.builder().severity("ERROR").segment("(none)").lineNumber(0)
                    .problem("File contains no parseable segments")
                    .fix("Check that the file is in a supported EDI format (X12, EDIFACT, HL7, etc.)")
                    .example("X12 files should start with ISA*00*...")
                    .build());
            return report(doc, issues, List.of());
        }

        if ("X12".equals(doc.getSourceFormat())) validateX12(doc, issues);
        else if ("EDIFACT".equals(doc.getSourceFormat())) validateEdifact(doc, issues);
        else if ("HL7".equals(doc.getSourceFormat())) validateHl7(doc, issues);
        else genericValidation(doc, issues);

        // Run business rules for all formats
        List<BusinessRuleEngine.RuleResult> bizResults = businessRuleEngine.evaluate(doc);
        for (var r : bizResults) {
            if (!r.isPassed()) {
                issues.add(Issue.builder()
                    .severity(r.getSeverity())
                    .segment(r.getAffectedSegment())
                    .problem("[" + r.getRuleId() + "] " + r.getMessage())
                    .fix(r.getRecommendation())
                    .build());
            }
        }

        return report(doc, issues, bizResults);
    }

    private void validateX12(EdiDocument doc, List<Issue> issues) {
        List<String> segIds = doc.getSegments().stream().map(EdiDocument.Segment::getId).collect(Collectors.toList());
        int lineNum = 0;

        // Structure checks
        if (!segIds.contains("ISA")) {
            issues.add(Issue.builder().severity("ERROR").segment("ISA").lineNumber(0)
                    .problem("Missing ISA (Interchange Control Header) — this MUST be the first segment")
                    .fix("Add ISA segment at the beginning of the file")
                    .example("ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1253*^*00501*000000001*0*P*:~")
                    .build());
        }
        if (!segIds.contains("GS")) {
            issues.add(Issue.builder().severity("ERROR").segment("GS").lineNumber(0)
                    .problem("Missing GS (Functional Group Header)")
                    .fix("Add GS segment after ISA").build());
        }
        if (!segIds.contains("ST")) {
            issues.add(Issue.builder().severity("ERROR").segment("ST").lineNumber(0)
                    .problem("Missing ST (Transaction Set Header) — no transaction defined")
                    .fix("Add ST segment with the transaction type (e.g. ST*837*0001)").build());
        }
        if (!segIds.contains("SE")) {
            issues.add(Issue.builder().severity("WARNING").segment("SE").lineNumber(segIds.size())
                    .problem("Missing SE (Transaction Set Trailer) — transaction not properly closed")
                    .fix("Add SE segment at the end: SE*<segment_count>*<ST_control_number>")
                    .example("SE*" + segIds.size() + "*0001~").build());
        }
        if (!segIds.contains("GE")) {
            issues.add(Issue.builder().severity("WARNING").segment("GE")
                    .problem("Missing GE (Group Trailer)").fix("Add GE*1*<GS_control_number>~").build());
        }
        if (!segIds.contains("IEA")) {
            issues.add(Issue.builder().severity("WARNING").segment("IEA")
                    .problem("Missing IEA (Interchange Trailer)").fix("Add IEA*1*<ISA_control_number>~").build());
        }

        // Order checks
        if (segIds.indexOf("ISA") > 0) {
            issues.add(Issue.builder().severity("ERROR").segment("ISA")
                    .problem("ISA is not the first segment — it MUST be first")
                    .fix("Move ISA to the very beginning of the file").build());
        }

        // Segment count in SE must match actual count
        for (int i = 0; i < doc.getSegments().size(); i++) {
            EdiDocument.Segment seg = doc.getSegments().get(i);
            if ("SE".equals(seg.getId()) && seg.getElements() != null && !seg.getElements().isEmpty()) {
                try {
                    int declaredCount = Integer.parseInt(seg.getElements().get(0).trim());
                    // Count segments between ST and SE
                    int stIdx = segIds.indexOf("ST");
                    int seIdx = i;
                    int actualCount = seIdx - stIdx + 1;
                    if (declaredCount != actualCount) {
                        issues.add(Issue.builder().severity("ERROR").segment("SE").lineNumber(i + 1)
                                .problem("SE segment count is " + declaredCount + " but actual is " + actualCount)
                                .fix("Change SE*" + declaredCount + " to SE*" + actualCount)
                                .example("SE*" + actualCount + "*0001~").build());
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // Empty required elements
        for (int i = 0; i < doc.getSegments().size(); i++) {
            EdiDocument.Segment seg = doc.getSegments().get(i);
            if ("ISA".equals(seg.getId()) && seg.getElements() != null) {
                if (seg.getElements().size() < 15) {
                    issues.add(Issue.builder().severity("ERROR").segment("ISA").lineNumber(i + 1)
                            .problem("ISA has " + seg.getElements().size() + " elements but needs exactly 15")
                            .fix("ISA must have all 16 fields (segment ID + 15 elements)").build());
                }
            }
        }

        // Version-aware validation
        String txnType = doc.getDocumentType();
        Set<String> present = new HashSet<>(segIds);
        // The basic required segments already checked above
        Set<String> alreadyChecked = Set.of("ISA", "GS", "ST", "SE", "GE", "IEA");
        if (txnType != null) {
            X12VersionRegistry.VersionDef vDef = versionRegistry.getVersionDef(doc.getVersion(), txnType);
            if (vDef != null) {
                for (String req : vDef.getRequiredSegments()) {
                    if (!present.contains(req) && !alreadyChecked.contains(req)) {
                        issues.add(Issue.builder().severity("WARNING").segment(req)
                                .problem("Version " + vDef.getVersion() + " requires segment " + req + " for " + txnType)
                                .fix("Add " + req + " segment as required by implementation guide " + vDef.getImplementationGuide())
                                .build());
                    }
                }
            }
        }

        if (issues.isEmpty()) {
            issues.add(Issue.builder().severity("OK").segment("(all)")
                    .problem("No issues found — this is a valid X12 " + doc.getDocumentType() + " document!")
                    .fix("No action needed").build());
        }
    }

    private void validateEdifact(EdiDocument doc, List<Issue> issues) {
        List<String> segIds = doc.getSegments().stream().map(EdiDocument.Segment::getId).collect(Collectors.toList());
        if (!segIds.contains("UNB")) issues.add(Issue.builder().severity("ERROR").segment("UNB").problem("Missing UNB (Interchange Header)").fix("Add UNB segment").build());
        if (!segIds.contains("UNH")) issues.add(Issue.builder().severity("ERROR").segment("UNH").problem("Missing UNH (Message Header)").fix("Add UNH segment").build());
        if (!segIds.contains("UNT")) issues.add(Issue.builder().severity("WARNING").segment("UNT").problem("Missing UNT (Message Trailer)").fix("Add UNT segment").build());
        if (!segIds.contains("UNZ")) issues.add(Issue.builder().severity("WARNING").segment("UNZ").problem("Missing UNZ (Interchange Trailer)").fix("Add UNZ segment").build());
        if (issues.isEmpty()) issues.add(Issue.builder().severity("OK").problem("Valid EDIFACT document").build());
    }

    private void validateHl7(EdiDocument doc, List<Issue> issues) {
        List<String> segIds = doc.getSegments().stream().map(EdiDocument.Segment::getId).collect(Collectors.toList());
        if (!segIds.contains("MSH")) issues.add(Issue.builder().severity("ERROR").segment("MSH").problem("Missing MSH (Message Header) — MUST be first").fix("Add MSH segment").build());
        else if (segIds.indexOf("MSH") > 0) issues.add(Issue.builder().severity("ERROR").segment("MSH").problem("MSH is not first").fix("Move MSH to line 1").build());
        if (issues.isEmpty()) issues.add(Issue.builder().severity("OK").problem("Valid HL7 message").build());
    }

    private void genericValidation(EdiDocument doc, List<Issue> issues) {
        if (doc.getSegments().size() < 3) issues.add(Issue.builder().severity("WARNING").problem("Very few segments (" + doc.getSegments().size() + ") — file may be incomplete").build());
        else issues.add(Issue.builder().severity("OK").problem("Basic structure looks valid (" + doc.getSegments().size() + " segments)").build());
    }

    private ValidationReport report(EdiDocument doc, List<Issue> issues, List<BusinessRuleEngine.RuleResult> bizResults) {
        long errors = issues.stream().filter(i -> "ERROR".equals(i.severity)).count();
        long warnings = issues.stream().filter(i -> "WARNING".equals(i.severity)).count();
        return ValidationReport.builder()
                .valid(errors == 0).format(doc.getSourceFormat()).documentType(doc.getDocumentType())
                .errors((int) errors).warnings((int) warnings).totalSegments(doc.getSegments() != null ? doc.getSegments().size() : 0)
                .issues(issues)
                .businessRuleResults(bizResults)
                .verdict(errors == 0 ? (warnings == 0 ? "✅ Perfect — no issues found" : "⚠️ Valid but has " + warnings + " warning(s)") : "❌ Invalid — " + errors + " error(s) must be fixed")
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ValidationReport {
        private boolean valid;
        private String format;
        private String documentType;
        private int errors;
        private int warnings;
        private int totalSegments;
        private List<Issue> issues;
        private List<BusinessRuleEngine.RuleResult> businessRuleResults;
        private String verdict;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Issue {
        private String severity; // ERROR, WARNING, OK, INFO
        private String segment;
        private Integer lineNumber;
        private String problem;
        private String fix;
        private String example;
    }
}
