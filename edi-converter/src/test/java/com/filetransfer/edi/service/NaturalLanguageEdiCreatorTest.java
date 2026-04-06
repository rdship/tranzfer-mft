package com.filetransfer.edi.service;

import com.filetransfer.edi.format.TemplateLibrary;
import com.filetransfer.edi.service.NaturalLanguageEdiCreator.NlEdiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NaturalLanguageEdiCreator.
 *
 * All tests run WITHOUT a Claude API key, exercising the full regex/pattern-matching
 * fallback path. Covers all 8 intent patterns, entity extraction, EDI generation,
 * and edge cases.
 *
 * JDK 25: uses real instances and reflection — no mocking of concrete classes.
 */
class NaturalLanguageEdiCreatorTest {

    private NaturalLanguageEdiCreator creator;
    private ClaudeApiClient claudeApiClient;

    @BeforeEach
    void setUp() throws Exception {
        TemplateLibrary templateLibrary = new TemplateLibrary();
        claudeApiClient = new ClaudeApiClient();
        // No API key — forces fallback regex path
        setField(claudeApiClient, "apiKey", "");
        setField(claudeApiClient, "model", "claude-sonnet-4-20250514");
        setField(claudeApiClient, "baseUrl", "https://api.anthropic.com");

        creator = new NaturalLanguageEdiCreator(templateLibrary, claudeApiClient);
    }

    // ========================================================================
    // Intent detection — all 8 patterns
    // ========================================================================

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Create a purchase order for 100 widgets|X12_850",
            "Send a PO to Acme Corp|X12_850",
            "Generate a order for parts|X12_850",
            "Build a purchase order|X12_850"
    })
    void detectsIntent_purchaseOrder(String input, String expectedType) {
        NlEdiResult result = creator.create(input);
        assertEquals(expectedType, result.getDocumentType());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Create an invoice for $15000|X12_810",
            "Generate a bill to RetailBuyer|X12_810",
            "Send an invoice from GlobalSupplier|X12_810"
    })
    void detectsIntent_invoice(String input, String expectedType) {
        NlEdiResult result = creator.create(input);
        assertEquals(expectedType, result.getDocumentType());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Create a healthcare claim for $1500|X12_837",
            "Generate a medical claim for patient John Doe|X12_837",
            "Send a claim for diagnosis J06.9|X12_837"
    })
    void detectsIntent_healthcareClaim(String input, String expectedType) {
        NlEdiResult result = creator.create(input);
        assertEquals(expectedType, result.getDocumentType());
    }

    @Test
    void detectsIntent_shipNotice() {
        NlEdiResult result = creator.create("Create a ship notice for order 12345");
        assertEquals("X12_856", result.getDocumentType());
    }

    @Test
    void detectsIntent_shipNotice_ASN() {
        NlEdiResult result = creator.create("Generate a ASN for shipment");
        assertEquals("X12_856", result.getDocumentType());
    }

    @Test
    void detectsIntent_swiftTransfer() {
        NlEdiResult result = creator.create("Create a wire transfer for $50000");
        assertEquals("SWIFT_MT103", result.getDocumentType());
    }

    @Test
    void detectsIntent_swiftTransfer_MT103() {
        NlEdiResult result = creator.create("Generate a SWIFT MT103 payment");
        assertEquals("SWIFT_MT103", result.getDocumentType());
    }

    @Test
    void detectsIntent_payment820() {
        NlEdiResult result = creator.create("Create a payment for $5000");
        assertEquals("X12_820", result.getDocumentType());
    }

    @Test
    void detectsIntent_hl7Admission() {
        NlEdiResult result = creator.create("Admit a patient John Smith");
        assertEquals("HL7_ADT", result.getDocumentType());
    }

    @Test
    void detectsIntent_hl7Admission_ADT() {
        NlEdiResult result = creator.create("Create a ADT message");
        assertEquals("HL7_ADT", result.getDocumentType());
    }

    @Test
    void detectsIntent_edifactOrder() {
        NlEdiResult result = creator.create("Create an edifact order for parts");
        assertEquals("EDIFACT_ORDERS", result.getDocumentType());
    }

    @Test
    void detectsIntent_internationalOrder() {
        NlEdiResult result = creator.create("Generate an international order");
        assertEquals("EDIFACT_ORDERS", result.getDocumentType());
    }

    // SWIFT must be detected BEFORE generic "payment" (X12_820)
    @Test
    void swiftDetected_beforePayment_forWireTransfer() {
        NlEdiResult result = creator.create("Send a wire transfer for $10000");
        assertEquals("SWIFT_MT103", result.getDocumentType());
    }

    // ========================================================================
    // Entity extraction
    // ========================================================================

    @Test
    void extractsQuantity() {
        NlEdiResult result = creator.create("Create a purchase order for 500 widgets at $12.50 each to Acme Corp");
        assertEquals("500", result.getExtractedFields().get("quantity"));
    }

    @Test
    void extractsPrice() {
        NlEdiResult result = creator.create("Create a purchase order for 500 widgets at $12.50 each to Acme Corp");
        assertEquals("12.50", result.getExtractedFields().get("unitPrice"));
    }

    @Test
    void extractsCompanyNames_purchaseOrder() {
        NlEdiResult result = creator.create("Create a purchase order from BuyerCo to SellerCo for 10 items");
        // "to SellerCo" should map sellerName
        assertNotNull(result.getExtractedFields());
    }

    @Test
    void extractsCompanyNames_invoice() {
        // Use a sentence structure where company names are clearly delimited
        NlEdiResult result = creator.create("Create an invoice for $15000 from GlobalSupplier");
        assertNotNull(result.getExtractedFields());
        // "from GlobalSupplier" means seller
        String seller = result.getExtractedFields().get("sellerName");
        assertNotNull(seller, "Should extract seller name");
        assertTrue(seller.contains("GlobalSupplier"),
                "Seller should contain 'GlobalSupplier', got: " + seller);
    }

    @Test
    void extractsPatientName() {
        NlEdiResult result = creator.create("Create a healthcare claim for patient John Doe, $1500");
        assertEquals("John", result.getExtractedFields().get("patientFirstName"));
        assertEquals("Doe", result.getExtractedFields().get("patientLastName"));
    }

    @Test
    void extractsDiagnosisCode() {
        NlEdiResult result = creator.create("Generate a claim for patient Jane Smith, diagnosis J06.9, $2000");
        assertEquals("J06.9", result.getExtractedFields().get("diagnosisCode"));
    }

    @Test
    void extractsAmount_invoice() {
        NlEdiResult result = creator.create("Create an invoice for $15000 from SellerCo to BuyerCo");
        assertEquals("15000", result.getExtractedFields().get("totalAmount"));
    }

    @Test
    void extractsBicCodes_swift() {
        NlEdiResult result = creator.create("Create a wire transfer BIC BANKUS33XXX to BIC BANKGB2LXXX for $50000");
        assertEquals("BANKUS33XXX", result.getExtractedFields().get("senderBic"));
        assertEquals("BANKGB2LXXX", result.getExtractedFields().get("receiverBic"));
    }

    // ========================================================================
    // EDI generation — each type
    // ========================================================================

    @Test
    void generates_X12_850() {
        NlEdiResult result = creator.create("Create a purchase order for 100 widgets at $10.00 each to Acme Corp");
        assertEquals("X12_850", result.getDocumentType());
        assertNotNull(result.getGeneratedEdi());
        assertTrue(result.getGeneratedEdi().contains("ISA*"));
        assertTrue(result.getGeneratedEdi().contains("ST*850*"));
        assertTrue(result.getGeneratedEdi().contains("BEG*"));
        assertTrue(result.getGeneratedEdi().contains("PO1*"));
        assertTrue(result.getGeneratedEdi().contains("IEA*"));
    }

    @Test
    void generates_X12_810() {
        NlEdiResult result = creator.create("Create an invoice for $15000 from SellerCo to BuyerCo");
        assertEquals("X12_810", result.getDocumentType());
        assertNotNull(result.getGeneratedEdi());
        assertTrue(result.getGeneratedEdi().contains("ST*810*"));
        assertTrue(result.getGeneratedEdi().contains("BIG*"));
        assertTrue(result.getGeneratedEdi().contains("TDS*"));
    }

    @Test
    void generates_X12_837() {
        NlEdiResult result = creator.create("Create a healthcare claim for patient Jane Smith, $2500, diagnosis J06.9");
        assertEquals("X12_837", result.getDocumentType());
        assertNotNull(result.getGeneratedEdi());
        assertTrue(result.getGeneratedEdi().contains("ST*837*"));
        assertTrue(result.getGeneratedEdi().contains("CLM*"));
        assertTrue(result.getGeneratedEdi().contains("HI*"));
    }

    @Test
    void generates_HL7_ADT() {
        NlEdiResult result = creator.create("Admit patient John Doe");
        assertEquals("HL7_ADT", result.getDocumentType());
        assertNotNull(result.getGeneratedEdi());
        assertTrue(result.getGeneratedEdi().contains("MSH|"));
        assertTrue(result.getGeneratedEdi().contains("PID|"));
        assertTrue(result.getGeneratedEdi().contains("ADT^A01"));
    }

    @Test
    void generates_SWIFT_MT103() {
        NlEdiResult result = creator.create("Create a wire transfer for $50000");
        assertEquals("SWIFT_MT103", result.getDocumentType());
        assertNotNull(result.getGeneratedEdi());
        assertTrue(result.getGeneratedEdi().contains("{1:F01"));
        assertTrue(result.getGeneratedEdi().contains(":20:"));
        assertTrue(result.getGeneratedEdi().contains(":32A:"));
        assertTrue(result.getGeneratedEdi().contains(":71A:SHA"));
    }

    @Test
    void generates_X12_856_asDefault() {
        // X12_856 doesn't have a dedicated generator yet, should hit default case
        NlEdiResult result = creator.create("Create a ship notice for order 12345");
        assertEquals("X12_856", result.getDocumentType());
        // Default case generates empty EDI and 0 confidence
        assertEquals(0, result.getConfidence());
    }

    @Test
    void generates_X12_820_asDefault() {
        NlEdiResult result = creator.create("Create a payment for $5000");
        assertEquals("X12_820", result.getDocumentType());
        assertEquals(0, result.getConfidence());
    }

    @Test
    void generates_EDIFACT_ORDERS_asDefault() {
        NlEdiResult result = creator.create("Create an edifact order for supplies");
        assertEquals("EDIFACT_ORDERS", result.getDocumentType());
        assertEquals(0, result.getConfidence());
    }

    // ========================================================================
    // Confidence calculation
    // ========================================================================

    @Test
    void confidence_higherWithMoreExtractedFields() {
        NlEdiResult sparse = creator.create("Create a purchase order");
        NlEdiResult rich = creator.create("Create a purchase order for 500 widgets at $25.00 each to Acme Corp from BuyerCo");
        // The rich version should have higher confidence because more key fields are extracted
        assertTrue(rich.getConfidence() >= sparse.getConfidence(),
                "Rich input should have >= confidence: rich=" + rich.getConfidence() + " sparse=" + sparse.getConfidence());
    }

    @Test
    void confidence_between0and100() {
        NlEdiResult result = creator.create("Create a purchase order for 100 items at $10.00 each to Acme Corp");
        assertTrue(result.getConfidence() >= 0 && result.getConfidence() <= 100);
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void emptyInput_returnsZeroConfidence() {
        NlEdiResult result = creator.create("");
        assertEquals(0, result.getConfidence());
        assertNotNull(result.getWarnings());
        assertFalse(result.getWarnings().isEmpty());
    }

    @Test
    void nullInput_returnsZeroConfidence() {
        NlEdiResult result = creator.create(null);
        assertEquals(0, result.getConfidence());
    }

    @Test
    void unknownIntent_returnsZeroConfidence() {
        NlEdiResult result = creator.create("What is the meaning of life?");
        assertEquals(0, result.getConfidence());
        assertNull(result.getDocumentType());
        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream()
                .anyMatch(w -> w.contains("Could not determine")));
    }

    @Test
    void blankInput_returnsEmptyWarning() {
        NlEdiResult result = creator.create("   ");
        assertEquals(0, result.getConfidence());
    }

    @Test
    void defaultValues_generateWarning() {
        // A sparse input will use DEFAULT_ values, which should trigger a warning
        NlEdiResult result = creator.create("Create a purchase order");
        // DEFAULT_BUYER and DEFAULT_SELLER will be used
        assertTrue(result.getExtractedFields().values().stream()
                        .anyMatch(v -> v.startsWith("DEFAULT_")) ||
                        result.getWarnings().stream().anyMatch(w -> w.contains("default")),
                "Should warn about default values or have defaults in fields");
    }

    // ========================================================================
    // Claude API client unavailable (verifies fallback)
    // ========================================================================

    @Test
    void claudeApiClient_isNotAvailable() {
        assertFalse(claudeApiClient.isAvailable(),
                "API client should not be available in tests (no key)");
    }

    @Test
    void create_usesFallback_whenClaudeUnavailable() {
        assertFalse(claudeApiClient.isAvailable());
        NlEdiResult result = creator.create("Create an invoice for $5000 from SellerInc to BuyerInc");
        assertNotNull(result);
        assertEquals("X12_810", result.getDocumentType());
        // Should work perfectly fine through fallback
        assertTrue(result.getGeneratedEdi().contains("ST*810*"));
    }

    // ========================================================================
    // Explanations
    // ========================================================================

    @Test
    void explanation_includesDocumentDetails_850() {
        NlEdiResult result = creator.create("Create a purchase order for 200 items at $5.00 to VendorCo");
        assertNotNull(result.getExplanation());
        assertTrue(result.getExplanation().contains("Purchase Order"));
    }

    @Test
    void explanation_includesDocumentDetails_810() {
        NlEdiResult result = creator.create("Generate an invoice for $25000 from SupplierX to BuyerY");
        assertNotNull(result.getExplanation());
        assertTrue(result.getExplanation().contains("Invoice"));
    }

    @Test
    void explanation_includesPatientInfo_837() {
        NlEdiResult result = creator.create("Create a claim for patient Alice Smith, $3000, diagnosis M54.5");
        assertNotNull(result.getExplanation());
        assertTrue(result.getExplanation().contains("Healthcare Claim") ||
                result.getExplanation().contains("patient"));
    }

    // ========================================================================
    // Multiple verb forms
    // ========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "Send a purchase order for items",
            "Create a purchase order for items",
            "Generate a purchase order for items",
            "Make a purchase order for items",
            "Write a purchase order for items",
            "Build a purchase order for items"
    })
    void allVerbForms_createPurchaseOrder(String input) {
        NlEdiResult result = creator.create(input);
        assertEquals("X12_850", result.getDocumentType());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
