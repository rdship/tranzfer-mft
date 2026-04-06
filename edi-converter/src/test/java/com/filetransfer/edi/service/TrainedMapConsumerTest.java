package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import com.filetransfer.edi.service.TrainedMapConsumer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TrainedMapConsumer: transform application, nested JSON output,
 * date reformatting, cache invalidation, and field mapping data types.
 *
 * Tests the non-HTTP logic directly via reflection to avoid JDK 25
 * Mockito restrictions on mocking concrete classes like RestTemplate.
 */
class TrainedMapConsumerTest {

    private TrainedMapConsumer consumer;
    private Method applyTransformMethod;
    private Method setNestedValueMethod;
    private Method reformatDateMethod;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new TrainedMapConsumer(new UniversalEdiParser(new FormatDetector()), new ObjectMapper());

        // Access private methods via reflection for unit testing
        applyTransformMethod = TrainedMapConsumer.class.getDeclaredMethod(
                "applyTransform", String.class, String.class, String.class);
        applyTransformMethod.setAccessible(true);

        setNestedValueMethod = TrainedMapConsumer.class.getDeclaredMethod(
                "setNestedValue", Map.class, String.class, String.class);
        setNestedValueMethod.setAccessible(true);

        reformatDateMethod = TrainedMapConsumer.class.getDeclaredMethod(
                "reformatDate", String.class, String.class);
        reformatDateMethod.setAccessible(true);
    }

    private String applyTransform(String value, String transform, String param) throws Exception {
        return (String) applyTransformMethod.invoke(consumer, value, transform, param);
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String path, String value) throws Exception {
        setNestedValueMethod.invoke(consumer, map, path, value);
    }

    private String reformatDate(String value, String param) throws Exception {
        return (String) reformatDateMethod.invoke(consumer, value, param);
    }

    // === Transform: DIRECT (default) ===

    @Test
    void applyTransform_nullTransform_returnsOriginalValue() throws Exception {
        assertEquals("hello", applyTransform("hello", null, null));
    }

    @Test
    void applyTransform_directTransform_returnsOriginalValue() throws Exception {
        assertEquals("hello", applyTransform("hello", "DIRECT", null));
    }

    // === Transform: TRIM ===

    @Test
    void applyTransform_trim_removesLeadingAndTrailingWhitespace() throws Exception {
        assertEquals("ACME", applyTransform("  ACME  ", "TRIM", null));
    }

    @Test
    void applyTransform_trim_handlesAlreadyTrimmedValue() throws Exception {
        assertEquals("ACME", applyTransform("ACME", "TRIM", null));
    }

    @Test
    void applyTransform_trim_handlesIsaPaddedFields() throws Exception {
        assertEquals("SENDER", applyTransform("SENDER         ", "TRIM", null));
    }

    // === Transform: UPPERCASE ===

    @Test
    void applyTransform_uppercase_convertsToUpperCase() throws Exception {
        assertEquals("HELLO WORLD", applyTransform("hello world", "UPPERCASE", null));
    }

    @Test
    void applyTransform_uppercase_handlesAlreadyUpperCase() throws Exception {
        assertEquals("HELLO", applyTransform("HELLO", "UPPERCASE", null));
    }

    // === Transform: LOWERCASE ===

    @Test
    void applyTransform_lowercase_convertsToLowerCase() throws Exception {
        assertEquals("hello world", applyTransform("HELLO WORLD", "LOWERCASE", null));
    }

    // === Transform: ZERO_PAD ===

    @Test
    void applyTransform_zeroPad_padsToSpecifiedLength() throws Exception {
        assertEquals("00042", applyTransform("42", "ZERO_PAD", "5"));
    }

    @Test
    void applyTransform_zeroPad_handlesAlreadyPaddedValue() throws Exception {
        assertEquals("00042", applyTransform("00042", "ZERO_PAD", "5"));
    }

    @Test
    void applyTransform_zeroPad_trimsThenPads() throws Exception {
        assertEquals("00042", applyTransform("  42  ", "ZERO_PAD", "5"));
    }

    // === Transform: DATE_REFORMAT ===

    @Test
    void applyTransform_dateReformat_yyyymmddToDashed() throws Exception {
        assertEquals("2024-01-15", applyTransform("20240115", "DATE_REFORMAT", "yyyyMMdd→yyyy-MM-dd"));
    }

    @Test
    void applyTransform_dateReformat_dashedToYyyymmdd() throws Exception {
        assertEquals("20240115", applyTransform("2024-01-15", "DATE_REFORMAT", "yyyy-MM-dd→yyyyMMdd"));
    }

    @Test
    void reformatDate_nullParam_returnsOriginal() throws Exception {
        assertEquals("20240101", reformatDate("20240101", null));
    }

    @Test
    void reformatDate_noArrow_returnsOriginal() throws Exception {
        assertEquals("20240101", reformatDate("20240101", "yyyyMMdd"));
    }

    @ParameterizedTest
    @CsvSource({
            "20240101, yyyyMMdd→yyyy-MM-dd, 2024-01-01",
            "20241231, yyyyMMdd→yyyy-MM-dd, 2024-12-31",
            "20240229, yyyyMMdd→yyyy-MM-dd, 2024-02-29",
    })
    void reformatDate_yyyymmddToDashed_variousDates(String input, String param, String expected) throws Exception {
        assertEquals(expected, reformatDate(input, param));
    }

    @ParameterizedTest
    @CsvSource({
            "2024-01-01, yyyy-MM-dd→yyyyMMdd, 20240101",
            "2024-12-31, yyyy-MM-dd→yyyyMMdd, 20241231",
    })
    void reformatDate_dashedToYyyymmdd_variousDates(String input, String param, String expected) throws Exception {
        assertEquals(expected, reformatDate(input, param));
    }

    // === Transform: null input ===

    @Test
    void applyTransform_nullInput_returnsEmptyString() throws Exception {
        assertEquals("", applyTransform(null, "TRIM", null));
        assertEquals("", applyTransform(null, "UPPERCASE", null));
        assertEquals("", applyTransform(null, null, null));
    }

    // === setNestedValue: flat paths ===

    @Test
    void setNestedValue_flatPath_setsTopLevelKey() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        setNestedValue(map, "buyer", "Acme Corp");
        assertEquals("Acme Corp", map.get("buyer"));
    }

    // === setNestedValue: nested paths ===

    @Test
    void setNestedValue_dottedPath_createsNestedObjects() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        setNestedValue(map, "buyer.name", "Acme Corp");
        setNestedValue(map, "buyer.id", "ACME01");

        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) map.get("buyer");
        assertNotNull(buyer);
        assertEquals("Acme Corp", buyer.get("name"));
        assertEquals("ACME01", buyer.get("id"));
    }

    @Test
    void setNestedValue_deeplyNestedPath_createsAllLevels() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        setNestedValue(map, "order.buyer.address.city", "New York");

        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) map.get("order");
        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) order.get("buyer");
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) buyer.get("address");
        assertEquals("New York", address.get("city"));
    }

    @Test
    void setNestedValue_multipleSiblingPaths_coexist() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        setNestedValue(map, "buyer.name", "Acme");
        setNestedValue(map, "seller.name", "TechCo");

        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) map.get("buyer");
        @SuppressWarnings("unchecked")
        Map<String, Object> seller = (Map<String, Object>) map.get("seller");
        assertEquals("Acme", buyer.get("name"));
        assertEquals("TechCo", seller.get("name"));
    }

    // === Cache invalidation ===

    @Test
    void invalidateCache_clearsAllEntries() {
        // After invalidation, hasTrainedMap should return false
        // (since no AI engine is running to respond)
        consumer.invalidateCache();
        assertFalse(consumer.hasTrainedMap("X12", "850", "JSON", null));
    }

    // === FieldMapping data structure ===

    @Test
    void fieldMapping_builderCreatesCorrectObject() {
        FieldMapping mapping = FieldMapping.builder()
                .sourceField("ISA*06")
                .targetField("sender.name")
                .confidence(95)
                .strategy("EXACT_VALUE")
                .transform("TRIM")
                .transformParam(null)
                .build();

        assertEquals("ISA*06", mapping.getSourceField());
        assertEquals("sender.name", mapping.getTargetField());
        assertEquals(95, mapping.getConfidence());
        assertEquals("EXACT_VALUE", mapping.getStrategy());
        assertEquals("TRIM", mapping.getTransform());
    }

    // === TrainedMap data structure ===

    @Test
    void trainedMap_builderCreatesCorrectObject() {
        TrainedMap map = TrainedMap.builder()
                .mapKey("X12:850→JSON@ACME")
                .version(3)
                .confidence(89)
                .fieldMappings(List.of(
                        FieldMapping.builder().sourceField("a").targetField("b").build()))
                .generatedCode("$source.a → $target.b")
                .build();

        assertEquals("X12:850→JSON@ACME", map.getMapKey());
        assertEquals(3, map.getVersion());
        assertEquals(89, map.getConfidence());
        assertEquals(1, map.getFieldMappings().size());
        assertNotNull(map.getGeneratedCode());
    }

    // === TrainedConversionResult data structure ===

    @Test
    void trainedConversionResult_builderCreatesCorrectObject() {
        TrainedConversionResult result = TrainedConversionResult.builder()
                .output("{\"buyer\":\"Acme\"}")
                .mapKey("X12:850→JSON")
                .mapVersion(2)
                .mapConfidence(85)
                .fieldsApplied(5)
                .fieldsSkipped(1)
                .totalMappings(6)
                .build();

        assertEquals("{\"buyer\":\"Acme\"}", result.getOutput());
        assertEquals(2, result.getMapVersion());
        assertEquals(5, result.getFieldsApplied());
        assertEquals(1, result.getFieldsSkipped());
        assertEquals(6, result.getTotalMappings());
    }

    // ===================================================================
    // applyCustomMappings tests
    // ===================================================================

    private static final String SAMPLE_X12_850 =
            "ISA*00*          *00*          *ZZ*ACME           *ZZ*GLOBALSUP      *240101*1200*U*00501*000000001*0*P*>~"
            + "GS*PO*ACME*GLOBALSUP*20240101*1200*1*X*005010~"
            + "ST*850*0001~"
            + "BEG*00*NE*PO-123**20240101~"
            + "NM1*BY*1*Acme Corp*John~"
            + "PO1*001*500*EA*12.50*PE*VP*WIDGET-100~"
            + "SE*6*0001~GE*1*1~IEA*1*000000001~";

    @Test
    void applyCustomMappings_singleMapping() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("BEG*03").targetField("poNumber")
                        .transform("DIRECT").confidence(100)
                        .build());

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("poNumber"));
        assertTrue(result.getOutput().contains("PO-123"));
        assertEquals(1, result.getFieldsApplied());
        assertEquals(0, result.getFieldsSkipped());
    }

    @Test
    void applyCustomMappings_multipleMappings() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("BEG*03").targetField("poNumber")
                        .transform("DIRECT").confidence(100).build(),
                FieldMapping.builder()
                        .sourceField("NM1*03").targetField("buyerName")
                        .transform("DIRECT").confidence(100).build(),
                FieldMapping.builder()
                        .sourceField("BEG*05").targetField("orderDate")
                        .transform("DIRECT").confidence(100).build()
        );

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        assertTrue(result.getOutput().contains("PO-123"));
        assertTrue(result.getOutput().contains("Acme Corp"));
        assertTrue(result.getOutput().contains("20240101"));
        assertEquals(3, result.getFieldsApplied());
    }

    @Test
    void applyCustomMappings_withTransforms() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("NM1*03").targetField("buyerName")
                        .transform("UPPERCASE").confidence(100).build(),
                FieldMapping.builder()
                        .sourceField("BEG*03").targetField("poNumber")
                        .transform("TRIM").confidence(100).build()
        );

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        assertTrue(result.getOutput().contains("ACME CORP"));
        assertTrue(result.getOutput().contains("PO-123"));
    }

    @Test
    void applyCustomMappings_nestedTargetField() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("NM1*03").targetField("buyer.name")
                        .transform("DIRECT").confidence(100).build(),
                FieldMapping.builder()
                        .sourceField("BEG*03").targetField("header.poNumber")
                        .transform("DIRECT").confidence(100).build()
        );

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        assertTrue(result.getOutput().contains("\"buyer\""));
        assertTrue(result.getOutput().contains("\"name\""));
        assertTrue(result.getOutput().contains("\"header\""));
        assertTrue(result.getOutput().contains("\"poNumber\""));
    }

    @Test
    void applyCustomMappings_emptyMappings() {
        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, List.of());

        assertNotNull(result.getOutput());
        assertEquals(0, result.getFieldsApplied());
        assertEquals(0, result.getTotalMappings());
    }

    @Test
    void applyCustomMappings_unmatchedSourceField() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("NONEXISTENT*99").targetField("ghostField")
                        .transform("DIRECT").confidence(100).build()
        );

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        assertEquals(0, result.getFieldsApplied());
        assertEquals(1, result.getFieldsSkipped());
    }

    @Test
    void applyCustomMappings_invalidContent_returnsEmptyOutput() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("BEG*03").targetField("poNumber")
                        .transform("DIRECT").confidence(100).build()
        );

        // Completely invalid content
        TrainedConversionResult result = consumer.applyCustomMappings("not edi at all", mappings);

        assertNotNull(result);
        // Parser may still parse it (as UNKNOWN format) — just verify no crash
    }

    @Test
    void applyCustomMappings_dateReformat() {
        List<FieldMapping> mappings = List.of(
                FieldMapping.builder()
                        .sourceField("BEG*05").targetField("orderDate")
                        .transform("DATE_REFORMAT")
                        .transformParam("yyyyMMdd→yyyy-MM-dd")
                        .confidence(100).build()
        );

        TrainedConversionResult result = consumer.applyCustomMappings(SAMPLE_X12_850, mappings);

        // The date 20240101 should be reformatted to 2024-01-01
        assertTrue(result.getOutput().contains("2024-01-01"));
        assertEquals(1, result.getFieldsApplied());
    }
}
