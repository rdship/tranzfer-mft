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
 * party role assignments, monetary totals, and null/empty resilience.
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
}
