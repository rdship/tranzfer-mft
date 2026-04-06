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
}
