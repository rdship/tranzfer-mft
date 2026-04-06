package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.service.BusinessRuleEngine.RuleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BusinessRuleEngine covering X12, EDIFACT, and HL7
 * format-level and transaction-type-specific rules.
 */
class BusinessRuleEngineTest {

    private BusinessRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BusinessRuleEngine();
    }

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

    // ============================================================
    // X12-001: ISA/IEA Control Number Match
    // ============================================================

    @Test
    void x12_001_controlNumbersMatch_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("IEA", "1","000000001"));
        List<RuleResult> results = engine.evaluate(d);
        RuleResult r = findRule(results, "X12-001");
        assertNotNull(r);
        assertTrue(r.isPassed(), "Expected X12-001 to pass when ISA13=IEA02");
    }

    @Test
    void x12_001_controlNumbersMismatch_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("IEA", "1","000000099"));
        List<RuleResult> results = engine.evaluate(d);
        RuleResult r = findRule(results, "X12-001");
        assertNotNull(r);
        assertFalse(r.isPassed(), "Expected X12-001 to fail when ISA13 != IEA02");
        assertTrue(r.getMessage().contains("ISA13"));
        assertTrue(r.getMessage().contains("IEA02"));
    }

    // ============================================================
    // X12-002: GS/GE Control Number Match
    // ============================================================

    @Test
    void x12_002_gsGeMatch_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("GE", "1","1"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-002");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_002_gsGeMismatch_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("GS", "PO","SENDER","RECEIVER","20240101","1200","1","X","005010"),
                seg("GE", "1","99"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-002");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // X12-003: ST/SE Control Number Match
    // ============================================================

    @Test
    void x12_003_stSeMatch_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("ST", "850","0001"),
                seg("SE", "2","0001"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-003");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_003_stSeMismatch_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("ST", "850","0001"),
                seg("SE", "2","9999"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-003");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // X12-005: Valid Date Fields
    // ============================================================

    @Test
    void x12_005_validDates_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-005");
        assertNotNull(r);
        assertTrue(r.isPassed(), "Expected X12-005 to pass with valid ISA date 240101");
    }

    @Test
    void x12_005_invalidIsaDate_fails() {
        // ISA09 = "991399" — month 13 is invalid
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","991399","1200","U","00501","000000001","0","P"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-005");
        assertNotNull(r);
        assertFalse(r.isPassed(), "Expected X12-005 to fail with invalid ISA date");
        assertTrue(r.getMessage().contains("ISA09"));
    }

    @Test
    void x12_005_invalidDtpDate_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("DTP", "472","D8","20241399"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-005");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("DTP03"));
    }

    // ============================================================
    // X12-006: NM1 Name Completeness
    // ============================================================

    @Test
    void x12_006_personMissingFirstName_fails() {
        // Entity type = 1 (person), last name present, first name empty
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("NM1", "IL","1","DOE",""),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-006");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("missing first name"));
    }

    @Test
    void x12_006_orgMissingName_fails() {
        // Entity type = 2 (org), organization name (element 2) is empty
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("NM1", "IL","2",""),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "X12-006");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("org missing organization name"));
    }

    // ============================================================
    // 837-001: CLM Amount Positive
    // ============================================================

    @Test
    void x12_837_001_positiveAmount_passes() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLM", "CLAIM001","1500.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_837_001_zeroAmount_fails() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLM", "CLAIM001","0"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertEquals("CLM", r.getAffectedSegment());
    }

    @Test
    void x12_837_001_negativeAmount_fails() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLM", "CLAIM001","-500.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // 837-002: NPI Format Validation
    // ============================================================

    @Test
    void x12_837_002_validNpi_passes() {
        // NM1 with qualifier XX at element 7 (index 7), code at element 8 (index 8)
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("NM1", "85","1","DOE","JOHN","","","","XX","1234567890"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-002");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_837_002_invalidNpi_fails() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("NM1", "85","1","DOE","JOHN","","","","XX","12345"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-002");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("not 10 digits"));
    }

    // ============================================================
    // 837-003: Service Line Required
    // ============================================================

    @Test
    void x12_837_003_sv1Present_passes() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("SV1", "HC:99213","50.00","UN","1"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-003");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_837_003_noServiceLine_fails() {
        EdiDocument d = doc("X12", "837",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLM", "CLAIM001","1500.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "837-003");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("No SV1/SV2"));
    }

    // ============================================================
    // 850-001: PO1 Quantity Positive
    // ============================================================

    @Test
    void x12_850_001_positiveQuantity_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("PO1", "1","10","EA","25.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_850_001_zeroQuantity_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("PO1", "1","0","EA","25.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // 850-002: BEG Segment Required
    // ============================================================

    @Test
    void x12_850_002_begPresent_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("BEG", "00","NE","PO-12345","","20240101"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-002");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_850_002_begMissing_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("PO1", "1","10","EA","25.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-002");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Missing BEG"));
    }

    // ============================================================
    // 850-003: CTT Count Matches PO1 Count
    // ============================================================

    @Test
    void x12_850_003_cttMatchesPo1Count_passes() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("PO1", "1","10","EA","25.00"),
                seg("PO1", "2","5","EA","10.00"),
                seg("CTT", "2"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-003");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_850_003_cttMismatchesPo1Count_fails() {
        EdiDocument d = doc("X12", "850",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("PO1", "1","10","EA","25.00"),
                seg("PO1", "2","5","EA","10.00"),
                seg("CTT", "5"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "850-003");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("CTT01=5"));
    }

    // ============================================================
    // 835-001: Payment <= Charge
    // ============================================================

    @Test
    void x12_835_001_paymentLessThanCharge_passes() {
        EdiDocument d = doc("X12", "835",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLP", "CLM001","1","1000.00","800.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "835-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_835_001_paymentExceedsCharge_fails() {
        EdiDocument d = doc("X12", "835",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("CLP", "CLM001","1","500.00","900.00"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "835-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("payment"));
    }

    // ============================================================
    // 810-001: Invoice Total Reconciliation
    // ============================================================

    @Test
    void x12_810_001_tdsMatchesIt1Sum_passes() {
        // IT1: qty=2, price=50 -> 100. TDS in cents = 10000
        EdiDocument d = doc("X12", "810",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("IT1", "1","2","EA","50.00"),
                seg("TDS", "10000"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "810-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void x12_810_001_tdsMismatchesIt1Sum_fails() {
        // IT1: qty=2, price=50 -> 100. TDS in cents = 50000 (=$500, should be $100)
        EdiDocument d = doc("X12", "810",
                seg("ISA", "00","","00","","ZZ","SENDER","ZZ","RECEIVER","240101","1200","U","00501","000000001","0","P"),
                seg("IT1", "1","2","EA","50.00"),
                seg("TDS", "50000"),
                seg("IEA", "1","000000001"));
        RuleResult r = findRule(engine.evaluate(d), "810-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("TDS"));
    }

    // ============================================================
    // EDIFACT-001: UNB/UNZ Reference Match
    // ============================================================

    @Test
    void edifact_001_referencesMatch_passes() {
        EdiDocument d = doc("EDIFACT", "ORDERS",
                seg("UNB", "UNOC:3","SENDER:14","RECEIVER:14","240101:1200","REF001"),
                seg("UNZ", "1","REF001"));
        RuleResult r = findRule(engine.evaluate(d), "EDIFACT-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void edifact_001_referencesMismatch_fails() {
        EdiDocument d = doc("EDIFACT", "ORDERS",
                seg("UNB", "UNOC:3","SENDER:14","RECEIVER:14","240101:1200","REF001"),
                seg("UNZ", "1","REF999"));
        RuleResult r = findRule(engine.evaluate(d), "EDIFACT-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // HL7-001: MSH First Segment
    // ============================================================

    @Test
    void hl7_001_mshFirst_passes() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("MSH", "^~\\&","SENDING","FAC"),
                seg("PID", "1","","PAT001"));
        RuleResult r = findRule(engine.evaluate(d), "HL7-001");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void hl7_001_mshNotFirst_fails() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("EVN", "A01"),
                seg("MSH", "^~\\&","SENDING","FAC"));
        RuleResult r = findRule(engine.evaluate(d), "HL7-001");
        assertNotNull(r);
        assertFalse(r.isPassed());
    }

    // ============================================================
    // HL7-002: PID Required for ADT
    // ============================================================

    @Test
    void hl7_002_adtWithPid_passes() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("MSH", "^~\\&","SENDING","FAC"),
                seg("PID", "1","","PAT001"));
        RuleResult r = findRule(engine.evaluate(d), "HL7-002");
        assertNotNull(r);
        assertTrue(r.isPassed());
    }

    @Test
    void hl7_002_adtWithoutPid_fails() {
        EdiDocument d = doc("HL7", "ADT_A01",
                seg("MSH", "^~\\&","SENDING","FAC"),
                seg("EVN", "A01"));
        RuleResult r = findRule(engine.evaluate(d), "HL7-002");
        assertNotNull(r);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Missing PID"));
    }

    // ============================================================
    // Total rule count
    // ============================================================

    @Test
    void totalRuleCount_isCorrect() {
        // Format rules: X12=6, EDIFACT=3, HL7=2 => 11
        // Txn rules: 837=3, 850=3, 835=1, 810=1 => 8
        // Total = 19
        assertEquals(19, engine.getTotalRuleCount());
    }

    @Test
    void getRegisteredRuleIds_x12_containsExpected() {
        List<String> ids = engine.getRegisteredRuleIds("X12");
        assertTrue(ids.contains("X12-001"));
        assertTrue(ids.contains("X12-002"));
        assertTrue(ids.contains("X12-003"));
        assertTrue(ids.contains("X12-004"));
        assertTrue(ids.contains("X12-005"));
        assertTrue(ids.contains("X12-006"));
        assertEquals(6, ids.size());
    }

    @Test
    void getRegisteredRuleIds_hl7_containsExpected() {
        List<String> ids = engine.getRegisteredRuleIds("HL7");
        assertTrue(ids.contains("HL7-001"));
        assertTrue(ids.contains("HL7-002"));
        assertEquals(2, ids.size());
    }

    @Test
    void evaluate_unknownFormat_returnsEmptyResults() {
        EdiDocument d = doc("UNKNOWN", "SOME_TYPE",
                seg("FOO", "bar"));
        List<RuleResult> results = engine.evaluate(d);
        assertTrue(results.isEmpty());
    }

    // ============================================================
    // Helper
    // ============================================================

    private RuleResult findRule(List<RuleResult> results, String ruleId) {
        return results.stream().filter(r -> ruleId.equals(r.getRuleId())).findFirst().orElse(null);
    }
}
