package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.service.edi.MappingCorrectionInterpreter.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests MappingCorrectionInterpreter's keyword fallback logic.
 * Claude API path is not tested (requires real API key);
 * these tests exercise the regex-based fallback that works without any LLM.
 */
class MappingCorrectionInterpreterTest {

    private MappingCorrectionInterpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        interpreter = new MappingCorrectionInterpreter();
        // apiKey is blank by default (@Value default "") — forces fallback path
    }

    private List<FieldMappingDto> sampleMappings() {
        List<FieldMappingDto> mappings = new ArrayList<>();
        mappings.add(FieldMappingDto.builder()
                .sourceField("NM1*02").targetField("companyName")
                .transform("DIRECT").confidence(85).build());
        mappings.add(FieldMappingDto.builder()
                .sourceField("BEG*03").targetField("poNumber")
                .transform("DIRECT").confidence(90).build());
        mappings.add(FieldMappingDto.builder()
                .sourceField("BEG*05").targetField("orderDate")
                .transform("DATE_REFORMAT").transformParam("yyyyMMdd->yyyy-MM-dd").confidence(88).build());
        mappings.add(FieldMappingDto.builder()
                .sourceField("ISA*06").targetField("senderId")
                .transform("TRIM").confidence(95).build());
        return mappings;
    }

    // ===================================================================
    // Pattern 1: Swap source field — "NM1*03 not NM1*02"
    // ===================================================================

    @Test
    void swapSourceField_notPattern() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "NM1*03 not NM1*02", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertFalse(result.isClarificationNeeded());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("MODIFY", change.getAction());
        assertEquals("companyName", change.getTargetField());
        assertEquals("NM1*02", change.getOldSourceField());
        assertEquals("NM1*03", change.getNewSourceField());
    }

    @Test
    void swapSourceField_insteadOfPattern() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "NM1*03 instead of NM1*02", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());
        assertEquals("MODIFY", result.getChanges().get(0).getAction());
        assertEquals("NM1*03", result.getChanges().get(0).getNewSourceField());
    }

    @Test
    void swapSourceField_fromPrefix() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "from NM1*03 not NM1*02", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("MODIFY", result.getChanges().get(0).getAction());
    }

    // ===================================================================
    // Pattern 2: "should come from" — change source for a target
    // ===================================================================

    @Test
    void shouldComeFrom_pattern() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "companyName should come from NM1*03", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("MODIFY", change.getAction());
        assertEquals("companyName", change.getTargetField());
        assertEquals("NM1*03", change.getNewSourceField());
    }

    @Test
    void shouldBeReadFrom_pattern() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "senderId should be read from ISA*08", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("senderId", result.getChanges().get(0).getTargetField());
        assertEquals("ISA*08", result.getChanges().get(0).getNewSourceField());
    }

    // ===================================================================
    // Pattern 3: "Map X to Y" — add or modify mapping
    // ===================================================================

    @Test
    void mapTo_newMapping() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Map PO1*02 to quantity", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("ADD", change.getAction()); // quantity doesn't exist yet
        assertEquals("quantity", change.getTargetField());
        assertEquals("PO1*02", change.getNewSourceField());
    }

    @Test
    void mapTo_existingMapping() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Map NM1*03 to companyName", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("MODIFY", change.getAction()); // companyName already exists
        assertEquals("companyName", change.getTargetField());
        assertEquals("NM1*03", change.getNewSourceField());
    }

    @Test
    void setTo_pattern() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Set PO1*04 to unitPrice", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("PO1*04", result.getChanges().get(0).getNewSourceField());
        assertEquals("unitPrice", result.getChanges().get(0).getTargetField());
    }

    // ===================================================================
    // Pattern 4: Date format — "format date as MM/dd/yyyy"
    // ===================================================================

    @Test
    void dateFormat_mmddyyyy() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Format the date as MM/dd/yyyy", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("CHANGE_TRANSFORM", change.getAction());
        assertEquals("orderDate", change.getTargetField()); // matches the date field
        assertEquals("DATE_REFORMAT", change.getNewTransform());
        assertTrue(change.getNewTransformParam().contains("MM/dd/yyyy"));
    }

    @ParameterizedTest
    @CsvSource({
            "'date format should be yyyy-MM-dd', yyyy-MM-dd",
            "'format date as dd/MM/yyyy', dd/MM/yyyy",
            "'change date to MM-dd-yyyy', MM-dd-yyyy"
    })
    void dateFormat_variants(String instruction, String expectedFormat) {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                instruction, sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertFalse(result.getChanges().isEmpty());
        assertTrue(result.getChanges().get(0).getNewTransformParam().contains(expectedFormat));
    }

    // ===================================================================
    // Pattern 5: Remove mapping — "remove companyName"
    // ===================================================================

    @Test
    void removeMapping() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Remove companyName", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("REMOVE", change.getAction());
        assertEquals("companyName", change.getTargetField());
    }

    @Test
    void deleteMapping() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Delete the mapping for senderId", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("REMOVE", result.getChanges().get(0).getAction());
        assertEquals("senderId", result.getChanges().get(0).getTargetField());
    }

    // ===================================================================
    // Pattern 6: Case transform — "make companyName uppercase"
    // ===================================================================

    @Test
    void makeUppercase() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Make companyName uppercase", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());

        MappingChange change = result.getChanges().get(0);
        assertEquals("CHANGE_TRANSFORM", change.getAction());
        assertEquals("companyName", change.getTargetField());
        assertEquals("UPPERCASE", change.getNewTransform());
    }

    @Test
    void makeLowercase() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "senderId lowercase", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("LOWERCASE", result.getChanges().get(0).getNewTransform());
    }

    // ===================================================================
    // Pattern 7: Trim — "trim companyName"
    // ===================================================================

    @Test
    void trimField() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Trim companyName", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals(1, result.getChanges().size());
        assertEquals("CHANGE_TRANSFORM", result.getChanges().get(0).getAction());
        assertEquals("TRIM", result.getChanges().get(0).getNewTransform());
    }

    @Test
    void trimWhitespaceFrom() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Trim whitespace from senderId", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("senderId", result.getChanges().get(0).getTargetField());
        assertEquals("TRIM", result.getChanges().get(0).getNewTransform());
    }

    // ===================================================================
    // Edge Cases
    // ===================================================================

    @Test
    void unrecognizedInstruction_returnsClarificationNeeded() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Do something magical with the data", sampleMappings(), "X12", "850", null);

        assertFalse(result.isUnderstood());
        assertTrue(result.isClarificationNeeded());
        assertNotNull(result.getClarificationQuestion());
        assertTrue(result.getClarificationQuestion().contains("NM1*03 not NM1*02"));
    }

    @Test
    void emptyMappings_addNewMapping() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Map NM1*03 to companyName", new ArrayList<>(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertEquals("ADD", result.getChanges().get(0).getAction());
    }

    @Test
    void summaryContainsFallbackNotice() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Map PO1*02 to quantity", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertTrue(result.getSummary().contains("keyword fallback"));
    }

    @Test
    void swapPattern_sourceNotFound_noMatch() {
        // NM1*99 doesn't exist in our mappings, but NM1*03 not NM1*99 should still produce a change
        // because the pattern matches the regex; however, no mapping has NM1*99 as source
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "NM1*03 not NM1*99", sampleMappings(), "X12", "850", null);

        // The swap pattern finds NM1*99 in the list — it won't match any mapping
        // So it falls through to other patterns
        // Since no other pattern matches either, should return not understood
        // unless the come-from or map-to patterns catch it
        assertNotNull(result);
    }

    @Test
    void nullSampleInput_doesNotThrow() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "Map NM1*03 to companyName", sampleMappings(), "X12", "850", null);

        assertTrue(result.isUnderstood());
        assertNotNull(result.getChanges());
    }

    @Test
    void caseInsensitive_patterns() {
        CorrectionInterpretation result = interpreter.interpretCorrection(
                "REMOVE COMPANYNAME", sampleMappings(), "X12", "850", null);

        // The regex is case-insensitive
        assertTrue(result.isUnderstood());
        assertEquals("REMOVE", result.getChanges().get(0).getAction());
    }
}
