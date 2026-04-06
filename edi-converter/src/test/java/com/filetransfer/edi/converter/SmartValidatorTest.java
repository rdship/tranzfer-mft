package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.X12VersionRegistry;
import com.filetransfer.edi.service.BusinessRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmartValidator covering X12, EDIFACT, HL7 structure validation,
 * missing segments, segment count mismatches, empty document handling,
 * business rule integration, and version-aware validation.
 */
class SmartValidatorTest {

    private SmartValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SmartValidator(new BusinessRuleEngine(), new X12VersionRegistry());
    }

    /** Build a segment with the given ID and elements */
    private EdiDocument.Segment seg(String id, String... elements) {
        return EdiDocument.Segment.builder().id(id).elements(List.of(elements)).build();
    }

    private EdiDocument doc(String format, String docType, EdiDocument.Segment... segments) {
        return EdiDocument.builder()
                .sourceFormat(format)
                .documentType(docType)
                .segments(List.of(segments))
                .build();
    }

    // === Valid X12 document ===
    // ISA(0), GS(1), ST(2), BEG(3), PO1(4), SE(5), GE(6), IEA(7)
    // ST at 2, SE at 5 → count = 5-2+1 = 4

    @Test
    void validateValidX12_noErrors() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "4","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.isValid(), "Expected valid but got errors: " + report.getIssues());
        assertEquals(0, report.getErrors());
    }

    @Test
    void validateValidX12_reportContainsOKIssue() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "4","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        boolean hasOk = report.getIssues().stream().anyMatch(i -> "OK".equals(i.getSeverity()));
        assertTrue(hasOk);
    }

    // === X12 missing ISA ===

    @Test
    void validateX12_missingISA_hasError() {
        EdiDocument d = doc("X12", "850",
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("SE", "3","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getErrors() > 0);
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "ISA".equals(i.getSegment())));
    }

    // === X12 missing SE/GE/IEA trailers ===

    @Test
    void validateX12_missingSE_hasWarning() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.getWarnings() > 0);
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "WARNING".equals(i.getSeverity()) && "SE".equals(i.getSegment())));
    }

    @Test
    void validateX12_missingGE_hasWarning() {
        // ST at 2, SE at 4 → count = 4-2+1 = 3
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("SE", "3","0001"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "WARNING".equals(i.getSeverity()) && "GE".equals(i.getSegment())));
    }

    @Test
    void validateX12_missingIEA_hasWarning() {
        // ST at 2, SE at 4 → count = 4-2+1 = 3
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("SE", "3","0001"),
                seg("GE", "1","1"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "WARNING".equals(i.getSeverity()) && "IEA".equals(i.getSegment())));
    }

    @Test
    void validateX12_missingAllTrailers_multipleWarnings() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.getWarnings() >= 3);
    }

    // === X12 wrong segment count in SE ===

    @Test
    void validateX12_wrongSegmentCountInSE_hasError() {
        // ST at 2, SE at 5 → actual = 4, but SE says 99
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "99","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "SE".equals(i.getSegment())
                        && i.getProblem().contains("segment count")));
    }

    @Test
    void validateX12_correctSegmentCountInSE_noSegmentCountError() {
        // ST at 2, SE at 5 → actual = 5-2+1 = 4
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "4","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "SE".equals(i.getSegment())
                        && i.getProblem().contains("segment count")));
    }

    // === EDIFACT validation ===

    @Test
    void validateEdifact_missingUNB_hasError() {
        EdiDocument d = doc("EDIFACT", "ORDERS",
                seg("UNH", "1","ORDERS:D:96A:UN"),
                seg("BGM", "220","PO123","9"),
                seg("UNT", "3","1"),
                seg("UNZ", "1","1"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "UNB".equals(i.getSegment())));
    }

    @Test
    void validateEdifact_validDocument_noErrors() {
        EdiDocument d = doc("EDIFACT", "ORDERS",
                seg("UNB", "UNOC:3","SENDER:14","RECEIVER:14","240101:1200","1"),
                seg("UNH", "1","ORDERS:D:96A:UN"),
                seg("BGM", "220","PO123","9"),
                seg("UNT", "3","1"),
                seg("UNZ", "1","1"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.isValid(), "Expected valid but got: " + report.getIssues());
        assertEquals(0, report.getErrors());
    }

    @Test
    void validateEdifact_missingUNH_hasError() {
        EdiDocument d = doc("EDIFACT", "ORDERS",
                seg("UNB", "UNOC:3","SENDER:14","RECEIVER:14","240101:1200","1"),
                seg("BGM", "220","PO123","9"),
                seg("UNT", "3","1"),
                seg("UNZ", "1","1"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "UNH".equals(i.getSegment())));
    }

    // === HL7 validation ===

    @Test
    void validateHl7_missingMSH_hasError() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("EVN", "A01","20240101120000"),
                seg("PID", "1","","PAT12345"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && "MSH".equals(i.getSegment())));
    }

    @Test
    void validateHl7_validWithMSH_noErrors() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("MSH", "^~\\&","SENDING_APP","SENDING_FAC"),
                seg("EVN", "A01","20240101120000"),
                seg("PID", "1","","PAT12345"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.isValid(), "Expected valid but got: " + report.getIssues());
        assertEquals(0, report.getErrors());
    }

    @Test
    void validateHl7_mshNotFirst_hasError() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("EVN", "A01","20240101120000"),
                seg("MSH", "^~\\&","SENDING_APP","SENDING_FAC"),
                seg("PID", "1","","PAT12345"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "ERROR".equals(i.getSeverity()) && i.getProblem().contains("not first")));
    }

    // === Empty and null document ===

    @Test
    void validateEmptyDocument_returnsFailedValidation() {
        EdiDocument d = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of()).build();

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getErrors() > 0);
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> i.getProblem().contains("no parseable segments")));
    }

    @Test
    void validateNullSegments_returnsFailedValidation() {
        // SmartValidator handles null segments at line 20
        EdiDocument d = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(null).build();

        SmartValidator.ValidationReport report = validator.validate(d);
        assertFalse(report.isValid());
        assertTrue(report.getErrors() > 0);
    }

    // === Report structure ===

    @Test
    void validationReport_containsFormatAndType() {
        // ST at 2, SE at 3 → count = 3-2+1 = 2
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("SE", "2","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertEquals("X12", report.getFormat());
        assertEquals("850", report.getDocumentType());
    }

    @Test
    void validationReport_totalSegmentsReflectsActual() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("SE", "2","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertEquals(6, report.getTotalSegments());
    }

    @Test
    void validationReport_issueHasFixSuggestion() {
        EdiDocument d = doc("X12", "850",
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        assertTrue(report.getIssues().stream()
                .filter(i -> "ERROR".equals(i.getSeverity()))
                .anyMatch(i -> i.getFix() != null && !i.getFix().isEmpty()));
    }

    // === Business rule integration tests ===

    @Test
    void validateX12_businessRules_controlNumberMismatch_flagged() {
        // ISA control number = 000000001, IEA control number = 000000099 (mismatch)
        // ST at 2, SE at 5 -> count = 4
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "4","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000099"));

        SmartValidator.ValidationReport report = validator.validate(d);
        // Should flag the ISA/IEA mismatch via business rule X12-001
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> i.getProblem() != null && i.getProblem().contains("X12-001")),
                "Expected X12-001 control number mismatch issue but got: " + report.getIssues());
    }

    @Test
    void validateX12_businessRules_allControlNumbersMatch_passes() {
        // All control numbers match: ISA13=IEA02, GS06=GE02, ST02=SE02
        // ST at 2, SE at 5 -> count = 4
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("ST", "850","0001"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("PO1", "1","10","EA","25.00","","UP","123456789"),
                seg("SE", "4","0001"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));

        SmartValidator.ValidationReport report = validator.validate(d);
        // X12-001 should NOT appear as a failed issue
        assertFalse(report.getIssues().stream()
                .anyMatch(i -> i.getProblem() != null && i.getProblem().contains("X12-001")),
                "X12-001 should not flag when control numbers match");
        // businessRuleResults should be populated
        assertNotNull(report.getBusinessRuleResults());
        assertFalse(report.getBusinessRuleResults().isEmpty());
    }

    @Test
    void validateX12_versionAware_missingRequiredFor837_flagged() {
        // 837 with version 005010 requires BHT, HL, NM1, CLM, SV1 — we omit them
        // ST at 2, SE at 3 -> count = 2
        EdiDocument d = EdiDocument.builder()
                .sourceFormat("X12")
                .documentType("837")
                .version("005010")
                .segments(List.of(
                    seg("ISA", "00","          ","00","          ","ZZ","SENDER         ","ZZ","RECEIVER       ","240101","1200","U","00501","000000001","0","P"),
                    seg("GS", "HP","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                    seg("ST", "837","0001"),
                    seg("SE", "2","0001"),
                    seg("GE", "1","1"),
                    seg("IEA", "1","000000001")
                ))
                .build();

        SmartValidator.ValidationReport report = validator.validate(d);
        // Should have warnings about missing version-required segments (BHT, HL, NM1, CLM, SV1)
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> "WARNING".equals(i.getSeverity()) && i.getProblem() != null
                        && i.getProblem().contains("requires segment")),
                "Expected version-aware missing segment warnings but got: " + report.getIssues());
        // Check specific segments
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> i.getProblem() != null && i.getProblem().contains("BHT")),
                "Expected BHT missing warning");
        assertTrue(report.getIssues().stream()
                .anyMatch(i -> i.getProblem() != null && i.getProblem().contains("CLM")),
                "Expected CLM missing warning");
    }
}
