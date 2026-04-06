package com.filetransfer.edi.service;

import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.CanonicalDocument.*;
import com.filetransfer.edi.model.EdiDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CanonicalMapper covering X12 850, X12 837, EDIFACT ORDERS, HL7 ADT mappings,
 * party role assignments, monetary totals, null/empty resilience, new segment types,
 * and spec-compliant reverse generation.
 */
@ExtendWith(MockitoExtension.class)
class CanonicalMapperTest {

    private CanonicalMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CanonicalMapper();
    }

    // === Helper to build segments ===

    private EdiDocument.Segment seg(String id, String... elements) {
        return EdiDocument.Segment.builder().id(id).elements(List.of(elements)).build();
    }

    private EdiDocument.Segment segWithNamedFields(String id, Map<String, String> named, String... elements) {
        return EdiDocument.Segment.builder().id(id).elements(List.of(elements)).namedFields(named).build();
    }

    // === X12 850 -> PURCHASE_ORDER ===

    @Test
    void mapX12_850_documentTypeIsPurchaseOrder() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .senderId("SENDER").receiverId("RECEIVER")
                .segments(List.of(
                        seg("ISA", "00", "          ", "00", "          ", "ZZ", "SENDER         ",
                                "ZZ", "RECEIVER       ", "240101", "1200", "U", "00501", "000000001", "0", "P"),
                        seg("GS", "PO", "SENDER", "RECEIVER", "20240101", "1200", "1", "X", "005010"),
                        seg("ST", "850", "0001"),
                        seg("BEG", "00", "NE", "PO-12345", "", "20240101"),
                        seg("NM1", "BY", "1", "ACME CORP", "", "", "", "", "", "1234567890"),
                        seg("NM1", "SE", "1", "SUPPLIER INC", "", "", "", "", "", "9876543210"),
                        seg("PO1", "1", "10", "EA", "25.00", "", "UP", "123456789"),
                        seg("PO1", "2", "5", "CA", "100.00", "", "UP", "987654321"),
                        seg("SE", "8", "0001"),
                        seg("GE", "1", "1"),
                        seg("IEA", "1", "000000001")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("X12", canonical.getSourceFormat());
        assertEquals("850", canonical.getSourceDocumentType());
    }

    @Test
    void mapX12_850_headerContainsPONumber() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("BEG", "00", "NE", "PO-12345", "", "20240101"),
                        seg("ST", "850", "0001")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getHeader());
        assertEquals("PO-12345", canonical.getHeader().getDocumentNumber());
    }

    @Test
    void mapX12_850_headerDocumentDateFormatted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("BEG", "00", "NE", "PO-12345", "", "20240101")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getHeader().getDocumentDate());
        assertEquals("2024-01-01", canonical.getHeader().getDocumentDate());
    }

    @Test
    void mapX12_850_headerPurposeDecoded() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("BEG", "00", "NE", "PO-12345", "", "20240101")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("Original", canonical.getHeader().getPurpose());
    }

    @Test
    void mapX12_850_partiesContainBuyerAndSeller() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("NM1", "BY", "1", "ACME CORP", "", "", "", "", "", "1234567890"),
                        seg("NM1", "SE", "1", "SUPPLIER INC", "", "", "", "", "", "9876543210")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getParties());
        assertTrue(canonical.getParties().size() >= 2);

        boolean hasBuyer = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BUYER);
        boolean hasSeller = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.SELLER);
        assertTrue(hasBuyer);
        assertTrue(hasSeller);
    }

    @Test
    void mapX12_850_buyerNameExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("NM1", "BY", "1", "ACME CORP", "", "", "", "", "", "1234567890")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        Party buyer = canonical.getParties().stream()
                .filter(p -> p.getRole() == Party.PartyRole.BUYER)
                .findFirst().orElse(null);
        assertNotNull(buyer);
        assertTrue(buyer.getName().contains("ACME CORP"));
    }

    @Test
    void mapX12_850_lineItemsExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("PO1", "1", "10", "EA", "25.00", "", "UP", "123456789"),
                        seg("PO1", "2", "5", "CA", "100.00", "", "UP", "987654321")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getLineItems());
        assertEquals(2, canonical.getLineItems().size());

        LineItem item1 = canonical.getLineItems().get(0);
        assertEquals(1, item1.getLineNumber());
        assertEquals(10.0, item1.getQuantity());
        assertEquals("EA", item1.getUnitOfMeasure());
        assertEquals(25.00, item1.getUnitPrice());
        assertEquals("123456789", item1.getProductCode());

        LineItem item2 = canonical.getLineItems().get(1);
        assertEquals(2, item2.getLineNumber());
        assertEquals(5.0, item2.getQuantity());
        assertEquals(100.00, item2.getUnitPrice());
    }

    @Test
    void mapX12_850_senderReceiverAddedAsPartiesIfNoNM1() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .senderId("SENDER123").receiverId("RECEIVER456")
                .segments(List.of(
                        seg("ST", "850", "0001"),
                        seg("BEG", "00", "NE", "PO-12345", "", "20240101")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        boolean hasSender = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.SENDER && "SENDER123".equals(p.getId()));
        boolean hasReceiver = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.RECEIVER && "RECEIVER456".equals(p.getId()));
        assertTrue(hasSender);
        assertTrue(hasReceiver);
    }

    // === X12 837 -> HEALTHCARE_CLAIM ===

    @Test
    void mapX12_837_documentTypeIsHealthcareClaim() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("ST", "837", "0001"),
                        seg("BHT", "0019", "00", "CLM-REF-001", "20240101", "1200", "CH")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(DocumentType.HEALTHCARE_CLAIM, canonical.getType());
    }

    @Test
    void mapX12_837_claimAmountInTotals() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("CLM", "CLM001", "1500.00", "", "", "11:B:1"),
                        seg("NM1", "IL", "1", "DOE", "JOHN", "", "", "", "MI", "INS12345"),
                        seg("NM1", "82", "1", "DR SMITH", "", "", "", "", "XX", "NPI123456"),
                        seg("SV1", "HC:99213", "150.00", "UN", "1")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getTotals());
        assertEquals(1500.00, canonical.getTotals().getTotalAmount(), 0.01);
    }

    @Test
    void mapX12_837_patientAndProviderParties() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("NM1", "IL", "1", "DOE", "JOHN", "", "", "", "MI", "INS12345"),
                        seg("NM1", "82", "1", "DR SMITH", "", "", "", "", "XX", "NPI123456")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        boolean hasPatient = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PATIENT);
        boolean hasProvider = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PROVIDER);
        assertTrue(hasPatient);
        assertTrue(hasProvider);
    }

    @Test
    void mapX12_837_serviceLineItems() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("SV1", "HC:99213", "150.00", "UN", "1"),
                        seg("SV1", "HC:99214", "200.00", "UN", "2")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertEquals(2, canonical.getLineItems().size());
        assertEquals(150.00, canonical.getLineItems().get(0).getUnitPrice(), 0.01);
        assertEquals(200.00, canonical.getLineItems().get(1).getUnitPrice(), 0.01);
    }

    @Test
    void mapX12_837_bhtSetsDocumentNumber() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("BHT", "0019", "00", "CLM-REF-001", "20240101", "1200", "CH")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("CLM-REF-001", canonical.getHeader().getDocumentNumber());
        assertEquals("2024-01-01", canonical.getHeader().getDocumentDate());
    }

    // === EDIFACT ORDERS -> PURCHASE_ORDER ===

    @Test
    void mapEdifact_ORDERS_documentTypeIsPurchaseOrder() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .senderId("SENDER:14").receiverId("RECEIVER:14")
                .segments(List.of(
                        seg("UNB", "UNOC:3", "SENDER:14", "RECEIVER:14", "240101:1200", "1"),
                        seg("UNH", "1", "ORDERS:D:96A:UN"),
                        seg("BGM", "220", "PO-99887", "9"),
                        seg("NAD", "BY", "BUYER123::9", "", "BUYER CORP"),
                        seg("NAD", "SE", "SELLER456::9", "", "SELLER LTD"),
                        seg("LIN", "1", "", "4012345678901:EN"),
                        seg("QTY", "21:50"),
                        seg("PRI", "AAA:30.00"),
                        seg("MOA", "86:1500.00"),
                        seg("UNT", "9", "1"),
                        seg("UNZ", "1", "1")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("EDIFACT", canonical.getSourceFormat());
    }

    @Test
    void mapEdifact_ORDERS_buyerAndSellerParties() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("NAD", "BY", "BUYER123::9", "", "BUYER CORP"),
                        seg("NAD", "SE", "SELLER456::9", "", "SELLER LTD")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        boolean hasBuyer = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BUYER);
        boolean hasSeller = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.SELLER);
        assertTrue(hasBuyer);
        assertTrue(hasSeller);
    }

    @Test
    void mapEdifact_ORDERS_buyerNameExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("NAD", "BY", "BUYER123::9", "", "BUYER CORP")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        Party buyer = canonical.getParties().stream()
                .filter(p -> p.getRole() == Party.PartyRole.BUYER)
                .findFirst().orElse(null);
        assertNotNull(buyer);
        assertEquals("BUYER CORP", buyer.getName());
    }

    @Test
    void mapEdifact_ORDERS_lineItemWithQuantityAndPrice() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("LIN", "1", "", "4012345678901:EN"),
                        seg("QTY", "21:50"),
                        seg("PRI", "AAA:30.00")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertEquals(1, canonical.getLineItems().size());
        LineItem item = canonical.getLineItems().get(0);
        assertEquals(50.0, item.getQuantity());
        assertEquals(30.00, item.getUnitPrice(), 0.01);
    }

    @Test
    void mapEdifact_ORDERS_monetaryTotalFromMOA() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("MOA", "86:1500.00")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(1500.00, canonical.getTotals().getTotalAmount(), 0.01);
    }

    @Test
    void mapEdifact_ORDERS_bgmSetsDocumentNumber() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("BGM", "220", "PO-99887", "9")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("PO-99887", canonical.getHeader().getDocumentNumber());
    }

    // === HL7 ADT -> PATIENT_ADMISSION ===

    @Test
    void mapHl7_ADT_documentTypeIsPatientAdmission() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "SENDING_APP", "SENDING_FAC", "RECEIVING_APP",
                                "RECEIVING_FAC", "20240101120000", "", "ADT^A01", "MSG00001", "P", "2.5"),
                        seg("PID", "1", "", "PAT12345^^^MRN", "", "DOE^JOHN^M", "", "19800115", "M"),
                        seg("PV1", "1", "I", "ICU^101^A", "", "", "", "", "ATT^ATTENDING^DR")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(DocumentType.PATIENT_ADMISSION, canonical.getType());
        assertEquals("HL7", canonical.getSourceFormat());
    }

    @Test
    void mapHl7_ADT_patientPartyExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "SENDING_APP", "SENDING_FAC", "RECEIVING_APP",
                                "RECEIVING_FAC", "20240101120000", "", "ADT^A01", "MSG00001"),
                        seg("PID", "1", "", "PAT12345^^^MRN", "", "DOE^JOHN^M", "", "19800115", "M")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        boolean hasPatient = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PATIENT);
        assertTrue(hasPatient);

        Party patient = canonical.getParties().stream()
                .filter(p -> p.getRole() == Party.PartyRole.PATIENT)
                .findFirst().orElse(null);
        assertNotNull(patient);
        assertTrue(patient.getName().contains("DOE"));
        assertTrue(patient.getName().contains("JOHN"));
    }

    @Test
    void mapHl7_ADT_providerPartyExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "SENDING_APP", "SENDING_FAC", "RECEIVING_APP",
                                "RECEIVING_FAC", "20240101120000", "", "ADT^A01", "MSG00001"),
                        seg("PV1", "1", "I", "ICU^101^A", "", "", "", "", "ATT^ATTENDING^DR")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        boolean hasProvider = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PROVIDER);
        assertTrue(hasProvider);
    }

    @Test
    void mapHl7_ADT_extensionsContainSendingApp() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "SENDING_APP", "SENDING_FAC", "RECEIVING_APP",
                                "RECEIVING_FAC", "20240101120000", "", "ADT^A01", "MSG00001")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getExtensions());
        assertEquals("SENDING_APP", canonical.getExtensions().get("sendingApplication"));
    }

    @Test
    void mapHl7_ADT_lineItemsEmpty() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "SENDING_APP")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertNotNull(canonical.getLineItems());
        assertTrue(canonical.getLineItems().isEmpty());
    }

    // === Verify party roles ===

    @Test
    void mapX12_allPartyRolesCorrect() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("NM1", "BY", "1", "BUYER", "", "", "", "", "", "B001"),
                        seg("NM1", "SE", "1", "SELLER", "", "", "", "", "", "S001"),
                        seg("NM1", "ST", "1", "SHIP TO", "", "", "", "", "", "ST01"),
                        seg("NM1", "BT", "1", "BILL TO", "", "", "", "", "", "BT01")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        List<Party> parties = canonical.getParties();

        assertTrue(parties.stream().anyMatch(p -> p.getRole() == Party.PartyRole.BUYER));
        assertTrue(parties.stream().anyMatch(p -> p.getRole() == Party.PartyRole.SELLER));
        assertTrue(parties.stream().anyMatch(p -> p.getRole() == Party.PartyRole.SHIP_TO));
        assertTrue(parties.stream().anyMatch(p -> p.getRole() == Party.PartyRole.BILL_TO));
    }

    // === Monetary totals ===

    @Test
    void mapX12_810_tdsTotalCalculatedCorrectly() {
        // TDS amount is in cents (pennies), so 150000 = $1500.00
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("810")
                .segments(List.of(
                        seg("TDS", "150000")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(1500.00, canonical.getTotals().getTotalAmount(), 0.01);
    }

    @Test
    void mapX12_currencyExtracted() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("CUR", "BY", "USD")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("USD", canonical.getTotals().getCurrency());
    }

    // === Null/empty segments ===

    @Test
    void mapX12_emptySegments_noCrash() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of())
                .build();

        assertDoesNotThrow(() -> {
            CanonicalDocument canonical = mapper.toCanonical(doc);
            assertNotNull(canonical);
            assertNotNull(canonical.getType());
        });
    }

    @Test
    void mapX12_segmentsWithNullElements_noCrash() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        EdiDocument.Segment.builder().id("BEG").elements(null).build()
                ))
                .build();

        assertDoesNotThrow(() -> {
            CanonicalDocument canonical = mapper.toCanonical(doc);
            assertNotNull(canonical);
        });
    }

    @Test
    void mapX12_unknownDocType_resolvesToUnknown() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("999999")
                .segments(List.of())
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(DocumentType.UNKNOWN, canonical.getType());
    }

    @Test
    void mapGenericFormat_resolvesToUnknown() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("SOMETHING_NEW").documentType("XYZ")
                .segments(List.of())
                .controlNumber("REF001")
                .documentDate("20240101")
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(DocumentType.UNKNOWN, canonical.getType());
        assertEquals("SOMETHING_NEW", canonical.getSourceFormat());
    }

    @Test
    void mapCanonical_documentIdGenerated() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of())
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertNotNull(canonical.getDocumentId());
        assertFalse(canonical.getDocumentId().isEmpty());
    }

    @Test
    void mapCanonical_createdAtPopulated() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of())
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertNotNull(canonical.getCreatedAt());
    }

    // ===================================================================
    // NEW: Forward mapping — REF segments
    // ===================================================================

    @Test
    void mapX12_refSegmentsCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("REF", "EI", "123456789", "Employer ID"),
                        seg("REF", "SY", "999-88-7777")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getReferences());
        assertEquals(2, canonical.getReferences().size());

        Reference ref1 = canonical.getReferences().get(0);
        assertEquals("EI", ref1.getQualifier());
        assertEquals("123456789", ref1.getValue());
        assertEquals("Employer ID", ref1.getDescription());

        Reference ref2 = canonical.getReferences().get(1);
        assertEquals("SY", ref2.getQualifier());
        assertEquals("999-88-7777", ref2.getValue());
        assertNull(ref2.getDescription());
    }

    // ===================================================================
    // NEW: Forward mapping — DTP/DTM dates
    // ===================================================================

    @Test
    void mapX12_dtpDatesCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("DTP", "472", "D8", "20240115"),
                        seg("DTP", "434", "RD8", "20240101-20240131")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getDates());
        assertEquals(2, canonical.getDates().size());

        DateInfo d1 = canonical.getDates().get(0);
        assertEquals("472", d1.getQualifier());
        assertEquals("D8", d1.getFormat());
        assertEquals("20240115", d1.getValue());

        DateInfo d2 = canonical.getDates().get(1);
        assertEquals("434", d2.getQualifier());
        assertEquals("RD8", d2.getFormat());
        assertEquals("20240101-20240131", d2.getValue());
    }

    @Test
    void mapX12_dtmDatesCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("DTM", "002", "20240301")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getDates());
        assertEquals(1, canonical.getDates().size());
        assertEquals("002", canonical.getDates().get(0).getQualifier());
        assertEquals("20240301", canonical.getDates().get(0).getValue());
        assertNull(canonical.getDates().get(0).getFormat());
    }

    // ===================================================================
    // NEW: Forward mapping — PER contacts
    // ===================================================================

    @Test
    void mapX12_perContactsCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("PER", "IC", "JOHN SMITH", "TE", "5551234567", "EM", "john@example.com")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getContacts());
        assertEquals(1, canonical.getContacts().size());

        Contact ct = canonical.getContacts().get(0);
        assertEquals("IC", ct.getType());
        assertEquals("JOHN SMITH", ct.getName());
        assertEquals("5551234567", ct.getPhone());
        assertEquals("john@example.com", ct.getEmail());
    }

    @Test
    void mapX12_perContactWithFax() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("PER", "IC", "JANE DOE", "TE", "5559876543", "FX", "5559876544")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getContacts());
        Contact ct = canonical.getContacts().get(0);
        assertEquals("5559876543", ct.getPhone());
        assertEquals("5559876544", ct.getFax());
        assertNull(ct.getEmail());
    }

    // ===================================================================
    // NEW: Forward mapping — N1 parties
    // ===================================================================

    @Test
    void mapX12_n1PartiesCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("N1", "ST", "ACME WAREHOUSE", "92", "WH001"),
                        seg("N1", "BT", "BILLING DEPT", "91", "BD001")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getParties());
        boolean hasShipTo = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.SHIP_TO && "ACME WAREHOUSE".equals(p.getName()));
        boolean hasBillTo = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BILL_TO && "BILLING DEPT".equals(p.getName()));
        assertTrue(hasShipTo, "Should have SHIP_TO party from N1*ST");
        assertTrue(hasBillTo, "Should have BILL_TO party from N1*BT");

        Party shipTo = canonical.getParties().stream()
                .filter(p -> p.getRole() == Party.PartyRole.SHIP_TO)
                .findFirst().orElse(null);
        assertNotNull(shipTo);
        assertEquals("92", shipTo.getQualifier());
        assertEquals("WH001", shipTo.getId());
    }

    // ===================================================================
    // NEW: Forward mapping — HL hierarchy
    // ===================================================================

    @Test
    void mapX12_hlHierarchyCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("HL", "1", "", "20"),
                        seg("HL", "2", "1", "22"),
                        seg("HL", "3", "2", "23")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getExtensions());
        @SuppressWarnings("unchecked")
        Map<String, String> hl1 = (Map<String, String>) canonical.getExtensions().get("hl_1");
        assertNotNull(hl1);
        assertEquals("20", hl1.get("levelCode"));

        @SuppressWarnings("unchecked")
        Map<String, String> hl2 = (Map<String, String>) canonical.getExtensions().get("hl_2");
        assertNotNull(hl2);
        assertEquals("1", hl2.get("parentId"));
        assertEquals("22", hl2.get("levelCode"));
    }

    // ===================================================================
    // NEW: Forward mapping — SBR, DMG, AMT, BPR, TRN, CTT, BSN, SN1, AK1, AK9
    // ===================================================================

    @Test
    void mapX12_sbrDmgExtensionsCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("837")
                .segments(List.of(
                        seg("SBR", "P", "", "", "", "", "", "", "", "CI"),
                        seg("DMG", "D8", "19800115", "M")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertEquals("P", canonical.getExtensions().get("subscriberPayerResponsibility"));
        assertEquals("CI", canonical.getExtensions().get("claimFilingIndicator"));
        assertEquals("19800115", canonical.getExtensions().get("dateOfBirth"));
        assertEquals("M", canonical.getExtensions().get("genderCode"));
    }

    @Test
    void mapX12_amtCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("835")
                .segments(List.of(
                        seg("AMT", "T", "1500.00")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("1500.00", canonical.getExtensions().get("amount_T"));
    }

    @Test
    void mapX12_bprSetsTotalAndFlag() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("835")
                .segments(List.of(
                        seg("BPR", "I", "2500.00", "C")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(2500.00, canonical.getTotals().getTotalAmount(), 0.01);
        assertEquals("C", canonical.getExtensions().get("creditDebitFlag"));
    }

    @Test
    void mapX12_trnSetsReferenceNumber() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("835")
                .segments(List.of(
                        seg("TRN", "1", "TRN-REF-12345")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("TRN-REF-12345", canonical.getHeader().getReferenceNumber());
    }

    @Test
    void mapX12_cttCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        seg("CTT", "5")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("5", canonical.getExtensions().get("lineItemCount"));
    }

    @Test
    void mapX12_bsnSetsDocumentNumberAndDate() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("856")
                .segments(List.of(
                        seg("BSN", "00", "SHIP-001", "20240215", "1430")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("SHIP-001", canonical.getHeader().getDocumentNumber());
        assertEquals("2024-02-15", canonical.getHeader().getDocumentDate());
    }

    @Test
    void mapX12_sn1LineItemCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("856")
                .segments(List.of(
                        seg("SN1", "1", "100", "EA")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(1, canonical.getLineItems().size());
        assertEquals(100.0, canonical.getLineItems().get(0).getQuantity());
        assertEquals("EA", canonical.getLineItems().get(0).getUnitOfMeasure());
    }

    @Test
    void mapX12_ak1Ak9FunctionalAck() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12").documentType("997")
                .segments(List.of(
                        seg("AK1", "PO", "1"),
                        seg("AK9", "A", "1", "1", "1")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("PO", canonical.getExtensions().get("ackFuncIdCode"));
        assertEquals("A", canonical.getExtensions().get("funcGroupAckCode"));
        assertEquals("A", canonical.getHeader().getStatus());
    }

    // ===================================================================
    // NEW: EDIFACT forward mapping — RFF, FTX
    // ===================================================================

    @Test
    void mapEdifact_rffReferencesCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("RFF", "ON:PO-12345"),
                        seg("RFF", "CT:CONTRACT-001")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getReferences());
        assertEquals(2, canonical.getReferences().size());
        assertEquals("ON", canonical.getReferences().get(0).getQualifier());
        assertEquals("PO-12345", canonical.getReferences().get(0).getValue());
        assertEquals("CT", canonical.getReferences().get(1).getQualifier());
        assertEquals("CONTRACT-001", canonical.getReferences().get(1).getValue());
    }

    @Test
    void mapEdifact_ftxNotesCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("FTX", "AAA", "", "", "Please deliver before noon")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertNotNull(canonical.getNotes());
        assertEquals(1, canonical.getNotes().size());
        assertEquals("AAA", canonical.getNotes().get(0).getType());
        assertEquals("Please deliver before noon", canonical.getNotes().get(0).getText());
    }

    @Test
    void mapEdifact_tdtTransportCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("DESADV")
                .segments(List.of(
                        seg("TDT", "20", "", "", "CARRIER-XYZ")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("20", canonical.getExtensions().get("transportStageQualifier"));
        assertEquals("CARRIER-XYZ", canonical.getExtensions().get("carrierIdentification"));
    }

    @Test
    void mapEdifact_locLocationCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("EDIFACT").documentType("ORDERS")
                .segments(List.of(
                        seg("LOC", "7", "USNYC")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("USNYC", canonical.getExtensions().get("location_7"));
    }

    // ===================================================================
    // NEW: HL7 forward mapping — EVN, OBX
    // ===================================================================

    @Test
    void mapHl7_evnEventCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP", "FAC", "RAPP", "RFAC", "20240101120000", "", "ADT^A01", "MSG001"),
                        seg("EVN", "A01", "20240101120000")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("A01", canonical.getExtensions().get("eventTypeCode"));
        assertEquals("20240101120000", canonical.getExtensions().get("recordedDateTime"));
    }

    @Test
    void mapHl7_obxObservationCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("OBX", "1", "NM", "8302-2^Height", "", "180")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertNotNull(canonical.getExtensions());
        @SuppressWarnings("unchecked")
        Map<String, String> obs = (Map<String, String>) canonical.getExtensions().get("observation_8302-2^Height");
        assertNotNull(obs, "Should capture OBX observation");
        assertEquals("NM", obs.get("valueType"));
        assertEquals("180", obs.get("value"));
    }

    @Test
    void mapHl7_obrOrderCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("OBR", "1", "", "", "80053^CMP")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals(1, canonical.getLineItems().size());
        assertEquals("80053", canonical.getLineItems().get(0).getProductCode());
        assertEquals("CMP", canonical.getLineItems().get(0).getDescription());
    }

    @Test
    void mapHl7_al1AllergyCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("AL1", "1", "DA", "PENICILLIN")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        @SuppressWarnings("unchecked")
        Map<String, String> allergy = (Map<String, String>) canonical.getExtensions().get("allergy_1");
        assertNotNull(allergy);
        assertEquals("DA", allergy.get("allergyType"));
        assertEquals("PENICILLIN", allergy.get("allergenCode"));
    }

    @Test
    void mapHl7_dg1DiagnosisCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("DG1", "1", "I10", "J06.9")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        @SuppressWarnings("unchecked")
        Map<String, String> diag = (Map<String, String>) canonical.getExtensions().get("diagnosis_1");
        assertNotNull(diag);
        assertEquals("I10", diag.get("codingMethod"));
        assertEquals("J06.9", diag.get("diagnosisCode"));
    }

    @Test
    void mapHl7_in1InsuranceCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("IN1", "1", "INS001", "PLAN001", "BLUE CROSS")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        boolean hasPayer = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.PAYER && "INS001".equals(p.getId()));
        assertTrue(hasPayer, "IN1 should create a PAYER party");

        Party payer = canonical.getParties().stream()
                .filter(p -> p.getRole() == Party.PartyRole.PAYER)
                .findFirst().orElse(null);
        assertNotNull(payer);
        assertEquals("BLUE CROSS", payer.getName());
    }

    @Test
    void mapHl7_orcOrderControlCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("HL7").documentType("ADT_A01")
                .segments(List.of(
                        seg("MSH", "^~\\&", "", "APP"),
                        seg("ORC", "NW", "ORD-12345")
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);
        assertEquals("NW", canonical.getExtensions().get("orderControl"));
        assertEquals("ORD-12345", canonical.getHeader().getReferenceNumber());
    }

    // ===================================================================
    // NEW: SWIFT forward mapping — 23B, 57A, 52A, 53A, 70, 71A, 79
    // ===================================================================

    @Test
    void mapSwift_additionalTagsCaptured() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("SWIFT_MT").documentType("MT103")
                .businessData(Map.of("reference", "TXN-001"))
                .segments(List.of(
                        segWithNamedFields("TAG", Map.of("tag", "23B", "value", "CRED")),
                        segWithNamedFields("TAG", Map.of("tag", "52A", "value", "BANKUS33")),
                        segWithNamedFields("TAG", Map.of("tag", "53A", "value", "CORRUS33")),
                        segWithNamedFields("TAG", Map.of("tag", "57A", "value", "BANKGB22")),
                        segWithNamedFields("TAG", Map.of("tag", "70", "value", "Payment for invoice 123")),
                        segWithNamedFields("TAG", Map.of("tag", "71A", "value", "OUR")),
                        segWithNamedFields("TAG", Map.of("tag", "79", "value", "Urgent transfer"))
                ))
                .build();

        CanonicalDocument canonical = mapper.toCanonical(doc);

        assertEquals("CRED", canonical.getExtensions().get("bankOperationCode"));
        assertEquals("CORRUS33", canonical.getExtensions().get("sendersCorrespondent"));
        assertEquals("Payment for invoice 123", canonical.getExtensions().get("remittanceInfo"));
        assertEquals("OUR", canonical.getExtensions().get("detailsOfCharges"));
        assertEquals("Urgent transfer", canonical.getExtensions().get("narrativeText"));

        boolean hasBankSender = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BANK_SENDER && "BANKUS33".equals(p.getId()));
        boolean hasBankReceiver = canonical.getParties().stream()
                .anyMatch(p -> p.getRole() == Party.PartyRole.BANK_RECEIVER && "BANKGB22".equals(p.getId()));
        assertTrue(hasBankSender);
        assertTrue(hasBankReceiver);
    }

    // ===================================================================
    // NEW: X12 reverse generation — ISA is exactly 106 characters
    // ===================================================================

    @Test
    void generateX12_isaIsExactly106Characters() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-001").documentDate("2024-01-15").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("SENDER01").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("RECEIVER01").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String x12 = mapper.fromCanonical(doc, "X12");
        String[] lines = x12.split("\n");
        String isaLine = lines[0];

        // ISA line must be exactly 106 characters (105 data + 1 segment terminator)
        assertEquals(106, isaLine.length(),
                "ISA segment must be exactly 106 characters, was " + isaLine.length() + ": [" + isaLine + "]");
        assertTrue(isaLine.startsWith("ISA*"));
        assertTrue(isaLine.endsWith("~"));
    }

    // ===================================================================
    // NEW: X12 reverse generation — SE count excludes ISA/GS
    // ===================================================================

    @Test
    void generateX12_seCountIsCorrect() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-002").documentDate("2024-02-01").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build(),
                        Party.builder().role(Party.PartyRole.BUYER).id("B1").name("BUYER CORP").build()
                ))
                .lineItems(List.of(
                        LineItem.builder().lineNumber(1).quantity(10).unitOfMeasure("EA").unitPrice(25.00).productCode("P001").build()
                ))
                .totals(MonetaryTotal.builder().totalAmount(250.00).currency("USD").build())
                .build();

        String x12 = mapper.fromCanonical(doc, "X12");
        String[] lines = x12.split("\n");

        // Find ST and SE lines
        int stLineIdx = -1;
        int seLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("ST*")) stLineIdx = i;
            if (lines[i].startsWith("SE*")) seLineIdx = i;
        }
        assertTrue(stLineIdx >= 0, "Must have ST segment");
        assertTrue(seLineIdx >= 0, "Must have SE segment");

        // Count segments from ST to SE inclusive
        int actualSegmentCount = seLineIdx - stLineIdx + 1;

        // Extract SE count from the SE segment
        String seLine = lines[seLineIdx];
        String seCountStr = seLine.split("\\*")[1];
        int declaredCount = Integer.parseInt(seCountStr);

        assertEquals(actualSegmentCount, declaredCount,
                "SE count should equal number of segments from ST to SE inclusive");
    }

    // ===================================================================
    // NEW: X12 reverse generation — ISA/IEA control numbers match
    // ===================================================================

    @Test
    void generateX12_isaIeaControlNumbersMatch() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-003").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String x12 = mapper.fromCanonical(doc, "X12");
        String[] lines = x12.split("\n");

        String isaLine = lines[0];
        String ieaLine = null;
        for (String line : lines) {
            if (line.startsWith("IEA*")) ieaLine = line;
        }
        assertNotNull(ieaLine, "Must have IEA segment");

        // ISA13 is the 14th element (index 13), fields separated by *
        String[] isaFields = isaLine.replace("~", "").split("\\*");
        String isaControlNum = isaFields[13]; // ISA13

        // IEA02 is the control number
        String[] ieaFields = ieaLine.replace("~", "").split("\\*");
        String ieaControlNum = ieaFields[2]; // IEA02

        assertEquals(isaControlNum, ieaControlNum,
                "ISA13 and IEA02 control numbers must match");
    }

    // ===================================================================
    // NEW: X12 reverse generation — GS/GE control numbers match
    // ===================================================================

    @Test
    void generateX12_gsGeControlNumbersMatch() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-004").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String x12 = mapper.fromCanonical(doc, "X12");
        String[] lines = x12.split("\n");

        String gsLine = null;
        String geLine = null;
        for (String line : lines) {
            if (line.startsWith("GS*")) gsLine = line;
            if (line.startsWith("GE*")) geLine = line;
        }
        assertNotNull(gsLine, "Must have GS segment");
        assertNotNull(geLine, "Must have GE segment");

        // GS06 is the group control number
        String[] gsFields = gsLine.replace("~", "").split("\\*");
        String gsControlNum = gsFields[6]; // GS06

        // GE02 is the group control number
        String[] geFields = geLine.replace("~", "").split("\\*");
        String geControlNum = geFields[2]; // GE02

        assertEquals(gsControlNum, geControlNum,
                "GS06 and GE02 group control numbers must match");
    }

    // ===================================================================
    // NEW: X12 reverse generation — version is 005010
    // ===================================================================

    @Test
    void generateX12_versionIs005010() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-005").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String x12 = mapper.fromCanonical(doc, "X12");
        String[] lines = x12.split("\n");

        // ISA12 should be "00501"
        String isaLine = lines[0];
        String[] isaFields = isaLine.replace("~", "").split("\\*");
        assertEquals("00501", isaFields[12], "ISA12 version should be 00501");

        // GS08 should be "005010"
        String gsLine = null;
        for (String line : lines) {
            if (line.startsWith("GS*")) { gsLine = line; break; }
        }
        assertNotNull(gsLine);
        String[] gsFields = gsLine.replace("~", "").split("\\*");
        assertEquals("005010", gsFields[8], "GS08 version should be 005010");
    }

    // ===================================================================
    // NEW: EDIFACT reverse generation — UNT count is correct
    // ===================================================================

    @Test
    void generateEdifact_untCountIsCorrect() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("ED-001").documentDate("2024-03-01").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build(),
                        Party.builder().role(Party.PartyRole.BUYER).id("B1").name("BUYER").build()
                ))
                .lineItems(List.of(
                        LineItem.builder().lineNumber(1).quantity(5).unitPrice(10.00).productCode("ITEM1").build()
                ))
                .totals(MonetaryTotal.builder().totalAmount(50.00).currency("EUR").build())
                .build();

        String edifact = mapper.fromCanonical(doc, "EDIFACT");
        String[] lines = edifact.split("\n");

        // Find UNH and UNT lines
        int unhLineIdx = -1;
        int untLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("UNH+")) unhLineIdx = i;
            if (lines[i].startsWith("UNT+")) untLineIdx = i;
        }
        assertTrue(unhLineIdx >= 0, "Must have UNH segment");
        assertTrue(untLineIdx >= 0, "Must have UNT segment");

        // Count segments from UNH to UNT inclusive
        int actualCount = untLineIdx - unhLineIdx + 1;

        // Extract UNT count
        String untLine = lines[untLineIdx];
        String untCountStr = untLine.replace("'", "").split("\\+")[1];
        int declaredCount = Integer.parseInt(untCountStr);

        assertEquals(actualCount, declaredCount,
                "UNT count should equal number of segments from UNH to UNT inclusive");
    }

    // ===================================================================
    // NEW: EDIFACT reverse generation — UNB/UNZ control refs match
    // ===================================================================

    @Test
    void generateEdifact_unbUnzControlRefsMatch() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("ED-002").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String edifact = mapper.fromCanonical(doc, "EDIFACT");
        String[] lines = edifact.split("\n");

        String unbLine = null;
        String unzLine = null;
        for (String line : lines) {
            if (line.startsWith("UNB+")) unbLine = line;
            if (line.startsWith("UNZ+")) unzLine = line;
        }
        assertNotNull(unbLine);
        assertNotNull(unzLine);

        // UNB control ref is the last field before '
        String unbRef = unbLine.replace("'", "").split("\\+")[5]; // after UNOA:4, sender, receiver, date, ref
        // UNZ control ref is the second field
        String unzRef = unzLine.replace("'", "").split("\\+")[2];

        assertEquals(unbRef, unzRef, "UNB and UNZ control references must match");
    }

    // ===================================================================
    // NEW: Round-trip test — X12 850 -> Canonical -> X12 -> Canonical
    // ===================================================================

    @Test
    void roundTrip_x12_850_preservesKeyBusinessData() {
        EdiDocument originalDoc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .senderId("ACME_SENDER").receiverId("ACME_RECEIVER")
                .segments(List.of(
                        seg("ISA", "00", "          ", "00", "          ", "ZZ", "ACME_SENDER    ",
                                "ZZ", "ACME_RECEIVER  ", "240115", "1030", "U", "00501", "000000099", "0", "P"),
                        seg("GS", "PO", "ACME_SENDER", "ACME_RECEIVER", "20240115", "1030", "1", "X", "005010"),
                        seg("ST", "850", "0001"),
                        seg("BEG", "00", "NE", "PO-ROUND-001", "", "20240115"),
                        seg("NM1", "BY", "1", "ROUND TRIP BUYER", "", "", "", "", "", "BUY-001"),
                        seg("NM1", "SE", "1", "ROUND TRIP SELLER", "", "", "", "", "", "SEL-001"),
                        seg("PO1", "1", "20", "EA", "50.00", "", "VP", "WIDGET-A"),
                        seg("PO1", "2", "10", "CA", "100.00", "", "VP", "GADGET-B"),
                        seg("TDS", "200000"),
                        seg("CUR", "BY", "USD"),
                        seg("SE", "8", "0001"),
                        seg("GE", "1", "1"),
                        seg("IEA", "1", "000000099")
                ))
                .build();

        // Forward: EDI -> Canonical
        CanonicalDocument canonical = mapper.toCanonical(originalDoc);

        assertEquals(DocumentType.PURCHASE_ORDER, canonical.getType());
        assertEquals("PO-ROUND-001", canonical.getHeader().getDocumentNumber());
        assertEquals("2024-01-15", canonical.getHeader().getDocumentDate());
        assertEquals("Original", canonical.getHeader().getPurpose());
        assertEquals(2000.00, canonical.getTotals().getTotalAmount(), 0.01);
        assertEquals("USD", canonical.getTotals().getCurrency());
        assertEquals(2, canonical.getLineItems().size());
        assertEquals(20.0, canonical.getLineItems().get(0).getQuantity());
        assertEquals(50.0, canonical.getLineItems().get(0).getUnitPrice());

        // Reverse: Canonical -> X12 string
        String regeneratedX12 = mapper.fromCanonical(canonical, "X12");

        // Verify the regenerated X12 contains key data
        assertTrue(regeneratedX12.contains("850"), "Must contain transaction type 850");
        assertTrue(regeneratedX12.contains("PO-ROUND-001"), "Must preserve PO number");
        assertTrue(regeneratedX12.contains("20240115"), "Must preserve date");
        assertTrue(regeneratedX12.contains("200000"), "Must preserve TDS total (in cents)");
        assertTrue(regeneratedX12.contains("USD"), "Must preserve currency");
        assertTrue(regeneratedX12.contains("PO1"), "Must contain PO1 line items");

        // Verify structure: ISA, GS, ST, ..., SE, GE, IEA all present
        assertTrue(regeneratedX12.contains("ISA*"), "Must have ISA envelope");
        assertTrue(regeneratedX12.contains("GS*"), "Must have GS envelope");
        assertTrue(regeneratedX12.contains("ST*"), "Must have ST header");
        assertTrue(regeneratedX12.contains("SE*"), "Must have SE trailer");
        assertTrue(regeneratedX12.contains("GE*"), "Must have GE trailer");
        assertTrue(regeneratedX12.contains("IEA*"), "Must have IEA trailer");

        // Version must be 005010 in GS
        assertTrue(regeneratedX12.contains("005010"), "GS must use version 005010");
    }

    // ===================================================================
    // NEW: X12 reverse — control numbers are unique across calls
    // ===================================================================

    @Test
    void generateX12_controlNumbersUniqueAcrossCalls() {
        CanonicalDocument doc = CanonicalDocument.builder()
                .type(DocumentType.PURCHASE_ORDER)
                .header(Header.builder().documentNumber("PO-UNIQ").build())
                .parties(List.of(
                        Party.builder().role(Party.PartyRole.SENDER).id("S1").build(),
                        Party.builder().role(Party.PartyRole.RECEIVER).id("R1").build()
                ))
                .lineItems(List.of())
                .totals(MonetaryTotal.builder().build())
                .build();

        String x12_1 = mapper.fromCanonical(doc, "X12");
        String x12_2 = mapper.fromCanonical(doc, "X12");

        // Extract ISA13 from each
        String[] lines1 = x12_1.split("\n");
        String[] lines2 = x12_2.split("\n");

        String[] isaFields1 = lines1[0].replace("~", "").split("\\*");
        String[] isaFields2 = lines2[0].replace("~", "").split("\\*");

        String controlNum1 = isaFields1[13];
        String controlNum2 = isaFields2[13];

        assertNotEquals(controlNum1, controlNum2,
                "Control numbers should be unique across consecutive calls");
    }
}
