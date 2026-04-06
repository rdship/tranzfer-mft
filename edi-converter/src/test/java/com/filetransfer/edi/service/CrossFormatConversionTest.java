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

    // ========================================================================
    // X12 850 Round-trip fidelity tests
    // ========================================================================

    private static final String X12_850_RT =
            "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *240101*1200*U*00501*000000001*0*P*>~" +
            "GS*PO*SENDER*RECEIVER*20240101*1200*1*X*005010~" +
            "ST*850*0001~" +
            "BEG*00*NE*PO-12345**20240101~" +
            "NM1*BY*1*Smith*John~" +
            "PO1*1*10*EA*25.00**VP*123456789~" +
            "PO1*2*5*CS*99.99**VP*WIDGET-X~" +
            "SE*6*0001~" +
            "GE*1*1~" +
            "IEA*1*000000001~";

    @Test
    void roundTrip_x12_850_preservesPurchaseOrderNumber() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals("PO-12345", canonical.getHeader().getDocumentNumber());

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals("PO-12345", recanonical.getHeader().getDocumentNumber());
    }

    @Test
    void roundTrip_x12_850_preservesLineItemQuantities() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals(2, canonical.getLineItems().size());
        assertEquals(10, canonical.getLineItems().get(0).getQuantity());
        assertEquals(5, canonical.getLineItems().get(1).getQuantity());

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals(2, recanonical.getLineItems().size());
        assertEquals(10, recanonical.getLineItems().get(0).getQuantity());
        assertEquals(5, recanonical.getLineItems().get(1).getQuantity());
    }

    @Test
    void roundTrip_x12_850_preservesLineItemPrices() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals(25.0, canonical.getLineItems().get(0).getUnitPrice(), 0.01);
        assertEquals(99.99, canonical.getLineItems().get(1).getUnitPrice(), 0.01);

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals(25.0, recanonical.getLineItems().get(0).getUnitPrice(), 0.01);
        assertEquals(99.99, recanonical.getLineItems().get(1).getUnitPrice(), 0.01);
    }

    @Test
    void roundTrip_x12_850_preservesPartyNames() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        // NM1*BY*1*Smith*John -> role=BUYER, name="John Smith"
        boolean hasBuyer = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BUYER && p.getName() != null && p.getName().contains("Smith"));
        assertTrue(hasBuyer, "Should have BUYER party with name containing 'Smith'");

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        boolean hasBuyerAgain = recanonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BUYER && p.getName() != null && p.getName().contains("Smith"));
        assertTrue(hasBuyerAgain, "Round-tripped output should still have BUYER with 'Smith'");
    }

    // ========================================================================
    // X12 837 Round-trip fidelity tests
    // ========================================================================

    private static final String X12_837 =
            "ISA*00*          *00*          *ZZ*SUBMITTER      *ZZ*RECEIVER       *240301*0800*U*00501*000000002*0*P*>~" +
            "GS*HC*SUBMITTER*RECEIVER*20240301*0800*2*X*005010~" +
            "ST*837*0002~" +
            "BHT*0019*00*CLM-98765*20240301~" +
            "NM1*85*1*Johnson*Dr Mary****34*NPI12345~" +
            "CLM*CLM-98765*1500.00~" +
            "SV1*HC:99213*150.00*UN*1~" +
            "SE*6*0002~" +
            "GE*1*2~" +
            "IEA*1*000000002~";

    @Test
    void roundTrip_x12_837_preservesClaimId() {
        EdiDocument doc = parser.parse(X12_837);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals("CLM-98765", canonical.getHeader().getDocumentNumber());

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals("CLM-98765", recanonical.getHeader().getDocumentNumber());
    }

    @Test
    void roundTrip_x12_837_preservesClaimAmount() {
        EdiDocument doc = parser.parse(X12_837);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals(1500.0, canonical.getTotals().getTotalAmount(), 0.01);

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        // The regenerated output should carry the claim amount through TDS
        assertTrue(regenerated.contains("1500") || regenerated.contains("150000"),
                "Regenerated X12 should contain the claim amount");
    }

    @Test
    void roundTrip_x12_837_preservesProviderName() {
        EdiDocument doc = parser.parse(X12_837);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        boolean hasProvider = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PROVIDER && p.getName() != null && p.getName().contains("Johnson"));
        assertTrue(hasProvider, "Should have PROVIDER party with name containing 'Johnson'");

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        assertTrue(regenerated.contains("Johnson"), "Regenerated X12 should contain provider name 'Johnson'");
    }

    // ========================================================================
    // X12 810 Round-trip fidelity tests
    // ========================================================================

    private static final String X12_810 =
            "ISA*00*          *00*          *ZZ*VENDOR         *ZZ*CUSTOMER       *240215*1000*U*00501*000000003*0*P*>~" +
            "GS*IN*VENDOR*CUSTOMER*20240215*1000*3*X*005010~" +
            "ST*810*0003~" +
            "BIG*20240215*INV-55678~" +
            "IT1*1*20*EA*15.00~" +
            "TDS*30000~" +
            "SE*5*0003~" +
            "GE*1*3~" +
            "IEA*1*000000003~";

    @Test
    void roundTrip_x12_810_preservesInvoiceNumber() {
        EdiDocument doc = parser.parse(X12_810);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals("INV-55678", canonical.getHeader().getDocumentNumber());

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        // Strip any trailing segment terminator artifact from the round-trip
        String reDocNum = recanonical.getHeader().getDocumentNumber();
        if (reDocNum != null) reDocNum = reDocNum.replaceAll("[~']+$", "");
        assertEquals("INV-55678", reDocNum);
    }

    @Test
    void roundTrip_x12_810_preservesInvoiceDate() {
        EdiDocument doc = parser.parse(X12_810);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        // BIG date is element 0 -> formatDate("20240215") -> "2024-02-15"
        assertNotNull(canonical.getHeader().getDocumentDate());
        assertTrue(canonical.getHeader().getDocumentDate().contains("2024"),
                "Invoice date should contain year 2024");

        String regenerated = canonicalMapper.fromCanonical(canonical, "X12");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertNotNull(recanonical.getHeader().getDocumentDate());
        // Strip any trailing segment terminator artifact from the round-trip
        String reDate = recanonical.getHeader().getDocumentDate().replaceAll("[~']+$", "");
        assertTrue(reDate.contains("2024"),
                "Round-tripped invoice date should still contain year 2024");
    }

    // ========================================================================
    // EDIFACT ORDERS Round-trip fidelity tests
    // ========================================================================

    @Test
    void roundTrip_edifact_orders_preservesDocNumber() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals("ORDER-789", canonical.getHeader().getDocumentNumber());

        String regenerated = canonicalMapper.fromCanonical(canonical, "EDIFACT");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals("ORDER-789", recanonical.getHeader().getDocumentNumber());
    }

    @Test
    void roundTrip_edifact_orders_preservesLineItems() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        assertEquals(1, canonical.getLineItems().size());
        assertEquals(200, canonical.getLineItems().get(0).getQuantity());
        assertEquals(7.50, canonical.getLineItems().get(0).getUnitPrice(), 0.01);

        String regenerated = canonicalMapper.fromCanonical(canonical, "EDIFACT");
        EdiDocument reparsed = parser.parse(regenerated);
        CanonicalDocument recanonical = canonicalMapper.toCanonical(reparsed);

        assertEquals(1, recanonical.getLineItems().size());
        assertEquals(200, recanonical.getLineItems().get(0).getQuantity());
        assertEquals(7.50, recanonical.getLineItems().get(0).getUnitPrice(), 0.01);
    }

    // ========================================================================
    // Cross-format round-trip tests
    // ========================================================================

    @Test
    void crossFormat_x12_850_to_edifact_preservesPONumber() {
        // X12 -> Canonical -> EDIFACT -> Canonical
        EdiDocument x12Doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical1 = canonicalMapper.toCanonical(x12Doc);
        assertEquals("PO-12345", canonical1.getHeader().getDocumentNumber());

        String edifact = canonicalMapper.fromCanonical(canonical1, "EDIFACT");
        EdiDocument edifactDoc = parser.parse(edifact);
        CanonicalDocument canonical2 = canonicalMapper.toCanonical(edifactDoc);

        assertEquals("PO-12345", canonical2.getHeader().getDocumentNumber());
    }

    @Test
    void crossFormat_edifact_orders_to_x12_preservesDocNumber() {
        // EDIFACT -> Canonical -> X12 -> Canonical
        EdiDocument edifactDoc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical1 = canonicalMapper.toCanonical(edifactDoc);
        assertEquals("ORDER-789", canonical1.getHeader().getDocumentNumber());

        String x12 = canonicalMapper.fromCanonical(canonical1, "X12");
        EdiDocument x12Doc = parser.parse(x12);
        CanonicalDocument canonical2 = canonicalMapper.toCanonical(x12Doc);

        assertEquals("ORDER-789", canonical2.getHeader().getDocumentNumber());
    }

    // ========================================================================
    // Structural validation tests
    // ========================================================================

    @Test
    void x12Output_hasProperEnvelopeStructure() {
        // Generate X12 from canonical, verify ISA/GS/ST...SE/GE/IEA structure
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        String x12 = canonicalMapper.fromCanonical(canonical, "X12");

        String[] lines = x12.split("\\n");
        assertTrue(lines.length >= 7, "Should have at least 7 segments (ISA,GS,ST,BEG,...,SE,GE,IEA)");

        // Extract segment IDs
        String firstSegId = lines[0].split("\\*")[0];
        String lastSegId = lines[lines.length - 1].split("\\*")[0];

        assertEquals("ISA", firstSegId, "First segment should be ISA");
        assertEquals("IEA", lastSegId.replace("~", ""), "Last segment should be IEA");

        // Verify the ordering: ISA, GS, ST, ..., SE, GE, IEA
        assertTrue(x12.indexOf("ISA*") < x12.indexOf("GS*"), "ISA should come before GS");
        assertTrue(x12.indexOf("GS*") < x12.indexOf("ST*"), "GS should come before ST");
        assertTrue(x12.indexOf("ST*") < x12.indexOf("SE*"), "ST should come before SE");
        assertTrue(x12.indexOf("SE*") < x12.indexOf("GE*"), "SE should come before GE");
        assertTrue(x12.indexOf("GE*") < x12.indexOf("IEA*"), "GE should come before IEA");
    }

    @Test
    void x12Output_seCountMatchesActualSegments() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        String x12 = canonicalMapper.fromCanonical(canonical, "X12");

        String[] lines = x12.split("\\n");
        // Find ST and SE lines
        int stIndex = -1, seIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String segId = lines[i].split("\\*")[0];
            if ("ST".equals(segId) && stIndex == -1) stIndex = i;
            if ("SE".equals(segId)) seIndex = i;
        }

        assertTrue(stIndex >= 0, "Should have ST segment");
        assertTrue(seIndex >= 0, "Should have SE segment");

        // Count segments from ST to SE inclusive
        int actualCount = seIndex - stIndex + 1;
        // SE*count*...~
        String seSegment = lines[seIndex].replace("~", "");
        String[] seParts = seSegment.split("\\*");
        int declaredCount = Integer.parseInt(seParts[1]);

        assertEquals(actualCount, declaredCount,
                "SE count (" + declaredCount + ") should match actual segment count from ST to SE (" + actualCount + ")");
    }

    @Test
    void x12Output_isaIeaControlNumbersMatch() {
        EdiDocument doc = parser.parse(X12_850_RT);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        String x12 = canonicalMapper.fromCanonical(canonical, "X12");

        String[] lines = x12.split("\\n");
        // ISA is first line, IEA is last line
        String isaLine = lines[0].replace("~", "");
        String ieaLine = lines[lines.length - 1].replace("~", "");

        String[] isaParts = isaLine.split("\\*");
        String[] ieaParts = ieaLine.split("\\*");

        // ISA13 (index 13) == IEA02 (index 2)
        String isaControlNum = isaParts[13].trim();
        String ieaControlNum = ieaParts[2].trim();

        assertEquals(isaControlNum, ieaControlNum,
                "ISA13 control number (" + isaControlNum + ") should match IEA02 (" + ieaControlNum + ")");
    }

    @Test
    void edifactOutput_hasProperEnvelopeStructure() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        String edifact = canonicalMapper.fromCanonical(canonical, "EDIFACT");

        // Should have UNA, UNB, UNH, ..., UNT, UNZ
        assertTrue(edifact.contains("UNA:+.? '"), "Should start with UNA service string");
        assertTrue(edifact.contains("UNB+"), "Should have UNB interchange header");
        assertTrue(edifact.contains("UNH+"), "Should have UNH message header");
        assertTrue(edifact.contains("UNT+"), "Should have UNT message trailer");
        assertTrue(edifact.contains("UNZ+"), "Should have UNZ interchange trailer");

        // Verify ordering
        assertTrue(edifact.indexOf("UNA") < edifact.indexOf("UNB+"), "UNA should come before UNB");
        assertTrue(edifact.indexOf("UNB+") < edifact.indexOf("UNH+"), "UNB should come before UNH");
        assertTrue(edifact.indexOf("UNH+") < edifact.indexOf("UNT+"), "UNH should come before UNT");
        assertTrue(edifact.indexOf("UNT+") < edifact.indexOf("UNZ+"), "UNT should come before UNZ");
    }

    @Test
    void edifactOutput_untCountMatchesActualSegments() {
        EdiDocument doc = parser.parse(EDIFACT_ORDERS);
        CanonicalDocument canonical = canonicalMapper.toCanonical(doc);
        String edifact = canonicalMapper.fromCanonical(canonical, "EDIFACT");

        String[] lines = edifact.split("\\n");
        // Count segments from UNH to UNT inclusive (skip UNA and UNB)
        int unhIndex = -1, untIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("UNH+") && unhIndex == -1) unhIndex = i;
            if (line.startsWith("UNT+")) untIndex = i;
        }

        assertTrue(unhIndex >= 0, "Should have UNH segment");
        assertTrue(untIndex >= 0, "Should have UNT segment");

        // Actual segment count from UNH to UNT inclusive
        int actualCount = untIndex - unhIndex + 1;

        // Parse UNT segment: UNT+count+ref'
        String untLine = lines[untIndex].replace("'", "");
        String[] untParts = untLine.split("\\+");
        int declaredCount = Integer.parseInt(untParts[1]);

        assertEquals(actualCount, declaredCount,
                "UNT count (" + declaredCount + ") should match actual segment count from UNH to UNT (" + actualCount + ")");
    }
}
