package com.filetransfer.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.service.edi.PartnerMapService;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Partner Map Management REST API.
 *
 * Manages partner-specific conversion maps: clone from standard, customize
 * field mappings / code tables, test against sample data, and activate for
 * live conversion.
 *
 * The edi-converter's MapResolver fetches active partner maps from these endpoints
 * at conversion time (partner maps have highest priority in the resolution cascade).
 */
@RestController
@RequestMapping("/api/v1/edi/maps")
@RequiredArgsConstructor
@Slf4j
public class PartnerMapController {

    private final PartnerMapService partnerMapService;
    private final ObjectMapper objectMapper;

    // ===================================================================
    // CREATE (blank map)
    // ===================================================================

    /** Create a new blank partner map — enables manual map building in MapBuilder UI */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapResponse createMap(@RequestBody CreateMapRequest request) {
        if (request.getName() == null || request.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        if (request.getSourceFormat() == null || request.getSourceFormat().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceFormat is required");
        if (request.getTargetFormat() == null || request.getTargetFormat().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetFormat is required");

        ConversionMap map = partnerMapService.createBlank(
                request.getName(), request.getPartnerId(),
                request.getSourceFormat(), request.getSourceType(),
                request.getTargetFormat(), request.getTargetType(),
                request.getFieldMappingsJson());
        return toResponse(map);
    }

    // ===================================================================
    // EXPORT / IMPORT
    // ===================================================================

    /** Export a map as JSON — for backup, transfer between environments, version control */
    @GetMapping("/{mapId}/export")
    public ResponseEntity<Map<String, Object>> exportMap(@PathVariable UUID mapId) {
        ConversionMap map = partnerMapService.getById(mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Map not found: " + mapId));
        Map<String, Object> export = new java.util.LinkedHashMap<>();
        export.put("exportVersion", "1.0");
        export.put("exportedAt", java.time.Instant.now().toString());
        export.put("map", toResponse(map));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + map.getName() + ".json\"")
                .body(export);
    }

    /** Import a map from JSON export */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public MapResponse importMap(@RequestBody Map<String, Object> importData) {
        @SuppressWarnings("unchecked")
        Map<String, Object> mapData = (Map<String, Object>) importData.get("map");
        if (mapData == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'map' field in import data");

        String name = (String) mapData.getOrDefault("name", "Imported Map");
        String partnerId = (String) mapData.get("partnerId");
        String sourceFormat = (String) mapData.getOrDefault("sourceFormat", "X12");
        String sourceType = (String) mapData.get("sourceType");
        String targetFormat = (String) mapData.getOrDefault("targetFormat", "JSON");
        String targetType = (String) mapData.get("targetType");
        String fieldMappingsJson = mapData.get("fieldMappingsJson") != null
                ? mapData.get("fieldMappingsJson").toString() : null;

        ConversionMap map = partnerMapService.createBlank(
                name, partnerId, sourceFormat, sourceType, targetFormat, targetType, fieldMappingsJson);
        return toResponse(map);
    }

    // ===================================================================
    // CLONE
    // ===================================================================

    /** Clone a standard map as a partner-specific map */
    @PostMapping("/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public MapResponse cloneMap(@RequestBody CloneRequest request) {
        validateCloneRequest(request);
        ConversionMap map = partnerMapService.cloneFromStandard(
                request.getSourceMapId(), request.getPartnerId(), request.getName());
        return toResponse(map);
    }

    // ===================================================================
    // QUERY
    // ===================================================================

    /** List all maps for a partner */
    @GetMapping("/partner/{partnerId}")
    public List<MapResponse> listPartnerMaps(
            @PathVariable String partnerId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String targetType) {

        List<ConversionMap> maps = partnerMapService.listByPartner(partnerId);

        // If sourceType/targetType filters provided, use getActivePartnerMap for converter lookups
        if (sourceType != null && targetType != null) {
            Optional<ConversionMap> active = partnerMapService.getActivePartnerMap(
                    partnerId, sourceType, targetType);
            return active.map(m -> List.of(toResponse(m))).orElse(List.of());
        }

        return maps.stream().map(this::toResponse).toList();
    }

    /** Get a specific map by ID */
    @GetMapping("/{mapId}")
    public MapResponse getMap(@PathVariable UUID mapId) {
        return partnerMapService.getById(mapId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Map not found"));
    }

    /** Version history of a map */
    @GetMapping("/{mapId}/versions")
    public List<MapResponse> getVersionHistory(@PathVariable UUID mapId) {
        return partnerMapService.getVersionHistory(mapId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ===================================================================
    // UPDATE
    // ===================================================================

    /** Update a partner map (field mappings, code tables, name, etc.) */
    @PutMapping("/{mapId}")
    public MapResponse updateMap(@PathVariable UUID mapId, @RequestBody Map<String, Object> updates) {
        try {
            ConversionMap map = partnerMapService.update(mapId, updates);
            return toResponse(map);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ===================================================================
    // TEST
    // ===================================================================

    /** Test a map against sample data */
    @PostMapping("/{mapId}/test")
    public PartnerMapService.TestResult testMap(@PathVariable UUID mapId, @RequestBody TestRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        return partnerMapService.testMap(mapId, request.getContent(), request.getExpectedOutput());
    }

    // ===================================================================
    // ACTIVATE / DEACTIVATE
    // ===================================================================

    /** Activate a partner map for live conversion */
    @PostMapping("/{mapId}/activate")
    public MapResponse activateMap(@PathVariable UUID mapId) {
        try {
            ConversionMap map = partnerMapService.activate(mapId);
            return toResponse(map);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Deactivate a partner map */
    @PostMapping("/{mapId}/deactivate")
    public MapResponse deactivateMap(@PathVariable UUID mapId) {
        try {
            ConversionMap map = partnerMapService.deactivate(mapId);
            return toResponse(map);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ===================================================================
    // DELETE
    // ===================================================================

    /** Delete a partner map */
    @DeleteMapping("/{mapId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMap(@PathVariable UUID mapId) {
        try {
            partnerMapService.delete(mapId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ===================================================================
    // HELPERS & DTOs
    // ===================================================================

    private void validateCloneRequest(CloneRequest request) {
        if (request.getSourceMapId() == null || request.getSourceMapId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceMapId is required");
        }
        if (request.getPartnerId() == null || request.getPartnerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerId is required");
        }
    }

    private MapResponse toResponse(ConversionMap map) {
        return MapResponse.builder()
                .id(map.getId().toString())
                .mapKey(map.getMapKey())
                .name(map.getName())
                .sourceFormat(map.getSourceFormat())
                .sourceType(map.getSourceType())
                .targetFormat(map.getTargetFormat())
                .targetType(map.getTargetType())
                .partnerId(map.getPartnerId())
                .parentMapId(map.getParentMapId())
                .status(map.getStatus())
                .version(map.getVersion())
                .active(map.isActive())
                .confidence(map.getConfidence())
                .fieldMappingCount(map.getFieldMappingCount())
                .fieldMappingsJson(map.getFieldMappingsJson())
                .usageCount(map.getUsageCount())
                .createdAt(map.getCreatedAt() != null ? map.getCreatedAt().toString() : null)
                .build();
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateMapRequest {
        private String name;
        private String partnerId;
        private String sourceFormat;     // X12, EDIFACT, etc.
        private String sourceType;       // 850, ORDERS, etc.
        private String targetFormat;     // JSON, XML, CSV
        private String targetType;       // optional
        private String fieldMappingsJson; // optional initial field mappings
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CloneRequest {
        private String sourceMapId;
        private String partnerId;
        private String name;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TestRequest {
        private String content;
        private Map<String, Object> expectedOutput;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MapResponse {
        private String id;
        private String mapKey;
        private String name;
        private String sourceFormat;
        private String sourceType;
        private String targetFormat;
        private String targetType;
        private String partnerId;
        private String parentMapId;
        private String status;
        private int version;
        private boolean active;
        private int confidence;
        private int fieldMappingCount;
        private String fieldMappingsJson;
        private long usageCount;
        private String createdAt;
    }
}
