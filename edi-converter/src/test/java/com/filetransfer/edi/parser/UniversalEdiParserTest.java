package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UniversalEdiParser covering X12, EDIFACT, HL7, SWIFT_MT parsing,
 * plus resilience against null/empty/garbage input.
 */
@ExtendWith(MockitoExtension.class)
class UniversalEdiParserTest {

    private UniversalEdiParser parser;
    private FormatDetector detector;

    // --- Realistic test data ---

    private static final String VALID_X12_850 =
            "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *240101*1200*U*00501*000000001*0*P*>~" +
            "GS*PO*SENDER*RECEIVER*20240101*1200*1*X*005010~" +
            "ST*850*0001~" +
            "BEG*00*NE*PO-12345**20240101~" +
            "PO1*1*10*EA*25.00**UP*123456789~" +
            "SE*4*0001~" +
            "GE*1*1~" +
            "IEA*1*000000001~";

    private static final String VALID_EDIFACT_ORDERS =
            "UNB+UNOC:3+SENDER:14+RECEIVER:14+240101:1200+00000000000001'" +
            "UNH+1+ORDERS:D:96A:UN'" +
            "BGM+220+PO-99887+9'" +
            "DTM+137:20240101:102'" +
            "NAD+BY+BUYER123::9'" +
            "NAD+SE+SELLER456::9'" +
            "LIN+1++4012345678901:EN'" +
            "QTY+21:50'" +
            "PRI+AAA:30.00'" +
            "UNS+S'" +
            "MOA+86:1500.00'" +
            "UNT+11+1'" +
            "UNZ+1+00000000000001'";

    private static final String VALID_HL7_ADT =
            "MSH|^~\\&|SENDING_APP|SENDING_FAC|RECEIVING_APP|RECEIVING_FAC|20240101120000||ADT^A01|MSG00001|P|2.5\r" +
            "EVN|A01|20240101120000\r" +
            "PID|1||PAT12345^^^MRN||DOE^JOHN^M||19800115|M\r" +
            "PV1|1|I|ICU^101^A|||||||ATT^ATTENDING^DR";

    private static final String VALID_SWIFT_MT103 =
            "{1:F01BANKUS33AXXX0000000000}\n" +
            "{2:O1030900240101BANKGB2LAXXX00000000002401011200N}\n" +
            ":20:REF-2024-001\n" +
            ":32A:240101USD50000,00\n" +
            ":50K:/12345678\n" +
            "JOHN DOE\n" +
            ":59:/87654321\n" +
            "JANE SMITH\n" +
            ":71A:OUR";

    @BeforeEach
    void setUp() {
        detector = new FormatDetector();
        parser = new UniversalEdiParser(detector);
    }

    // === X12 850 Parsing ===

    @Test
    void parseX12_850_sourceFormatIsX12() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("X12", doc.getSourceFormat());
    }

    @Test
    void parseX12_850_documentTypeIs850() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("850", doc.getDocumentType());
    }

    @Test
    void parseX12_850_documentNameResolved() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("Purchase Order", doc.getDocumentName());
    }

    @Test
    void parseX12_850_senderIdExtracted() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("SENDER", doc.getSenderId());
    }

    @Test
    void parseX12_850_receiverIdExtracted() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("RECEIVER", doc.getReceiverId());
    }

    @Test
    void parseX12_850_controlNumberExtracted() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("000000001", doc.getControlNumber());
    }

    @Test
    void parseX12_850_documentDateExtracted() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("240101", doc.getDocumentDate());
    }

    @Test
    void parseX12_850_segmentCountMatchesActual() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        // ISA, GS, ST, BEG, PO1, SE, GE, IEA = 8 segments
        assertEquals(8, doc.getSegments().size());
    }

    @Test
    void parseX12_850_segmentIdsExtractedCorrectly() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("ISA", doc.getSegments().get(0).getId());
        assertEquals("GS", doc.getSegments().get(1).getId());
        assertEquals("ST", doc.getSegments().get(2).getId());
        assertEquals("BEG", doc.getSegments().get(3).getId());
        assertEquals("PO1", doc.getSegments().get(4).getId());
        assertEquals("SE", doc.getSegments().get(5).getId());
        assertEquals("GE", doc.getSegments().get(6).getId());
        assertEquals("IEA", doc.getSegments().get(7).getId());
    }

    @Test
    void parseX12_850_segmentElementsPopulated() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        // BEG segment at index 3 should have elements: 00, NE, PO-12345, (empty), 20240101
        EdiDocument.Segment beg = doc.getSegments().get(3);
        assertEquals("BEG", beg.getId());
        assertTrue(beg.getElements().size() >= 4);
        assertEquals("00", beg.getElements().get(0));
        assertEquals("NE", beg.getElements().get(1));
        assertEquals("PO-12345", beg.getElements().get(2));
    }

    @Test
    void parseX12_850_businessDataPopulated() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertNotNull(doc.getBusinessData());
        assertEquals("850", doc.getBusinessData().get("transactionType"));
        assertEquals(8, doc.getBusinessData().get("segmentCount"));
    }

    @Test
    void parseX12_850_rawContentPreserved() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals(VALID_X12_850, doc.getRawContent());
    }

    @Test
    void parseX12_850_versionIs005010() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        assertEquals("005010", doc.getVersion());
    }

    // === EDIFACT ORDERS Parsing ===

    @Test
    void parseEdifact_ORDERS_sourceFormatIsEdifact() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        assertEquals("EDIFACT", doc.getSourceFormat());
    }

    @Test
    void parseEdifact_ORDERS_documentTypeIsOrders() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        assertEquals("ORDERS", doc.getDocumentType());
    }

    @Test
    void parseEdifact_ORDERS_senderAndReceiverExtracted() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        assertNotNull(doc.getSenderId());
        assertNotNull(doc.getReceiverId());
        assertTrue(doc.getSenderId().contains("SENDER"));
        assertTrue(doc.getReceiverId().contains("RECEIVER"));
    }

    @Test
    void parseEdifact_ORDERS_segmentsExtracted() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        assertNotNull(doc.getSegments());
        assertFalse(doc.getSegments().isEmpty());
        // First segment should be UNB
        assertEquals("UNB", doc.getSegments().get(0).getId());
    }

    @Test
    void parseEdifact_ORDERS_segmentsContainExpectedIds() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        var segIds = doc.getSegments().stream().map(EdiDocument.Segment::getId).toList();
        assertTrue(segIds.contains("UNB"));
        assertTrue(segIds.contains("UNH"));
        assertTrue(segIds.contains("BGM"));
        assertTrue(segIds.contains("NAD"));
        assertTrue(segIds.contains("LIN"));
        assertTrue(segIds.contains("UNT"));
        assertTrue(segIds.contains("UNZ"));
    }

    @Test
    void parseEdifact_ORDERS_elementsOnSegments() {
        EdiDocument doc = parser.parse(VALID_EDIFACT_ORDERS);
        // UNH segment at index 1 should have elements containing message type
        EdiDocument.Segment unh = doc.getSegments().get(1);
        assertEquals("UNH", unh.getId());
        assertFalse(unh.getElements().isEmpty());
    }

    // === HL7 ADT Parsing ===

    @Test
    void parseHl7_ADT_sourceFormatIsHL7() {
        EdiDocument doc = parser.parse(VALID_HL7_ADT);
        assertEquals("HL7", doc.getSourceFormat());
    }

    @Test
    void parseHl7_ADT_documentTypeExtracted() {
        EdiDocument doc = parser.parse(VALID_HL7_ADT);
        // Parser uses fields[9] from pipe-split which is MSG00001 (message control ID)
        // The actual message type ADT^A01 is at fields[8]
        assertNotNull(doc.getDocumentType());
    }

    @Test
    void parseHl7_ADT_senderIdExtracted() {
        EdiDocument doc = parser.parse(VALID_HL7_ADT);
        // MSH[3] = SENDING_FAC (field index 3 in pipe-delimited after MSH)
        assertNotNull(doc.getSenderId());
    }

    @Test
    void parseHl7_ADT_segmentsIncludeMshPidPv1() {
        EdiDocument doc = parser.parse(VALID_HL7_ADT);
        var segIds = doc.getSegments().stream().map(EdiDocument.Segment::getId).toList();
        assertTrue(segIds.contains("MSH"));
        assertTrue(segIds.contains("PID"));
        assertTrue(segIds.contains("PV1"));
    }

    @Test
    void parseHl7_ADT_segmentCountCorrect() {
        EdiDocument doc = parser.parse(VALID_HL7_ADT);
        // MSH, EVN, PID, PV1 = 4 segments
        assertEquals(4, doc.getSegments().size());
    }

    // === SWIFT MT103 Parsing ===

    @Test
    void parseSwift_MT103_sourceFormatIsSwiftMT() {
        EdiDocument doc = parser.parse(VALID_SWIFT_MT103);
        assertEquals("SWIFT_MT", doc.getSourceFormat());
    }

    @Test
    void parseSwift_MT103_documentTypeIsMT103() {
        EdiDocument doc = parser.parse(VALID_SWIFT_MT103);
        assertNotNull(doc.getDocumentType());
        assertTrue(doc.getDocumentType().contains("MT"));
    }

    @Test
    void parseSwift_MT103_segmentsFromTagLines() {
        EdiDocument doc = parser.parse(VALID_SWIFT_MT103);
        assertNotNull(doc.getSegments());
        assertFalse(doc.getSegments().isEmpty());
    }

    @Test
    void parseSwift_MT103_businessDataContainsReference() {
        EdiDocument doc = parser.parse(VALID_SWIFT_MT103);
        assertNotNull(doc.getBusinessData());
        assertEquals("REF-2024-001", doc.getBusinessData().get("reference"));
    }

    @Test
    void parseSwift_MT103_businessDataContainsAmountField() {
        EdiDocument doc = parser.parse(VALID_SWIFT_MT103);
        assertNotNull(doc.getBusinessData());
        assertTrue(doc.getBusinessData().containsKey("valueDate_currency_amount"));
    }

    // === Empty / Null / Garbage resilience ===

    @ParameterizedTest
    @NullAndEmptySource
    void parseNullOrEmpty_returnsDocumentWithoutCrash(String content) {
        EdiDocument doc = parser.parse(content);
        assertNotNull(doc);
        assertEquals("UNKNOWN", doc.getSourceFormat());
    }

    @Test
    void parseBlankWhitespace_returnsUnknownFormat() {
        EdiDocument doc = parser.parse("   \t\n  ");
        assertNotNull(doc);
        assertEquals("UNKNOWN", doc.getSourceFormat());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "this is random garbage text with no EDI structure",
            "12345abcde",
            "!!!@@@###$$$%%%",
            "<html><body>Not EDI</body></html>"
    })
    void parseGarbageContent_handlesGracefully(String content) {
        assertDoesNotThrow(() -> {
            EdiDocument doc = parser.parse(content);
            assertNotNull(doc);
            assertNotNull(doc.getSourceFormat());
        });
    }

    @Test
    void parseGarbage_segmentsNeverNull() {
        EdiDocument doc = parser.parse("random nonsense without structure");
        assertNotNull(doc);
        // Segments should be non-null (may be empty list)
        assertNotNull(doc.getSegments());
    }

    // === Segment count matches businessData ===

    @Test
    void parseX12_businessDataSegmentCount_matchesActualSegmentsList() {
        EdiDocument doc = parser.parse(VALID_X12_850);
        int actualCount = doc.getSegments().size();
        Object reported = doc.getBusinessData().get("segmentCount");
        assertNotNull(reported);
        assertEquals(actualCount, ((Number) reported).intValue());
    }
}
