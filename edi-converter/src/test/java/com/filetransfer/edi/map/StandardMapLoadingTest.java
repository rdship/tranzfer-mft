package com.filetransfer.edi.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for standard map loading: verifies every JSON file in
 * maps/standard/ parses correctly, has required fields, minimum mappings,
 * valid code table references, and filename consistency.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandardMapLoadingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ConversionMapDefinition> allMaps = new ArrayList<>();
    private final Map<String, String> fileNameByMapId = new LinkedHashMap<>();

    @BeforeAll
    void loadAllMaps() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:maps/standard/*.json");

        assertTrue(resources.length > 0, "Should find at least one standard map resource");

        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            assertNotNull(fileName, "Resource filename should not be null");

            try (InputStream is = resource.getInputStream()) {
                ConversionMapDefinition map = objectMapper.readValue(is, ConversionMapDefinition.class);
                allMaps.add(map);
                fileNameByMapId.put(map.getMapId(), fileName.replace(".json", ""));
            }
        }
    }

    // === All maps parse successfully ===

    @Test
    void allStandardMaps_loadSuccessfully() {
        assertFalse(allMaps.isEmpty(), "Should have loaded at least one standard map");
        assertTrue(allMaps.size() >= 31,
                "Should have at least 31 standard maps, but found " + allMaps.size());

        // If we got here without exception, all maps parsed without error
        for (ConversionMapDefinition map : allMaps) {
            assertNotNull(map, "Every parsed map should be non-null");
        }
    }

    // === Required fields ===

    @Test
    void allMaps_haveRequiredFields() {
        for (ConversionMapDefinition map : allMaps) {
            assertNotNull(map.getMapId(),
                    "mapId is required but missing in: " + map.getName());
            assertFalse(map.getMapId().isBlank(),
                    "mapId must not be blank in: " + map.getName());

            assertNotNull(map.getSourceType(),
                    "sourceType is required but missing in map: " + map.getMapId());
            assertFalse(map.getSourceType().isBlank(),
                    "sourceType must not be blank in map: " + map.getMapId());

            assertNotNull(map.getTargetType(),
                    "targetType is required but missing in map: " + map.getMapId());
            assertFalse(map.getTargetType().isBlank(),
                    "targetType must not be blank in map: " + map.getMapId());

            assertNotNull(map.getFieldMappings(),
                    "fieldMappings is required but missing in map: " + map.getMapId());
        }
    }

    // === Minimum field mappings ===

    @Test
    void allMaps_haveMinimumMappings() {
        for (ConversionMapDefinition map : allMaps) {
            int totalMappings = map.getFieldMappings().size();
            if (map.getLoopMappings() != null) {
                for (var lm : map.getLoopMappings()) {
                    if (lm.getFieldMappings() != null) {
                        totalMappings += lm.getFieldMappings().size();
                    }
                }
            }
            assertTrue(totalMappings >= 10,
                    "Map " + map.getMapId() + " should have >= 10 field mappings (including loops), but has " + totalMappings);
        }
    }

    // === Code table integrity ===

    @Test
    void allLookupTransforms_haveCodeTables() {
        for (ConversionMapDefinition map : allMaps) {
            checkLookupTransforms(map.getFieldMappings(), map.getCodeTables(), map.getMapId(), "field");

            if (map.getLoopMappings() != null) {
                for (var lm : map.getLoopMappings()) {
                    if (lm.getFieldMappings() != null) {
                        checkLookupTransforms(lm.getFieldMappings(), map.getCodeTables(),
                                map.getMapId(), "loop[" + lm.getSourceLoop() + "]");
                    }
                }
            }
        }
    }

    private void checkLookupTransforms(List<ConversionMapDefinition.FieldMapping> fieldMappings,
                                        Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables,
                                        String mapId, String context) {
        if (fieldMappings == null) return;

        for (var fm : fieldMappings) {
            if ("LOOKUP".equalsIgnoreCase(fm.getTransform())) {
                assertNotNull(fm.getTransformConfig(),
                        "LOOKUP transform in " + mapId + " (" + context + ") at " + fm.getSourcePath()
                                + " must have transformConfig");

                String tableName = fm.getTransformConfig().get("table");
                assertNotNull(tableName,
                        "LOOKUP transform in " + mapId + " (" + context + ") at " + fm.getSourcePath()
                                + " must reference a 'table' in transformConfig");

                assertNotNull(codeTables,
                        "Map " + mapId + " has LOOKUP transform but no codeTables defined");

                assertTrue(codeTables.containsKey(tableName),
                        "Map " + mapId + " references code table '" + tableName
                                + "' but it doesn't exist in codeTables. Available: " + codeTables.keySet());

                assertFalse(codeTables.get(tableName).isEmpty(),
                        "Code table '" + tableName + "' in map " + mapId + " is empty");
            }
        }
    }

    // === Filename consistency ===

    @Test
    void mapNames_matchFileNames() {
        for (var entry : fileNameByMapId.entrySet()) {
            String mapId = entry.getKey();
            String fileName = entry.getValue();
            assertEquals(mapId, fileName,
                    "mapId '" + mapId + "' should match filename '" + fileName + ".json'");
        }
    }

    // === Additional structural validation ===

    @Test
    void allMaps_haveValidStatus() {
        Set<String> validStatuses = Set.of("ACTIVE", "DRAFT", "DEPRECATED");
        for (ConversionMapDefinition map : allMaps) {
            if (map.getStatus() != null) {
                assertTrue(validStatuses.contains(map.getStatus()),
                        "Map " + map.getMapId() + " has invalid status: " + map.getStatus());
            }
        }
    }

    @Test
    void allMaps_haveValidConfidence() {
        for (ConversionMapDefinition map : allMaps) {
            assertTrue(map.getConfidence() >= 0.0 && map.getConfidence() <= 1.0,
                    "Map " + map.getMapId() + " confidence " + map.getConfidence()
                            + " is outside valid range [0.0, 1.0]");
        }
    }
}
