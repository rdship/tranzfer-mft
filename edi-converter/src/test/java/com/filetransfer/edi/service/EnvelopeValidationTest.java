package com.filetransfer.edi.service;

import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.CanonicalDocument.*;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests on the structural correctness of generated EDI envelopes.
 * Verifies that fromCanonical() produces spec-compliant envelope structures
 * for X12, EDIFACT, and HL7, and that the generated output can be re-parsed
 * and re-detected by our own parser and detector.
 *
 * Uses real instances (JDK 25 compatible — no Mockito on concrete classes).
 */
class EnvelopeValidationTest {

    private CanonicalMapper canonicalMapper;
    private UniversalEdiParser parser;
    private FormatDetector detector;

    @BeforeEach
    void setUp() {
        canonicalMapper = new CanonicalMapper();
        detector = new FormatDetector();
        parser = new UniversalEdiParser(detector);
    }

    /**
     * Builds a sample Purchase Order canonical document for envelope testing.
     */
    private CanonicalDocument buildSamplePO() {
        return CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder()
                        .documentNumber("PO-TEST-001")
                        .documentDate("20240315")
                        .currency("USD").build())
                .parties(List.of(
                        Party.builder()
                                .role(Party.PartyRole.BUYER)
                                .name("Acme Corp").id("ACME123").build(),
                        Party.builder()
                                .role(Party.PartyRole.SELLER)
                                .name("Widget Inc").id("WIDGET456").build()))
                .lineItems(List.of(
                        LineItem.builder()
                                .lineNumber(1).itemId("ITEM-1").quantity(100)
                                .unitOfMeasure("EA").unitPrice(9.99)
                                .productCode("SKU-001").build(),
                        LineItem.builder()
                                .lineNumber(2).itemId("ITEM-2").quantity(50)
                                .unitOfMeasure("EA").unitPrice(24.99)
                                .productCode("SKU-002").build()))
                .totals(MonetaryTotal.builder()
                        .totalAmount(2248.50).currency("USD").build())
                .build();
    }

    /**
     * Builds a sample Invoice canonical document.
     */
    private CanonicalDocument buildSampleInvoice() {
        return CanonicalDocument.builder()
                .type(DocumentType.INVOICE)
                .header(Header.builder()
                        .documentNumber("INV-TEST-001")
                        .documentDate("20240401")
                        .currency("EUR").build())
                .parties(List.of(
                        Party.builder()
                                .role(Party.PartyRole.SELLER)
                                .name("Vendor Ltd").id("VEND001").build(),
                        Party.builder()
                                .role(Party.PartyRole.BUYER)
                                .name("Client Inc").id("CLI002").build()))
                .lineItems(List.of(
                        LineItem.builder()
                                .lineNumber(1).quantity(25)
                                .unitOfMeasure("EA").unitPrice(40.00)
                                .productCode("PROD-A").build()))
                .totals(MonetaryTotal.builder()
                        .totalAmount(1000.00).currency("EUR").build())
                .build();
    }

    /**
     * Builds a sample Patient Admission canonical document for HL7 tests.
     */
    private CanonicalDocument buildSampleAdmission() {
        return CanonicalDocument.builder()
                .type(DocumentType.PATIENT_ADMISSION)
                .header(Header.builder()
                        .documentNumber("ADM-TEST-001")
                        .documentDate("20240320").build())
                .parties(List.of(
                        Party.builder()
                                .role(Party.PartyRole.PATIENT)
                                .name("Jane Smith").id("P12345").build(),
                        Party.builder()
                                .role(Party.PartyRole.PROVIDER)
                                .name("Dr Brown").build()))
                .lineItems(List.of())
                .build();
    }

    // ========================================================================
    // X12 Envelope Tests
    // ========================================================================

    @Test
    void x12_isaIsFirstSegment() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");
        assertTrue(lines.length > 0, "X12 output should not be empty");
        assertTrue(lines[0].startsWith("ISA*"), "First segment should be ISA, got: " + lines[0]);
    }

    @Test
    void x12_ieaIsLastSegment() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");
        String lastLine = lines[lines.length - 1].trim();
        assertTrue(lastLine.startsWith("IEA*"), "Last segment should be IEA, got: " + lastLine);
    }

    @Test
    void x12_isaIeaControlNumbersMatch() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        String isaLine = lines[0].replace("~", "");
        String ieaLine = lines[lines.length - 1].replace("~", "");

        String[] isaParts = isaLine.split("\\*");
        String[] ieaParts = ieaLine.split("\\*");

        // ISA13 = control number (index 13), IEA02 = control number (index 2)
        String isaCtrl = isaParts[13].trim();
        String ieaCtrl = ieaParts[2].trim();

        assertEquals(isaCtrl, ieaCtrl,
                "ISA13 (" + isaCtrl + ") must match IEA02 (" + ieaCtrl + ")");
    }

    @Test
    void x12_gsGeControlNumbersMatch() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        String gsLine = null, geLine = null;
        for (String line : lines) {
            if (line.startsWith("GS*")) gsLine = line.replace("~", "");
            if (line.startsWith("GE*")) geLine = line.replace("~", "");
        }

        assertNotNull(gsLine, "Should have GS segment");
        assertNotNull(geLine, "Should have GE segment");

        // GS06 = group control number (index 6), GE02 = group control number (index 2)
        String[] gsParts = gsLine.split("\\*");
        String[] geParts = geLine.split("\\*");

        String gsCtrl = gsParts[6].trim();
        String geCtrl = geParts[2].trim();

        assertEquals(gsCtrl, geCtrl,
                "GS06 (" + gsCtrl + ") must match GE02 (" + geCtrl + ")");
    }

    @Test
    void x12_stSeControlNumbersMatch() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        String stLine = null, seLine = null;
        for (String line : lines) {
            if (line.startsWith("ST*") && stLine == null) stLine = line.replace("~", "");
            if (line.startsWith("SE*")) seLine = line.replace("~", "");
        }

        assertNotNull(stLine, "Should have ST segment");
        assertNotNull(seLine, "Should have SE segment");

        // ST02 = transaction set control number (index 2), SE02 = same (index 2)
        String[] stParts = stLine.split("\\*");
        String[] seParts = seLine.split("\\*");

        String stCtrl = stParts[2].trim();
        String seCtrl = seParts[2].trim();

        assertEquals(stCtrl, seCtrl,
                "ST02 (" + stCtrl + ") must match SE02 (" + seCtrl + ")");
    }

    @Test
    void x12_seCountCorrect() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        int stIndex = -1, seIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String segId = lines[i].split("\\*")[0];
            if ("ST".equals(segId) && stIndex == -1) stIndex = i;
            if ("SE".equals(segId)) seIndex = i;
        }

        assertTrue(stIndex >= 0, "Should have ST segment");
        assertTrue(seIndex >= 0, "Should have SE segment");

        int actualCount = seIndex - stIndex + 1;
        String seSeg = lines[seIndex].replace("~", "");
        int declaredCount = Integer.parseInt(seSeg.split("\\*")[1]);

        assertEquals(actualCount, declaredCount,
                "SE01 count (" + declaredCount + ") should equal actual segments from ST to SE (" + actualCount + ")");
    }

    @Test
    void x12_geCountIsOne() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        for (String line : lines) {
            if (line.startsWith("GE*")) {
                String geSeg = line.replace("~", "");
                String[] parts = geSeg.split("\\*");
                assertEquals("1", parts[1].trim(),
                        "GE01 (number of transaction sets) should be 1");
                return;
            }
        }
        fail("GE segment not found in X12 output");
    }

    @Test
    void x12_ieaCountIsOne() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        String lastLine = lines[lines.length - 1].replace("~", "");
        assertTrue(lastLine.startsWith("IEA*"), "Last segment should be IEA");
        String[] parts = lastLine.split("\\*");
        assertEquals("1", parts[1].trim(),
                "IEA01 (number of functional groups) should be 1");
    }

    @Test
    void x12_hasCorrectSegmentOrder() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String[] lines = x12.split("\\n");

        // Collect segment IDs in order
        String[] segIds = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            segIds[i] = lines[i].split("\\*")[0];
        }

        // First three should be ISA, GS, ST
        assertEquals("ISA", segIds[0], "Segment 0 should be ISA");
        assertEquals("GS", segIds[1], "Segment 1 should be GS");
        assertEquals("ST", segIds[2], "Segment 2 should be ST");

        // Last three should be SE, GE, IEA
        assertEquals("SE", segIds[lines.length - 3].replace("~", ""), "Third-to-last should be SE");
        assertEquals("GE", segIds[lines.length - 2].replace("~", ""), "Second-to-last should be GE");
        assertEquals("IEA", segIds[lines.length - 1].replace("~", ""), "Last should be IEA");
    }

    @Test
    void x12_formatDetectedAsX12() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        String detectedFormat = detector.detect(x12);
        assertEquals("X12", detectedFormat,
                "Generated X12 output should be detected as X12 by FormatDetector");
    }

    @Test
    void x12_generatedVersionIs005010OrEquivalent() {
        // The generator uses 004010 in the GS segment
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        // Check GS line for version
        String[] lines = x12.split("\\n");
        String gsLine = null;
        for (String line : lines) {
            if (line.startsWith("GS*")) {
                gsLine = line;
                break;
            }
        }
        assertNotNull(gsLine, "Should have GS segment");
        // GS08 is the version (index 8)
        String[] gsParts = gsLine.replace("~", "").split("\\*");
        String version = gsParts[8].trim();
        assertTrue(version.startsWith("004") || version.startsWith("005"),
                "GS version should be 004010 or 005010, got: " + version);
    }

    @Test
    void x12_reparsedOutputMatchesOriginalDocumentType() {
        String x12 = canonicalMapper.fromCanonical(buildSamplePO(), "X12");
        EdiDocument reparsed = parser.parse(x12);

        assertEquals("X12", reparsed.getSourceFormat());
        assertEquals("850", reparsed.getDocumentType(),
                "Reparsed X12 PO should have document type 850");
    }

    @Test
    void x12_invoiceOutputHasBigSegment() {
        String x12 = canonicalMapper.fromCanonical(buildSampleInvoice(), "X12");
        assertTrue(x12.contains("ST*810"), "Invoice should generate ST*810");
        assertTrue(x12.contains("BIG*"), "Invoice should have BIG header segment");
        assertTrue(x12.contains("INV-TEST-001"), "Invoice should contain the document number");
    }

    // ========================================================================
    // EDIFACT Envelope Tests
    // ========================================================================

    @Test
    void edifact_unbIsPresent() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        assertTrue(edifact.contains("UNB+"), "EDIFACT output should contain UNB segment");
    }

    @Test
    void edifact_unzIsLastSegment() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        String[] lines = edifact.split("\\n");
        String lastLine = lines[lines.length - 1].trim();
        assertTrue(lastLine.startsWith("UNZ+"), "Last segment should be UNZ, got: " + lastLine);
    }

    @Test
    void edifact_untCountCorrect() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        String[] lines = edifact.split("\\n");

        int unhIndex = -1, untIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("UNH+") && unhIndex == -1) unhIndex = i;
            if (line.startsWith("UNT+")) untIndex = i;
        }

        assertTrue(unhIndex >= 0, "Should have UNH segment");
        assertTrue(untIndex >= 0, "Should have UNT segment");

        // Count segments from UNH to UNT inclusive
        int actualCount = untIndex - unhIndex + 1;
        String untLine = lines[untIndex].replace("'", "");
        String[] untParts = untLine.split("\\+");
        int declaredCount = Integer.parseInt(untParts[1]);

        assertEquals(actualCount, declaredCount,
                "UNT count (" + declaredCount + ") should match actual segment count from UNH to UNT (" + actualCount + ")");
    }

    @Test
    void edifact_formatDetectedAsEdifact() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        String detectedFormat = detector.detect(edifact);
        assertEquals("EDIFACT", detectedFormat,
                "Generated EDIFACT output should be detected as EDIFACT by FormatDetector");
    }

    @Test
    void edifact_hasUnaServiceString() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        assertTrue(edifact.startsWith("UNA:+.? '"),
                "EDIFACT output should start with UNA service string advice");
    }

    @Test
    void edifact_unzCountIsOne() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        String[] lines = edifact.split("\\n");
        String unzLine = lines[lines.length - 1].replace("'", "");
        assertTrue(unzLine.startsWith("UNZ+"));
        String[] parts = unzLine.split("\\+");
        assertEquals("1", parts[1].trim(),
                "UNZ message count should be 1");
    }

    @Test
    void edifact_bgmContainsDocumentNumber() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        assertTrue(edifact.contains("BGM+220+PO-TEST-001"),
                "BGM should contain doc code 220 (order) and document number PO-TEST-001");
    }

    @Test
    void edifact_reparsedOutputPreservesDocNumber() {
        String edifact = canonicalMapper.fromCanonical(buildSamplePO(), "EDIFACT");
        EdiDocument reparsed = parser.parse(edifact);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals("PO-TEST-001", recanonical.getHeader().getDocumentNumber(),
                "Reparsed EDIFACT should preserve document number");
    }

    // ========================================================================
    // HL7 Envelope Tests
    // ========================================================================

    @Test
    void hl7_mshIsFirstSegment() {
        String hl7 = canonicalMapper.fromCanonical(buildSampleAdmission(), "HL7");
        // HL7 uses \r as separator
        String[] segments = hl7.split("\\r");
        assertTrue(segments[0].startsWith("MSH|"),
                "First segment should be MSH, got: " + segments[0]);
    }

    @Test
    void hl7_formatDetectedAsHl7() {
        String hl7 = canonicalMapper.fromCanonical(buildSampleAdmission(), "HL7");
        String detectedFormat = detector.detect(hl7);
        assertEquals("HL7", detectedFormat,
                "Generated HL7 output should be detected as HL7 by FormatDetector");
    }

    @Test
    void hl7_containsPatientSegment() {
        String hl7 = canonicalMapper.fromCanonical(buildSampleAdmission(), "HL7");
        assertTrue(hl7.contains("PID|"), "HL7 output should contain PID segment");
        assertTrue(hl7.contains("P12345"), "HL7 PID should contain patient ID P12345");
        assertTrue(hl7.contains("Smith"), "HL7 PID should contain patient name Smith");
    }

    @Test
    void hl7_containsVisitSegment() {
        String hl7 = canonicalMapper.fromCanonical(buildSampleAdmission(), "HL7");
        assertTrue(hl7.contains("PV1|"), "HL7 output should contain PV1 (patient visit) segment");
    }

    // ========================================================================
    // Multi-format generation from same canonical
    // ========================================================================

    @Test
    void sameCanonical_generatesValidX12AndEdifact() {
        CanonicalDocument po = buildSamplePO();

        // Generate X12
        String x12 = canonicalMapper.fromCanonical(po, "X12");
        assertNotNull(x12, "X12 output should not be null");
        assertTrue(x12.contains("ISA*"), "X12 should have ISA");
        assertTrue(x12.contains("PO-TEST-001"), "X12 should contain document number");

        // Generate EDIFACT from the same canonical
        String edifact = canonicalMapper.fromCanonical(po, "EDIFACT");
        assertNotNull(edifact, "EDIFACT output should not be null");
        assertTrue(edifact.contains("UNB+"), "EDIFACT should have UNB");
        assertTrue(edifact.contains("PO-TEST-001"), "EDIFACT should contain document number");

        // Both should be parseable
        EdiDocument x12Doc = parser.parse(x12);
        assertEquals("X12", x12Doc.getSourceFormat());
        assertEquals("850", x12Doc.getDocumentType());

        EdiDocument edifactDoc = parser.parse(edifact);
        assertEquals("EDIFACT", edifactDoc.getSourceFormat());

        // Both should round-trip back to the same document number
        CanonicalDocument fromX12 = canonicalMapper.toCanonical(x12Doc);
        CanonicalDocument fromEdifact = canonicalMapper.toCanonical(edifactDoc);

        assertEquals("PO-TEST-001", fromX12.getHeader().getDocumentNumber());
        assertEquals("PO-TEST-001", fromEdifact.getHeader().getDocumentNumber());
    }

    @Test
    void sameCanonical_x12AndEdifactPreserveLineItemCounts() {
        CanonicalDocument po = buildSamplePO();

        String x12 = canonicalMapper.fromCanonical(po, "X12");
        String edifact = canonicalMapper.fromCanonical(po, "EDIFACT");

        EdiDocument x12Doc = parser.parse(x12);
        EdiDocument edifactDoc = parser.parse(edifact);

        CanonicalDocument fromX12 = canonicalMapper.toCanonical(x12Doc);
        CanonicalDocument fromEdifact = canonicalMapper.toCanonical(edifactDoc);

        assertEquals(2, fromX12.getLineItems().size(),
                "X12 round-trip should preserve 2 line items");
        assertEquals(2, fromEdifact.getLineItems().size(),
                "EDIFACT round-trip should preserve 2 line items");
    }
}
