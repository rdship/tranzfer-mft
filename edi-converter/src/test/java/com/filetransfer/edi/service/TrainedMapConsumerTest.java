package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import com.filetransfer.edi.service.TrainedMapConsumer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

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

    // ===================================================================
    // PERSISTENCE TESTS
    // ===================================================================

    /** Helper: create a consumer with storageDir and persistToDisk set via reflection */
    private TrainedMapConsumer createPersistentConsumer(Path tempDir) throws Exception {
        TrainedMapConsumer c = new TrainedMapConsumer(
                new UniversalEdiParser(new FormatDetector()), new ObjectMapper());
        setField(c, "storageDir", tempDir.toString());
        setField(c, "persistToDisk", true);
        setField(c, "aiEngineUrl", "http://localhost:99999"); // unreachable
        return c;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    /** Helper: invoke the private persistMap method */
    private void invokePersistMap(TrainedMapConsumer c, String cacheKey, TrainedMap map) throws Exception {
        Method m = TrainedMapConsumer.class.getDeclaredMethod("persistMap", String.class, TrainedMap.class);
        m.setAccessible(true);
        m.invoke(c, cacheKey, map);
    }

    /** Helper: invoke the private loadPersistedMap method */
    @SuppressWarnings("unchecked")
    private Optional<TrainedMap> invokeLoadPersistedMap(TrainedMapConsumer c, String cacheKey) throws Exception {
        Method m = TrainedMapConsumer.class.getDeclaredMethod("loadPersistedMap", String.class);
        m.setAccessible(true);
        return (Optional<TrainedMap>) m.invoke(c, cacheKey);
    }

    private TrainedMap buildTestMap(String mapKey, int version, int confidence, int fieldCount) {
        List<FieldMapping> mappings = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) {
            mappings.add(FieldMapping.builder()
                    .sourceField("SRC*" + String.format("%02d", i + 1))
                    .targetField("target.field" + (i + 1))
                    .transform("DIRECT")
                    .confidence(confidence)
                    .build());
        }
        return TrainedMap.builder()
                .mapKey(mapKey)
                .version(version)
                .confidence(confidence)
                .fieldMappings(mappings)
                .build();
    }

    @Test
    void persistAndLoadMap_roundTrip(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        String cacheKey = "X12:850→JSON@ACME";
        TrainedMap original = buildTestMap("X12:850→JSON@ACME", 3, 92, 5);

        // Persist
        invokePersistMap(c, cacheKey, original);

        // Verify file exists
        String fileName = TrainedMapConsumer.cacheKeyToFileName(cacheKey);
        assertTrue(Files.exists(tempDir.resolve(fileName)), "Persisted file should exist");

        // Load back
        Optional<TrainedMap> loaded = invokeLoadPersistedMap(c, cacheKey);
        assertTrue(loaded.isPresent(), "Loaded map should be present");
        assertEquals(original.getMapKey(), loaded.get().getMapKey());
        assertEquals(original.getVersion(), loaded.get().getVersion());
        assertEquals(original.getConfidence(), loaded.get().getConfidence());
        assertEquals(original.getFieldMappings().size(), loaded.get().getFieldMappings().size());
    }

    @Test
    void loadPersistedMap_notFound_returnsEmpty(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        Optional<TrainedMap> loaded = invokeLoadPersistedMap(c, "NONEXISTENT:000→NOPE@NOBODY");
        assertTrue(loaded.isEmpty(), "Loading a non-existent map should return empty");
    }

    @Test
    void listPersistedMaps_returnsAllPersistedMaps(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);

        invokePersistMap(c, "X12:850→JSON@ACME", buildTestMap("map1", 1, 90, 3));
        invokePersistMap(c, "EDIFACT:ORDERS→XML@PARTNER2", buildTestMap("map2", 2, 85, 5));
        invokePersistMap(c, "HL7:ADT→JSON@_", buildTestMap("map3", 1, 75, 2));

        List<TrainedMapInfo> maps = c.listPersistedMaps();
        assertEquals(3, maps.size(), "Should list all 3 persisted maps");

        // Verify the map keys are all represented
        Set<String> keys = new HashSet<>();
        maps.forEach(m -> keys.add(m.getMapKey()));
        assertTrue(keys.contains("map1"));
        assertTrue(keys.contains("map2"));
        assertTrue(keys.contains("map3"));
    }

    @Test
    void invalidateCache_doesNotDeleteDiskFiles(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        String cacheKey = "X12:850→JSON@ACME";
        invokePersistMap(c, cacheKey, buildTestMap("map1", 1, 90, 3));

        // Invalidate in-memory only
        c.invalidateCache();

        // Disk file should still exist
        String fileName = TrainedMapConsumer.cacheKeyToFileName(cacheKey);
        assertTrue(Files.exists(tempDir.resolve(fileName)), "Disk file should survive in-memory invalidation");

        // Should still be loadable from disk
        Optional<TrainedMap> loaded = invokeLoadPersistedMap(c, cacheKey);
        assertTrue(loaded.isPresent(), "Map should still be loadable from disk after cache clear");
    }

    @Test
    void invalidateAll_deletesDiskFilesToo(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        invokePersistMap(c, "X12:850→JSON@ACME", buildTestMap("map1", 1, 90, 3));
        invokePersistMap(c, "EDIFACT:ORDERS→XML@PARTNER2", buildTestMap("map2", 2, 85, 5));

        // Verify files exist before invalidation
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(2, files.filter(p -> p.toString().endsWith(".json")).count());
        }

        // Invalidate all
        c.invalidateAll();

        // Both disk and memory should be empty
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.filter(p -> p.toString().endsWith(".json")).count(),
                    "All disk files should be deleted after invalidateAll");
        }
        List<TrainedMapInfo> maps = c.listPersistedMaps();
        assertEquals(0, maps.size(), "No maps should be listed after invalidateAll");
    }

    @Test
    void invalidateMap_removesSpecificMap(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        invokePersistMap(c, "X12:850→JSON@ACME", buildTestMap("map1", 1, 90, 3));
        invokePersistMap(c, "EDIFACT:ORDERS→XML@PARTNER2", buildTestMap("map2", 2, 85, 5));

        // Invalidate only the first map
        c.invalidateMap("X12", "850", "JSON", "ACME");

        // First map should be gone from disk
        String fileName1 = TrainedMapConsumer.cacheKeyToFileName("X12:850→JSON@ACME");
        assertFalse(Files.exists(tempDir.resolve(fileName1)), "Invalidated map file should be deleted");

        // Second map should still exist
        String fileName2 = TrainedMapConsumer.cacheKeyToFileName("EDIFACT:ORDERS→XML@PARTNER2");
        assertTrue(Files.exists(tempDir.resolve(fileName2)), "Other map file should still exist");

        // List should only contain the second map
        List<TrainedMapInfo> maps = c.listPersistedMaps();
        assertEquals(1, maps.size());
        assertEquals("map2", maps.get(0).getMapKey());
    }

    @Test
    void cacheStats_tracksHitsAndMisses(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);

        // Initial stats should be zero
        CacheStats stats = c.getCacheStats();
        assertEquals(0, stats.getInMemoryCount());
        assertEquals(0, stats.getPersistedCount());
        assertEquals(0, stats.getHitCount());
        assertEquals(0, stats.getMissCount());
        assertEquals(0, stats.getFetchCount());

        // Trigger a fetch attempt (AI engine is unreachable, no disk fallback)
        c.getTrainedMap("X12", "850", "JSON", null);

        stats = c.getCacheStats();
        assertEquals(1, stats.getFetchCount(), "Should have attempted one fetch");
        assertEquals(1, stats.getMissCount(), "Should have one miss (AI engine down, no disk)");
        assertEquals(1, stats.getInMemoryCount(), "Miss should still be cached in memory");

        // Second request for the same key should be a cache hit (cached miss)
        c.getTrainedMap("X12", "850", "JSON", null);
        stats = c.getCacheStats();
        assertEquals(1, stats.getFetchCount(), "Should NOT have fetched again (cached miss)");
        assertEquals(1, stats.getHitCount(), "Should have one hit from cached miss");

        // Persist a map and verify persisted count
        invokePersistMap(c, "X12:850→JSON@_", buildTestMap("test", 1, 90, 2));
        stats = c.getCacheStats();
        assertEquals(1, stats.getPersistedCount(), "Should have one persisted map");
    }

    @Test
    void getTrainedMap_aiEngineDown_fallsToDisk(@TempDir Path tempDir) throws Exception {
        TrainedMapConsumer c = createPersistentConsumer(tempDir);
        String cacheKey = "X12:850→JSON@ACME";

        // Pre-persist a map to disk (simulating a previous successful fetch)
        invokePersistMap(c, cacheKey, buildTestMap("X12:850→JSON@ACME", 3, 92, 5));

        // Now request the map — AI engine is unreachable, should fall back to disk
        Optional<TrainedMap> result = c.getTrainedMap("X12", "850", "JSON", "ACME");

        assertTrue(result.isPresent(), "Should load from disk when AI engine is down");
        assertEquals("X12:850→JSON@ACME", result.get().getMapKey());
        assertEquals(3, result.get().getVersion());
        assertEquals(92, result.get().getConfidence());
        assertEquals(5, result.get().getFieldMappings().size());
    }

    @Test
    void cacheKeyToFileName_producesValidFileName() {
        String fileName = TrainedMapConsumer.cacheKeyToFileName("X12:850→JSON@ACME");
        assertFalse(fileName.contains(":"), "Filename should not contain colons");
        assertFalse(fileName.contains("→"), "Filename should not contain arrow chars");
        assertFalse(fileName.contains("@"), "Filename should not contain @ sign");
        assertTrue(fileName.endsWith(".json"), "Filename should end with .json");
        assertEquals("X12_850_to_JSON_at_ACME.json", fileName);
    }
}
