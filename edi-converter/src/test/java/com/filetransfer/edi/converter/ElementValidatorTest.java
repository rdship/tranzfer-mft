package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.EdifactSegmentDefinitions;
import com.filetransfer.edi.parser.X12SegmentDefinitions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ElementValidator — validates segment elements against
 * X12SegmentDefinitions and EdifactSegmentDefinitions registries.
 *
 * Uses real instances (no mocking) per JDK 25 constraints.
 */
class ElementValidatorTest {

    private X12SegmentDefinitions x12Defs;
    private EdifactSegmentDefinitions edifactDefs;
    private ElementValidator validator;

    @BeforeEach
    void setUp() {
        x12Defs = new X12SegmentDefinitions();
        edifactDefs = new EdifactSegmentDefinitions();
        validator = new ElementValidator(x12Defs, edifactDefs);
    }

    // --------------------------------------------------
    //  Helpers
    // --------------------------------------------------

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

    private long countErrors(List<ElementValidator.ElementIssue> issues) {
        return issues.stream().filter(i -> "ERROR".equals(i.getSeverity())).count();
    }

    // --------------------------------------------------
    //  1. Valid ISA passes all checks
    // --------------------------------------------------
    @Test
    void validISA_passesAllChecks() {
        EdiDocument.Segment isa = seg("ISA",
                "00", "          ", "00", "          ",
                "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                "240101", "1253", "^", "00501",
                "000000001", "0", "P", ":");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        assertEquals(0, issues.size(), "Valid ISA should have no issues, but got: " + issues);
    }

    // --------------------------------------------------
    //  2. ISA with wrong element count flags error (missing required elements)
    // --------------------------------------------------
    @Test
    void isaWithTooFewElements_flagsMissingRequiredElements() {
        // Only 5 elements instead of 16 — all remaining required elements should error
        EdiDocument.Segment isa = seg("ISA", "00", "          ", "00", "          ", "ZZ");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        long errors = countErrors(issues);
        assertTrue(errors > 0, "Missing elements should produce errors");
        // ISA has 16 elements, all required. Providing 5, missing 11.
        assertTrue(errors >= 11, "Should flag at least 11 missing required elements, got " + errors);
    }

    // --------------------------------------------------
    //  3. ISA with invalid qualifier flags error
    // --------------------------------------------------
    @Test
    void isaWithInvalidAuthorizationQualifier_flagsError() {
        EdiDocument.Segment isa = seg("ISA",
                "99", "          ", "00", "          ",  // "99" not in valid set
                "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                "240101", "1253", "^", "00501",
                "000000001", "0", "P", ":");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Should flag ISA01 invalid qualifier '99'");
    }

    // --------------------------------------------------
    //  4. ISA with wrong date length flags error
    // --------------------------------------------------
    @Test
    void isaWithWrongDateLength_flagsError() {
        // ISA09 should be 6 chars; provide 4
        EdiDocument.Segment isa = seg("ISA",
                "00", "          ", "00", "          ",
                "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                "2401", "1253", "^", "00501",
                "000000001", "0", "P", ":");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 9 && i.getProblem().contains("too short")),
                "Should flag ISA09 date too short");
    }

    // --------------------------------------------------
    //  5. ISA with non-numeric control number flags error
    // --------------------------------------------------
    @Test
    void isaWithNonNumericControlNumber_flagsError() {
        EdiDocument.Segment isa = seg("ISA",
                "00", "          ", "00", "          ",
                "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                "240101", "1253", "^", "00501",
                "ABCDEFGHI", "0", "P", ":");  // ISA13 should be numeric

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 13 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("whole number")),
                "Should flag ISA13 non-numeric control number");
    }

    // --------------------------------------------------
    //  6. NM1 with invalid entity type qualifier flags error
    // --------------------------------------------------
    @Test
    void nm1WithInvalidEntityTypeQualifier_flagsError() {
        // NM102 valid values: 1,2,3,4,5,6,7,8,D,X — "Z" is invalid
        EdiDocument.Segment nm1 = seg("NM1", "85", "Z", "SMITH", "JOHN", "", "", "", "XX", "1234567890");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(nm1, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 2 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Should flag NM102 invalid entity type qualifier 'Z'");
    }

    // --------------------------------------------------
    //  7. NM1 with valid entity code passes
    // --------------------------------------------------
    @Test
    void nm1WithValidEntityCode_passes() {
        EdiDocument.Segment nm1 = seg("NM1", "85", "1", "SMITH", "JOHN", "", "", "", "XX", "1234567890");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(nm1, "X12", 0);
        assertEquals(0, countErrors(issues),
                "Valid NM1 should have no errors, but got: " + issues);
    }

    // --------------------------------------------------
    //  8. PO1 with invalid unit of measure flags error
    // --------------------------------------------------
    @Test
    void po1WithInvalidUnitOfMeasure_flagsError() {
        // PO103 valid: BX,CA,CS,CY,DZ,EA,... — "XX" is invalid
        EdiDocument.Segment po1 = seg("PO1", "1", "10", "XX", "25.00", "", "UP", "123456");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(po1, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 3 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Should flag PO103 invalid UOM 'XX'");
    }

    // --------------------------------------------------
    //  9. BEG with valid data passes
    // --------------------------------------------------
    @Test
    void begWithValidData_passes() {
        EdiDocument.Segment beg = seg("BEG", "00", "NE", "PO-12345", "", "20240101");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(beg, "X12", 0);
        assertEquals(0, countErrors(issues),
                "Valid BEG should have no errors, but got: " + issues);
    }

    // --------------------------------------------------
    //  10. Missing required element detected
    // --------------------------------------------------
    @Test
    void missingRequiredElement_detected() {
        // SE has 2 required elements: segment count + control number
        // Provide only 1 element — second required element absent
        EdiDocument.Segment se = seg("SE", "5");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(se, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 2 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("missing")),
                "Should flag SE02 missing required element");
    }

    // --------------------------------------------------
    //  11. Empty required element detected
    // --------------------------------------------------
    @Test
    void emptyRequiredElement_detected() {
        // GE01 (Number of Transaction Sets) is required — provide empty string
        EdiDocument.Segment ge = seg("GE", "", "12345");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(ge, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("missing or empty")),
                "Should flag GE01 empty required element");
    }

    // --------------------------------------------------
    //  12. Element too short detected
    // --------------------------------------------------
    @Test
    void elementTooShort_detected() {
        // ST01 (Transaction Set Identifier Code) min=3, max=3 — provide "85" (2 chars)
        EdiDocument.Segment st = seg("ST", "85", "0001");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(st, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && i.getProblem().contains("too short")),
                "Should flag ST01 too short");
    }

    // --------------------------------------------------
    //  13. Element too long detected
    // --------------------------------------------------
    @Test
    void elementTooLong_detected() {
        // ST02 (Transaction Set Control Number) max=9 — provide 12 chars
        EdiDocument.Segment st = seg("ST", "850", "123456789012");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(st, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 2 && i.getProblem().contains("too long")),
                "Should flag ST02 too long");
    }

    // --------------------------------------------------
    //  14. Valid EDIFACT UNB passes
    // --------------------------------------------------
    @Test
    void validEdifactUNB_passes() {
        EdiDocument.Segment unb = seg("UNB",
                "UNOC:3", "SENDER:14", "RECEIVER:14", "240101:1200", "00000001");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(unb, "EDIFACT", 0);
        assertEquals(0, countErrors(issues),
                "Valid UNB should have no errors, but got: " + issues);
    }

    // --------------------------------------------------
    //  15. EDIFACT NAD with valid qualifier passes
    // --------------------------------------------------
    @Test
    void edifactNADWithValidQualifier_passes() {
        EdiDocument.Segment nad = seg("NAD", "BY", "BUYER123::92", "", "ACME Corp");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(nad, "EDIFACT", 0);
        // Should not have errors on the qualifier
        assertFalse(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Valid NAD qualifier BY should not flag error");
    }

    // --------------------------------------------------
    //  16. Full valid X12 850 document — no issues
    // --------------------------------------------------
    @Test
    void fullValidX12_850_noIssues() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00", "          ", "00", "          ",
                        "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                        "240101", "1253", "^", "00501", "000000001", "0", "P", ":"),
                seg("GS", "PO", "SENDER", "RECEIVER", "20240101", "1200", "1", "X", "005010"),
                seg("ST", "850", "0001"),
                seg("BEG", "00", "NE", "PO-12345", "", "20240101"),
                seg("PO1", "1", "10", "EA", "25.00", "", "UP", "123456789"),
                seg("CTT", "1"),
                seg("SE", "4", "0001"),
                seg("GE", "1", "1"),
                seg("IEA", "1", "000000001"));

        List<ElementValidator.ElementIssue> issues = validator.validateDocument(d);
        assertEquals(0, countErrors(issues),
                "Full valid 850 should have no errors, got: " + issues);
    }

    // --------------------------------------------------
    //  17. Full valid X12 837 document — no issues
    // --------------------------------------------------
    @Test
    void fullValidX12_837_noIssues() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00", "          ", "00", "          ",
                        "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                        "240101", "1253", "^", "00501", "000000001", "0", "P", ":"),
                seg("GS", "HC", "SENDER", "RECEIVER", "20240101", "1200", "1", "X", "005010X222A1"),
                seg("ST", "837", "0001"),
                seg("BHT", "0019", "00", "REF123", "20240101", "1253", "CH"),
                seg("NM1", "85", "1", "SMITH", "JOHN", "", "", "", "XX", "1234567890"),
                seg("CLM", "PATIENT001", "500.00", "", "", "11:B:1"),
                seg("SV1", "HC:99213", "100.00", "UN", "1", "11"),
                seg("SE", "5", "0001"),
                seg("GE", "1", "1"),
                seg("IEA", "1", "000000001"));

        List<ElementValidator.ElementIssue> issues = validator.validateDocument(d);
        assertEquals(0, countErrors(issues),
                "Full valid 837 should have no errors, got: " + issues);
    }

    // --------------------------------------------------
    //  18. Document with mixed valid/invalid — correct issue count
    // --------------------------------------------------
    @Test
    void documentWithMixedValidInvalid_correctIssueCount() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00", "          ", "00", "          ",
                        "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                        "240101", "1253", "^", "00501", "000000001", "0", "P", ":"),
                seg("GS", "PO", "SENDER", "RECEIVER", "20240101", "1200", "1", "X", "005010"),
                seg("ST", "850", "0001"),
                // BEG with invalid purpose code "QQ" and invalid PO type "QQ"
                seg("BEG", "QQ", "QQ", "PO-12345", "", "20240101"),
                // PO1 with invalid UOM "ZZ" (not in set) and non-numeric quantity
                seg("PO1", "1", "ABC", "ZZ", "25.00", "", "UP", "123456789"),
                seg("SE", "4", "0001"),
                seg("GE", "1", "1"),
                seg("IEA", "1", "000000001"));

        List<ElementValidator.ElementIssue> issues = validator.validateDocument(d);
        long errors = countErrors(issues);
        // BEG01 invalid code, BEG02 invalid code = 2 errors
        // PO102 non-numeric = 1 error, PO103 invalid code = 1 error
        // Total = 4 errors
        assertTrue(errors >= 4,
                "Should have at least 4 errors for invalid BEG+PO1, got " + errors + ": " + issues);
    }

    // --------------------------------------------------
    //  19. Unknown segment skipped gracefully
    // --------------------------------------------------
    @Test
    void unknownSegment_skippedGracefully() {
        EdiDocument.Segment custom = seg("ZZZ", "some", "custom", "data");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(custom, "X12", 0);
        assertEquals(0, issues.size(), "Unknown segment should produce no issues");
    }

    // --------------------------------------------------
    //  20. EDIFACT NAD with invalid qualifier flags error
    // --------------------------------------------------
    @Test
    void edifactNADWithInvalidQualifier_flagsError() {
        // "ZZ" is not in the NAD valid set
        EdiDocument.Segment nad = seg("NAD", "ZZ");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(nad, "EDIFACT", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Should flag NAD01 invalid qualifier 'ZZ'");
    }

    // --------------------------------------------------
    //  21. EDIFACT UNB with missing required elements flags errors
    // --------------------------------------------------
    @Test
    void edifactUNBMissingRequired_flagsErrors() {
        // UNB needs at least: syntax id, sender, recipient, date/time, control ref
        // Provide only 2
        EdiDocument.Segment unb = seg("UNB", "UNOC:3", "SENDER:14");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(unb, "EDIFACT", 0);
        long errors = countErrors(issues);
        assertTrue(errors >= 3, "Should flag missing required UNB elements, got " + errors);
    }

    // --------------------------------------------------
    //  22. X12 date validation — valid 8-digit date
    // --------------------------------------------------
    @Test
    void validEightDigitDate_passes() {
        EdiDocument.Segment beg = seg("BEG", "00", "NE", "PO-12345", "", "20240315");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(beg, "X12", 0);
        assertFalse(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 5 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("date")),
                "Valid 8-digit date should not flag date error");
    }

    // --------------------------------------------------
    //  23. X12 time validation — invalid time
    // --------------------------------------------------
    @Test
    void invalidTime_flagsError() {
        // ISA10 is time HHMM 4-digit. "99XX" is not valid
        EdiDocument.Segment isa = seg("ISA",
                "00", "          ", "00", "          ",
                "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                "240101", "99XX", "^", "00501",
                "000000001", "0", "P", ":");

        List<ElementValidator.ElementIssue> issues = validator.validateSegment(isa, "X12", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 10 && i.getProblem().contains("time")),
                "Should flag ISA10 invalid time '99XX'");
    }

    // --------------------------------------------------
    //  24. Null document returns empty issues
    // --------------------------------------------------
    @Test
    void nullDocument_returnsEmptyIssues() {
        List<ElementValidator.ElementIssue> issues = validator.validateDocument(null);
        assertTrue(issues.isEmpty(), "Null doc should produce no issues");
    }

    // --------------------------------------------------
    //  25. Document with null segments returns empty issues
    // --------------------------------------------------
    @Test
    void documentWithNullSegments_returnsEmptyIssues() {
        EdiDocument d = EdiDocument.builder().sourceFormat("X12").segments(null).build();
        List<ElementValidator.ElementIssue> issues = validator.validateDocument(d);
        assertTrue(issues.isEmpty(), "Null segments should produce no issues");
    }

    // --------------------------------------------------
    //  26. X12 definitions registry covers expected segments
    // --------------------------------------------------
    @Test
    void x12Definitions_containAllExpectedSegments() {
        String[] expected = {"ISA","GS","ST","SE","GE","IEA","BEG","PO1","NM1","CLM","SV1",
                "N1","N3","N4","REF","DTM","DTP","BHT","HL","CTT","BIG","IT1","TDS","BSN",
                "BPR","CLP","SVC","AK1","AK9","PER","PID","INS","HD","LX","AMT"};
        for (String seg : expected) {
            assertTrue(x12Defs.hasDefinition(seg), "X12 should have definition for " + seg);
        }
        assertEquals(expected.length, x12Defs.getDefinedSegments().size());
    }

    // --------------------------------------------------
    //  27. EDIFACT definitions registry covers expected segments
    // --------------------------------------------------
    @Test
    void edifactDefinitions_containAllExpectedSegments() {
        String[] expected = {"UNB","UNH","UNT","UNZ","BGM","DTM","NAD","LIN","QTY","PRI",
                "MOA","UNS","CNT","FTX","RFF","PIA","TAX","ALC","TDT","LOC","PAC"};
        for (String seg : expected) {
            assertTrue(edifactDefs.hasDefinition(seg), "EDIFACT should have definition for " + seg);
        }
        assertEquals(expected.length, edifactDefs.getDefinedSegments().size());
    }

    // --------------------------------------------------
    //  28. Segment with null elements list treated as empty
    // --------------------------------------------------
    @Test
    void segmentWithNullElements_treatedAsEmpty() {
        EdiDocument.Segment st = EdiDocument.Segment.builder().id("ST").elements(null).build();
        List<ElementValidator.ElementIssue> issues = validator.validateSegment(st, "X12", 0);
        // ST has 2 required elements (pos 1 and 2), so we expect at least 2 errors
        assertTrue(countErrors(issues) >= 2,
                "Null elements on ST should flag missing required elements");
    }

    // --------------------------------------------------
    //  29. EDIFACT UNS with valid section identifier passes
    // --------------------------------------------------
    @Test
    void edifactUNSWithValidSectionId_passes() {
        EdiDocument.Segment uns = seg("UNS", "S");
        List<ElementValidator.ElementIssue> issues = validator.validateSegment(uns, "EDIFACT", 0);
        assertEquals(0, countErrors(issues), "Valid UNS should pass, got: " + issues);
    }

    // --------------------------------------------------
    //  30. EDIFACT UNS with invalid section identifier flags error
    // --------------------------------------------------
    @Test
    void edifactUNSWithInvalidSectionId_flagsError() {
        EdiDocument.Segment uns = seg("UNS", "X");
        List<ElementValidator.ElementIssue> issues = validator.validateSegment(uns, "EDIFACT", 0);
        assertTrue(issues.stream().anyMatch(i ->
                        i.getElementPosition() == 1 && "ERROR".equals(i.getSeverity())
                                && i.getProblem().contains("not in valid code set")),
                "Should flag UNS01 invalid section identifier 'X'");
    }
}
