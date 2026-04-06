package com.filetransfer.edi.service;

import com.filetransfer.edi.converter.UniversalConverter;
import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.CanonicalDocument.*;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end cross-format EDI conversion tests.
 * Verifies: X12 850 → EDIFACT ORDERS, EDIFACT → X12, HL7, SWIFT,
 * round-trip fidelity, and the Canonical bridge.
 *
 * Uses real instances (JDK 25 compatible — no Mockito on concrete classes).
 */
class CrossFormatConversionTest {

    private CanonicalMapper canonicalMapper;
    private UniversalConverter converter;
    private FormatDetector detector;
    private UniversalEdiParser parser;

    // Sample X12 850 Purchase Order
    private static final String X12_850 =
            "ISA*00*          *00*          *ZZ*ACME           *ZZ*WIDGETCO       *210101*1200*U*00401*000000001*0*P*~\n" +
            "GS*PO*ACME*WIDGETCO*20210101*1200*1*X*004010~\n" +
            "ST*850*0001~\n" +
            "BEG*00*NE*PO-12345**20210101~\n" +
            "NM1*BY*1*Smith*John~\n" +
            "N3*123 Main Street~\n" +
            "N4*Springfield*IL*62704~\n" +
            "PO1*1*100*EA*5.00**VP*WIDGET-A~\n" +
            "PO1*2*50*EA*12.50**VP*GADGET-B~\n" +
            "SE*8*0001~\n" +
            "GE*1*1~\n" +
            "IEA*1*000000001~";

    // Sample EDIFACT ORDERS
    private static final String EDIFACT_ORDERS =
            "UNA:+.? '\n" +
            "UNB+UNOA:4+SUPPLIER:ZZ+BUYER:ZZ+260406:0900+REF001'\n" +
            "UNH+1+ORDERS:D:01B:UN'\n" +
            "BGM+220+ORDER-789+9'\n" +
            "DTM+137:20260406:102'\n" +
            "NAD+BY+BUYER-ID::91++Acme Corp'\n" +
            "NAD+SE+SELLER-ID::91++Widget Factory'\n" +
            "LIN+1++PART-001:SA'\n" +
            "QTY+21:200'\n" +
            "PRI+AAA:7.50'\n" +
            "UNS+S'\n" +
            "MOA+86:1500'\n" +
            "CNT+2:1'\n" +
            "UNT+11+1'\n" +
            "UNZ+1+REF001'";

    @BeforeEach
    void setUp() {
        canonicalMapper = new CanonicalMapper();
        converter = new UniversalConverter(canonicalMapper);
        detector = new FormatDetector();
        parser = new UniversalEdiParser(detector);
    }

    // ========================================================================
    // X12 → EDIFACT
    // ========================================================================

    @Test
    void x12_850_to_edifact_producesValidEdifactOrders() {
        EdiDocument doc = parser.parse(X12_850);
        String edifact = converter.convert(doc, "EDIFACT");

        assertNotNull(edifact);
        assertTrue(edifact.contains("UNB+"), "Should have UNB interchange header");
        assertTrue(edifact.contains("UNH+"), "Should have UNH message header");
        assertTrue(edifact.contains("ORDERS"), "Should be ORDERS message type");
        assertTrue(edifact.contains("BGM+220"), "Should have BGM with 220 (order)");
        assertTrue(edifact.contains("PO-12345"), "Should carry over PO number");
        assertTrue(edifact.contains("LIN+"), "Should have line items");
        assertTrue(edifact.contains("QTY+21:100"), "Should have first item qty 100");
        assertTrue(edifact.contains("UNT+"), "Should have UNT trailer");
        assertTrue(edifact.contains("UNZ+"), "Should have UNZ trailer");
    }

    @Test
    void x12_850_to_edifact_preservesBuyerParty() {
        EdiDocument doc = parser.parse(X12_850);
        String edifact = converter.convert(doc, "EDIFACT");

        assertTrue(edifact.contains("NAD+BY"), "Should have buyer NAD segment");
    }

    @Test
    void x12_850_to_edifact_preservesLineItems() {
        EdiDocument doc = parser.parse(X12_850);
        String edifact = converter.convert(doc, "EDIFACT");

        // Should have 2 LIN segments (2 PO1 line items)
        int linCount = edifact.split("LIN\\+").length - 1;
        assertEquals(2, linCount, "Should produce 2 LIN segments from 2 PO1 items");

        assertTrue(edifact.contains("WIDGET-A"), "Should carry product code WIDGET-A");
        assertTrue(edifact.contains("GADGET-B"), "Should carry product code GADGET-B");
    }

    // ========================================================================
    // EDIFACT → X12
    // ========================================================================

    @Test
    void edifact_orders_to_x12_producesValidX12_850() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        String x12 = converter.convert(doc, "X12");

        assertNotNull(x12);
        assertTrue(x12.contains("ISA*"), "Should have ISA header");
        assertTrue(x12.contains("GS*PO"), "Should have GS with PO func code");
        assertTrue(x12.contains("ST*850"), "Should be 850 transaction");
        assertTrue(x12.contains("BEG*"), "Should have BEG header");
        assertTrue(x12.contains("ORDER-789"), "Should carry over order number");
        assertTrue(x12.contains("PO1*"), "Should have PO1 line items");
        assertTrue(x12.contains("SE*"), "Should have SE trailer");
        assertTrue(x12.contains("IEA*"), "Should have IEA trailer");
    }

    @Test
    void edifact_orders_to_x12_preservesQuantityAndPrice() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        String x12 = converter.convert(doc, "X12");

        assertTrue(x12.contains("200"), "Should carry over quantity 200");
        assertTrue(x12.contains("7.50") || x12.contains("7.5"), "Should carry over price 7.50");
    }

    // ========================================================================
    // Canonical bridge — round trip fidelity
    // ========================================================================

    @Test
    void x12_to_canonical_preservesBusinessData() {
        EdiDocument doc = parser.parse(X12_850);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);

        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("X12", canonical.getSourceFormat());
        assertEquals("850", canonical.getSourceDocumentType());
        assertNotNull(canonical.getHeader());
        assertEquals("PO-12345", canonical.getHeader().getDocumentNumber());
        assertEquals(2, canonical.getLineItems().size());
        assertEquals(100, canonical.getLineItems().get(0).getQuantity());
        assertEquals(5.0, canonical.getLineItems().get(0).getUnitPrice());
    }

    @Test
    void edifact_to_canonical_preservesBusinessData() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);

        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("EDIFACT", canonical.getSourceFormat());
        assertNotNull(canonical.getHeader());
        assertEquals("ORDER-789", canonical.getHeader().getDocumentNumber());
    }

    @Test
    void canonical_to_x12_roundTrip_preservesKeyFields() {
        // X12 → Canonical → X12 → Canonical
        EdiDocument original = parser.parse(X12_850);
        CanonicalDocument canonical1 = canonicalMapper.toCanonical(original);

        String regeneratedX12 = canonicalMapper.fromCanonical(canonical1, "X12");
        EdiDocument reparsed = parser.parse(regeneratedX12);
        CanonicalDocument canonical2 = canonicalMapper.toCanonical(reparsed);

        // Key business data should survive the round trip
        assertEquals(canonical1.getType(), canonical2.getType());
        assertEquals(canonical1.getHeader().getDocumentNumber(), canonical2.getHeader().getDocumentNumber());
        assertEquals(canonical1.getLineItems().size(), canonical2.getLineItems().size());
    }

    // ========================================================================
    // fromCanonical() direct tests
    // ========================================================================

    @Test
    void fromCanonical_x12_generatesValidStructure() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("TEST-001").documentDate("20260406").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("SENDER-1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("RECV-1").build(),
                        Party.builder().role(Party.PartyRole.BUYER).name("John Doe").build()
                ))
                .lineItems(List.of(
                        LineItem.builder().lineNumber(1).quantity(10).unitPrice(25.00)
                                .unitOfMeasure("EA").productCode("SKU-100").build()
                ))
                .totals(MonetaryTotal.builder().totalAmount(250.00).currency("USD").build())
                .build();

        String x12 = canonicalMapper.fromCanonical(doc, "X12");

        assertTrue(x12.contains("ISA*"));
        assertTrue(x12.contains("ST*850"));
        assertTrue(x12.contains("BEG*00*NE*TEST-001"));
        assertTrue(x12.contains("NM1*BY*1*Doe*John"));
        assertTrue(x12.contains("PO1*1*10*EA*25"));
        assertTrue(x12.contains("SKU-100"));
        assertTrue(x12.contains("TDS*25000")); // 250.00 * 100
        assertTrue(x12.contains("CUR*BY*USD"));
        assertTrue(x12.contains("SE*"));
        assertTrue(x12.contains("IEA*"));
    }

    @Test
    void fromCanonical_edifact_generatesValidStructure() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.INVOICE)
                .header(Header.builder().documentNumber("INV-001").documentDate("20260406").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("SENDER-1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("RECV-1").build(),
                        Party.builder().role(Party.PartyRole.BUYER).id("BUY-1").name("Acme Corp").build()
                ))
                .lineItems(List.of(
                        LineItem.builder().lineNumber(1).quantity(50).unitPrice(10.00)
                                .productCode("PROD-A").build()
                ))
                .totals(MonetaryTotal.builder().totalAmount(500.00).currency("EUR").build())
                .build();

        String edifact = canonicalMapper.fromCanonical(doc, "EDIFACT");

        assertTrue(edifact.contains("UNA:+.? '"));
        assertTrue(edifact.contains("UNB+UNOA:4"));
        assertTrue(edifact.contains("INVOIC"), "Should use INVOIC message type for Invoice");
        assertTrue(edifact.contains("BGM+380"), "Should use 380 BGM code for Invoice");
        assertTrue(edifact.contains("INV-001"));
        assertTrue(edifact.contains("NAD+BY"));
        assertTrue(edifact.contains("LIN+1"));
        assertTrue(edifact.contains("QTY+21:50"));
        assertTrue(edifact.contains("CUX+2:EUR"));
        assertTrue(edifact.contains("MOA+86:500"));
        assertTrue(edifact.contains("UNT+"));
        assertTrue(edifact.contains("UNZ+"));
    }

    @Test
    void fromCanonical_hl7_generatesValidStructure() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PATIENT_ADMISSION)
                .header(Header.builder().documentNumber("ADM-001").documentDate("20260406").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.PATIENT).name("Jane Smith").id("P12345").build(),
                        Party.builder().role(Party.PartyRole.PROVIDER).name("Dr Brown").build()
                ))
                .lineItems(List.of())
                .build();

        String hl7 = canonicalMapper.fromCanonical(doc, "HL7");

        assertTrue(hl7.contains("MSH|"));
        assertTrue(hl7.contains("PID|"));
        assertTrue(hl7.contains("P12345"), "Should contain patient ID");
        assertTrue(hl7.contains("Smith"), "Should contain patient last name");
        assertTrue(hl7.contains("PV1|"));
    }

    @Test
    void fromCanonical_swift_generatesValidStructure() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.WIRE_TRANSFER)
                .header(Header.builder().documentNumber("TXN-001").documentDate("20260406").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.PAYER).name("Alice Sender").build(),
                        Party.builder().role(Party.PartyRole.PAYEE).name("Bob Receiver").build(),
                        Party.builder().role(Party.PartyRole.BANK_SENDER).id("BANKUS33").build(),
                        Party.builder().role(Party.PartyRole.BANK_RECEIVER).id("BANKGB22").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().totalAmount(1500.00).currency("USD").build())
                .build();

        String swift = canonicalMapper.fromCanonical(doc, "SWIFT_MT");

        assertTrue(swift.contains(":20:TXN-001"), "Should have reference");
        assertTrue(swift.contains(":32A:"), "Should have value/date/amount field");
        assertTrue(swift.contains("USD"), "Should have currency");
        assertTrue(swift.contains("1500.00"), "Should have amount");
        assertTrue(swift.contains(":50K:"), "Should have ordering customer");
        assertTrue(swift.contains(":59:"), "Should have beneficiary");
    }

    @Test
    void fromCanonical_unsupportedFormat_throws() {
        CanonicalDocument doc = CanonicalDocument.builder().type(DocumentType.UNKNOWN).build();
        assertThrows(IllegalArgumentException.class,
                () -> canonicalMapper.fromCanonical(doc, "BANANA"));
    }

    // ========================================================================
    // Full pipeline: parse → convert → re-parse → verify
    // ========================================================================

    @Test
    void fullPipeline_x12_to_edifact_reparseable() {
        // Parse original X12
        EdiDocument x12Doc = parser.parse(X12_850);

        // Convert to EDIFACT
        String edifactOutput = converter.convert(x12Doc, "EDIFACT");

        // Re-parse the EDIFACT output
        EdiDocument edifactDoc = parser.parse(edifactOutput);

        // Should be detected as EDIFACT
        assertEquals("EDIFACT", edifactDoc.getSourceFormat());

        // Business data should come through
        CanonicalDocument canonical = canonicalMapper.toCanonical(edifactDoc);
        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("PO-12345", canonical.getHeader().getDocumentNumber());
    }

    @Test
    void fullPipeline_edifact_to_x12_reparseable() {
        // Parse original EDIFACT
        EdiDocument edifactDoc = parser.parse(EDIFACT_ORDERS);

        // Convert to X12
        String x12Output = converter.convert(edifactDoc, "X12");

        // Re-parse the X12 output
        EdiDocument x12Doc = parser.parse(x12Output);

        // Should be detected as X12
        assertEquals("X12", x12Doc.getSourceFormat());
        assertEquals("850", x12Doc.getDocumentType());
    }

    @Test
    void converter_convert_x12_delegatesToCanonicalBridge() {
        EdiDocument doc = parser.parse(X12_850);
        // Convert X12 input to EDIFACT output via the standard convert() method
        String result = converter.convert(doc, "EDIFACT");
        assertNotNull(result);
        assertTrue(result.contains("UNB+"), "convert() should route EDI targets through canonical bridge");
    }
}
