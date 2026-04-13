package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Partner map management — clone, customize, test, and activate partner-specific maps.
 *
 * Partner maps are cloned from standard maps (in edi-converter classpath) and stored
 * in the ai-engine DB with a partnerId scope. The edi-converter's MapResolver fetches
 * partner maps from this service at conversion time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerMapService {

    private final ConversionMapRepository mapRepo;
    private final TrainedMapStore trainedMapStore;
    private final ObjectMapper objectMapper;

    @Value("${platform.services.edi-converter.url:http://edi-converter:8095}")
    private String ediConverterUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // ===================================================================
    // CLONE
    // ===================================================================

    /**
     * Create a blank partner map from scratch — for manual mapping in MapBuilder UI.
     */
    @Transactional
    public ConversionMap createBlank(String name, String partnerId,
                                      String sourceFormat, String sourceType,
                                      String targetFormat, String targetType,
                                      String fieldMappingsJson) {
        String mapKey = (sourceFormat + "_" + (sourceType != null ? sourceType : "ANY")
                + "_TO_" + targetFormat + "_" + (targetType != null ? targetType : "ANY")
                + "_" + (partnerId != null ? partnerId : "GLOBAL")).toUpperCase();

        ConversionMap map = ConversionMap.builder()
                .mapKey(mapKey)
                .name(name)
                .sourceFormat(sourceFormat)
                .sourceType(sourceType)
                .targetFormat(targetFormat)
                .targetType(targetType)
                .partnerId(partnerId)
                .status("DRAFT")
                .version(1)
                .active(false)
                .confidence(0)
                .fieldMappingCount(0)
                .fieldMappingsJson(fieldMappingsJson != null ? fieldMappingsJson : "[]")
                .usageCount(0)
                .build();
        return mapRepo.save(map);
    }

    /**
     * Clone a standard map as a partner-specific map.
     * Fetches the standard map definition from edi-converter, stores it in DB.
     */
    @Transactional
    public ConversionMap cloneFromStandard(String sourceMapId, String partnerId, String name) {
        // Fetch the standard map definition from edi-converter
        // Endpoint: GET /api/v1/convert/maps/{mapId} — returns ConversionMapDefinition JSON
        String url = ediConverterUrl + "/api/v1/convert/maps/" + sourceMapId;
        @SuppressWarnings("unchecked")
        Map<String, Object> mapDef = restTemplate.getForObject(url, Map.class);

        if (mapDef == null) {
            throw new IllegalArgumentException("Standard map not found: " + sourceMapId);
        }

        // Extract field mappings JSON
        String fieldMappingsJson;
        try {
            Object fieldMappings = mapDef.get("fieldMappings");
            fieldMappingsJson = fieldMappings != null
                    ? objectMapper.writeValueAsString(fieldMappings) : "[]";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize field mappings from standard map", e);
        }

        // Build the map key for the partner map
        String sourceType = stringVal(mapDef, "sourceType");
        String targetType = stringVal(mapDef, "targetType");
        String sourceStandard = stringVal(mapDef, "sourceStandard");
        String targetStandard = stringVal(mapDef, "targetStandard");

        // Derive format from standard or type
        String sourceFormat = sourceStandard != null ? sourceStandard
                : (sourceType != null && sourceType.contains("_") ? sourceType.split("_")[0] : "UNKNOWN");
        String targetFormat = targetStandard != null ? targetStandard
                : (targetType != null && targetType.contains("_") ? targetType.split("_")[0] : "UNKNOWN");

        String mapKey = TrainedMapStore.buildMapKey(sourceFormat, sourceType, targetFormat, targetType, partnerId);

        // Check for existing partner map with same key
        Optional<ConversionMap> existing = mapRepo.findByMapKeyAndActiveTrue(mapKey);
        if (existing.isPresent()) {
            throw new IllegalStateException("Partner already has an active map for this conversion path: " + mapKey);
        }

        // Extract loop/code table metadata as JSON if present
        String metadataJson = null;
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (mapDef.containsKey("loopMappings")) metadata.put("loopMappings", mapDef.get("loopMappings"));
            if (mapDef.containsKey("codeTables")) metadata.put("codeTables", mapDef.get("codeTables"));
            if (mapDef.containsKey("metadata")) metadata.put("metadata", mapDef.get("metadata"));
            if (!metadata.isEmpty()) {
                metadataJson = objectMapper.writeValueAsString(metadata);
            }
        } catch (Exception e) {
            log.warn("Failed to serialize metadata from standard map: {}", e.getMessage());
        }

        // Count field mappings
        int fieldMappingCount = 0;
        try {
            List<?> mappings = objectMapper.readValue(fieldMappingsJson, List.class);
            fieldMappingCount = mappings.size();
        } catch (Exception ignored) {}

        ConversionMap partnerMap = ConversionMap.builder()
                .mapKey(mapKey)
                .name(name != null ? name : "Partner map: " + sourceMapId)
                .sourceFormat(sourceFormat)
                .sourceType(sourceType)
                .targetFormat(targetFormat)
                .targetType(targetType)
                .partnerId(partnerId)
                .parentMapId(sourceMapId)
                .status("DRAFT")
                .version(1)
                .active(false)
                .confidence(100) // cloned from standard, full confidence
                .fieldMappingCount(fieldMappingCount)
                .fieldMappingsJson(fieldMappingsJson)
                .generatedCode(metadataJson) // reuse generatedCode column for extra metadata
                .build();

        partnerMap = mapRepo.save(partnerMap);
        log.info("Cloned standard map '{}' as partner map '{}' for partner {}",
                sourceMapId, partnerMap.getId(), partnerId);
        return partnerMap;
    }

    // ===================================================================
    // UPDATE
    // ===================================================================

    /**
     * Update a partner map's field mappings (and optionally other definition fields).
     * Increments the version.
     */
    @Transactional
    public ConversionMap update(UUID mapId, Map<String, Object> updates) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));

        if (map.getPartnerId() == null || map.getPartnerId().isBlank()) {
            throw new IllegalStateException("Cannot update a non-partner map via partner API");
        }

        // Apply field mapping updates
        if (updates.containsKey("fieldMappings")) {
            try {
                String json = objectMapper.writeValueAsString(updates.get("fieldMappings"));
                List<?> mappings = objectMapper.readValue(json, List.class);
                map.setFieldMappingsJson(json);
                map.setFieldMappingCount(mappings.size());
            } catch (Exception e) {
                throw new RuntimeException("Invalid fieldMappings JSON", e);
            }
        }

        // Apply name update
        if (updates.containsKey("name")) {
            map.setName((String) updates.get("name"));
        }

        // Apply code tables / loop mappings / metadata updates
        if (updates.containsKey("loopMappings") || updates.containsKey("codeTables") || updates.containsKey("metadata")) {
            try {
                Map<String, Object> extraMeta = new LinkedHashMap<>();
                // Preserve existing metadata
                if (map.getGeneratedCode() != null && !map.getGeneratedCode().isBlank()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existing = objectMapper.readValue(map.getGeneratedCode(), Map.class);
                        extraMeta.putAll(existing);
                    } catch (Exception ignored) {}
                }
                if (updates.containsKey("loopMappings")) extraMeta.put("loopMappings", updates.get("loopMappings"));
                if (updates.containsKey("codeTables")) extraMeta.put("codeTables", updates.get("codeTables"));
                if (updates.containsKey("metadata")) extraMeta.put("metadata", updates.get("metadata"));
                map.setGeneratedCode(objectMapper.writeValueAsString(extraMeta));
            } catch (Exception e) {
                log.warn("Failed to update metadata: {}", e.getMessage());
            }
        }

        // Increment version, set status to DRAFT (modifications require re-activation)
        map.setVersion(map.getVersion() + 1);
        map.setStatus("DRAFT");

        map = mapRepo.save(map);
        log.info("Updated partner map '{}' to version {}", mapId, map.getVersion());
        return map;
    }

    // ===================================================================
    // TEST
    // ===================================================================

    /**
     * Test a partner map by sending content through the edi-converter with the map's field mappings.
     * Returns the conversion result and optionally a diff against expected output.
     */
    public TestResult testMap(UUID mapId, String content, Map<String, Object> expectedOutput) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));

        // Deserialize field mappings from JSON to match edi-converter's expected format
        List<Object> fieldMappings;
        try {
            fieldMappings = objectMapper.readValue(map.getFieldMappingsJson(),
                    new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return TestResult.builder()
                    .mapId(mapId.toString())
                    .success(false)
                    .error("Invalid field mappings JSON: " + e.getMessage())
                    .build();
        }

        // Send to edi-converter's test-mappings endpoint
        // Expects: { "sourceContent": "...", "fieldMappings": [...] }
        Map<String, Object> testRequest = new LinkedHashMap<>();
        testRequest.put("sourceContent", content);
        testRequest.put("fieldMappings", fieldMappings);

        @SuppressWarnings("unchecked")
        Map<String, Object> conversionResult;
        try {
            String url = ediConverterUrl + "/api/v1/convert/convert/test-mappings";
            conversionResult = restTemplate.postForObject(url, testRequest, Map.class);
        } catch (Exception e) {
            return TestResult.builder()
                    .mapId(mapId.toString())
                    .success(false)
                    .error("Conversion failed: " + e.getMessage())
                    .build();
        }

        // Compare with expected output if provided
        Map<String, Object> diffs = null;
        boolean matches = true;
        if (expectedOutput != null && !expectedOutput.isEmpty() && conversionResult != null) {
            diffs = computeDiff(expectedOutput, conversionResult);
            matches = diffs.isEmpty();
        }

        return TestResult.builder()
                .mapId(mapId.toString())
                .success(true)
                .conversionResult(conversionResult)
                .matchesExpected(matches)
                .diffs(diffs)
                .fieldsApplied(conversionResult != null ? intVal(conversionResult, "fieldsApplied") : 0)
                .fieldsSkipped(conversionResult != null ? intVal(conversionResult, "fieldsSkipped") : 0)
                .build();
    }

    // ===================================================================
    // ACTIVATE / DEACTIVATE
    // ===================================================================

    /**
     * Activate a partner map — makes it the live map for this partner's conversion path.
     */
    @Transactional
    public ConversionMap activate(UUID mapId) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));

        if (map.getPartnerId() == null || map.getPartnerId().isBlank()) {
            throw new IllegalStateException("Cannot activate a non-partner map via partner API");
        }

        // Deactivate other maps for the same mapKey
        mapRepo.deactivateAllByMapKey(map.getMapKey());

        map.setActive(true);
        map.setStatus("ACTIVE");
        map = mapRepo.save(map);

        // Update the hot cache in TrainedMapStore
        trainedMapStore.refreshCache();

        log.info("Activated partner map '{}' (mapKey={}) for partner {}",
                mapId, map.getMapKey(), map.getPartnerId());
        return map;
    }

    /**
     * Deactivate a partner map — removes it from live conversion.
     */
    @Transactional
    public ConversionMap deactivate(UUID mapId) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));

        map.setActive(false);
        map.setStatus("INACTIVE");
        map = mapRepo.save(map);

        trainedMapStore.refreshCache();

        log.info("Deactivated partner map '{}' for partner {}", mapId, map.getPartnerId());
        return map;
    }

    // ===================================================================
    // QUERY
    // ===================================================================

    /**
     * List all maps for a partner.
     */
    public List<ConversionMap> listByPartner(String partnerId) {
        return mapRepo.findByPartnerId(partnerId);
    }

    /**
     * Get a specific map by ID (works for both partner and trained maps).
     */
    public Optional<ConversionMap> getById(UUID mapId) {
        return mapRepo.findById(mapId);
    }

    /**
     * Get active partner map for a specific conversion path.
     * Used by edi-converter's MapResolver to fetch partner maps.
     */
    public Optional<ConversionMap> getActivePartnerMap(String partnerId, String sourceType, String targetType) {
        // Parse source type to extract format (e.g., "X12_850" -> sourceFormat="X12")
        String sourceFormat = sourceType.contains("_") ? sourceType.split("_", 2)[0] : sourceType;
        String targetFormat = targetType.contains("_") ? targetType.split("_", 2)[0] : targetType;

        List<ConversionMap> maps = mapRepo.findByPartnerIdAndSourceFormatAndTargetFormatAndStatus(
                partnerId, sourceFormat, targetFormat, "ACTIVE");

        if (maps.isEmpty()) {
            // Try broader match
            List<ConversionMap> allActive = mapRepo.findByPartnerIdAndStatus(partnerId, "ACTIVE");
            return allActive.stream()
                    .filter(m -> matchesConversion(m, sourceType, targetType))
                    .findFirst();
        }

        // Return the best match (prefer exact sourceType/targetType match)
        final List<ConversionMap> candidates = maps;
        return candidates.stream()
                .filter(m -> matchesConversion(m, sourceType, targetType))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    /**
     * Version history of a map (all versions with the same mapKey).
     */
    public List<ConversionMap> getVersionHistory(UUID mapId) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));
        return mapRepo.findByMapKeyOrderByVersionDesc(map.getMapKey());
    }

    /**
     * Delete a partner map.
     */
    @Transactional
    public void delete(UUID mapId) {
        ConversionMap map = mapRepo.findById(mapId)
                .orElseThrow(() -> new IllegalArgumentException("Map not found: " + mapId));

        if (map.getPartnerId() == null || map.getPartnerId().isBlank()) {
            throw new IllegalStateException("Cannot delete a non-partner map via partner API");
        }

        mapRepo.delete(map);
        trainedMapStore.refreshCache();

        log.info("Deleted partner map '{}' for partner {}", mapId, map.getPartnerId());
    }

    // ===================================================================
    // HELPERS
    // ===================================================================

    private boolean matchesConversion(ConversionMap map, String sourceType, String targetType) {
        boolean sourceMatch = sourceType.equalsIgnoreCase(map.getSourceType())
                || sourceType.equalsIgnoreCase(map.getSourceFormat() + "_" + map.getSourceType())
                || (map.getSourceType() != null && sourceType.contains(map.getSourceType()));
        boolean targetMatch = targetType.equalsIgnoreCase(map.getTargetType())
                || targetType.equalsIgnoreCase(map.getTargetFormat() + "_" + map.getTargetType())
                || (map.getTargetType() != null && targetType.contains(map.getTargetType()));
        return sourceMatch && targetMatch;
    }

    /**
     * Simple diff: compare expected keys against actual output.
     */
    private Map<String, Object> computeDiff(Map<String, Object> expected, Map<String, Object> actual) {
        Map<String, Object> diffs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            Object expectedVal = entry.getValue();
            Object actualVal = actual.get(key);
            if (actualVal == null) {
                diffs.put(key, Map.of("expected", expectedVal, "actual", "MISSING"));
            } else if (!expectedVal.toString().equals(actualVal.toString())) {
                diffs.put(key, Map.of("expected", expectedVal, "actual", actualVal));
            }
        }
        return diffs;
    }

    private String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private int intVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    // ===================================================================
    // DTOs
    // ===================================================================

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TestResult {
        private String mapId;
        private boolean success;
        private String error;
        private Map<String, Object> conversionResult;
        private boolean matchesExpected;
        private Map<String, Object> diffs;
        private int fieldsApplied;
        private int fieldsSkipped;
    }
}
