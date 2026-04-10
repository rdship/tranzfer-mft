package com.filetransfer.ai.controller;

import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.entity.edi.TrainingSample;
import com.filetransfer.ai.entity.edi.TrainingSession;
import com.filetransfer.ai.service.edi.EdiMapTrainingEngine;
import com.filetransfer.ai.service.edi.EdiTrainingDataService;
import com.filetransfer.ai.service.edi.SchemaMapGenerator;
import com.filetransfer.ai.service.edi.TrainedMapStore;
import com.filetransfer.ai.service.edi.EdiTrainingDataSeeder;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * EDI Map Training REST API.
 *
 * Training flow:
 *   1. POST /samples        — Upload input/output pairs
 *   2. POST /samples/bulk   — Bulk upload samples
 *   3. POST /train          — Trigger training on accumulated samples
 *   4. GET  /maps           — List trained maps
 *   5. GET  /maps/{mapKey}  — Get active map (used by edi-converter)
 *   6. GET  /maps/{mapKey}/apply — Apply map to new input (test endpoint)
 *
 * The edi-converter fetches maps via GET /maps/{mapKey} at conversion time.
 */
@RestController
@RequestMapping("/api/v1/edi/training")
@RequiredArgsConstructor
@Slf4j
public class EdiMapTrainingController {

    private final EdiTrainingDataService trainingDataService;
    private final EdiMapTrainingEngine trainingEngine;
    private final TrainedMapStore mapStore;
    private final EdiTrainingDataSeeder trainingDataSeeder;
    private final SchemaMapGenerator schemaMapGenerator;

    private static final int MAX_BATCH_SIZE = 500;

    // ===================================================================
    // TRAINING SAMPLES
    // ===================================================================

    /** Add a single training sample (input EDI + expected output) */
    @PostMapping("/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainingSample addSample(@RequestBody EdiTrainingDataService.AddSampleRequest request) {
        validateSampleRequest(request);
        return trainingDataService.addSample(request);
    }

    /** Bulk add training samples */
    @PostMapping("/samples/bulk")
    public EdiTrainingDataService.BulkIngestionResult addSamplesBulk(
            @RequestBody List<EdiTrainingDataService.AddSampleRequest> requests) {
        if (requests.size() > MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Batch size " + requests.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        return trainingDataService.addSamplesBulk(requests);
    }

    /** Get sample counts grouped by map key */
    @GetMapping("/samples/counts")
    public List<EdiTrainingDataService.MapKeySampleCount> getSampleCounts() {
        return trainingDataService.getSampleCounts();
    }

    /** Get a sample by ID */
    @GetMapping("/samples/{id}")
    public TrainingSample getSample(@PathVariable UUID id) {
        return trainingDataService.getSample(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample not found"));
    }

    /** Delete a sample */
    @DeleteMapping("/samples/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSample(@PathVariable UUID id) {
        if (!trainingDataService.deleteSample(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample not found");
        }
    }

    /** Mark a sample as human-validated */
    @PostMapping("/samples/{id}/validate")
    public TrainingSample validateSample(@PathVariable UUID id) {
        return trainingDataService.validateSample(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample not found"));
    }

    /** Get all samples for a partner */
    @GetMapping("/samples/partner/{partnerId}")
    public List<TrainingSample> getPartnerSamples(@PathVariable String partnerId) {
        return trainingDataService.getSamplesByPartner(partnerId);
    }

    // ===================================================================
    // TRAINING
    // ===================================================================

    /** Trigger training on accumulated samples for a specific conversion path */
    @PostMapping("/train")
    public TrainResponse train(@RequestBody TrainRequest request) {
        String sourceFormat = request.getSourceFormat().toUpperCase();
        String targetFormat = request.getTargetFormat().toUpperCase();

        // Fetch matching samples
        List<TrainingSample> samples = trainingDataService.getSamplesForTraining(
                sourceFormat, request.getSourceType(), targetFormat, request.getPartnerId());

        if (samples.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No training samples found for " + sourceFormat + " → " + targetFormat);
        }

        // Build map key
        String mapKey = TrainedMapStore.buildMapKey(
                sourceFormat, request.getSourceType(), targetFormat, request.getTargetType(), request.getPartnerId());

        // Run training
        EdiMapTrainingEngine.TrainingResult result = trainingEngine.train(samples, mapKey);

        if (!result.isSuccess()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Training failed: " + result.getError());
        }

        // Store the trained map
        ConversionMap storedMap = mapStore.storeTrainingResult(result,
                request.getTriggeredBy() != null ? request.getTriggeredBy() : "api");

        return TrainResponse.builder()
                .mapKey(mapKey)
                .mapId(storedMap.getId().toString())
                .version(storedMap.getVersion())
                .confidence(result.getConfidence())
                .testAccuracy(result.getTestAccuracy())
                .fieldMappingsCount(result.getFieldMappings().size())
                .trainingSampleCount(result.getTrainingSampleCount())
                .testSampleCount(result.getTestSampleCount())
                .strategiesUsed(result.getStrategiesUsed())
                .unmappedSourceFields(result.getUnmappedSourceFields())
                .unmappedTargetFields(result.getUnmappedTargetFields())
                .durationMs(result.getDurationMs())
                .trainingReport(result.getTrainingReport())
                .build();
    }

    /** Quick train: upload samples + trigger training in one call */
    @PostMapping("/quick-train")
    public TrainResponse quickTrain(@RequestBody QuickTrainRequest request) {
        // Add all samples
        for (EdiTrainingDataService.AddSampleRequest sample : request.getSamples()) {
            validateSampleRequest(sample);
            trainingDataService.addSample(sample);
        }

        // Trigger training
        TrainRequest trainRequest = new TrainRequest();
        trainRequest.setSourceFormat(request.getSourceFormat());
        trainRequest.setSourceType(request.getSourceType());
        trainRequest.setTargetFormat(request.getTargetFormat());
        trainRequest.setTargetType(request.getTargetType());
        trainRequest.setPartnerId(request.getPartnerId());
        trainRequest.setTriggeredBy(request.getTriggeredBy());

        return train(trainRequest);
    }

    // ===================================================================
    // SCHEMA-BASED MAP GENERATION
    // ===================================================================

    /** Generate a DRAFT conversion map from known schema definitions (no training samples needed) */
    @PostMapping("/generate-from-schema")
    public ResponseEntity<Map<String, Object>> generateFromSchema(@RequestBody Map<String, Object> request) {
        String sourceSchema = (String) request.get("sourceSchema");
        String targetSchema = (String) request.get("targetSchema");
        String partnerId = (String) request.get("partnerId");

        if (sourceSchema == null || sourceSchema.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceSchema is required");
        }
        if (targetSchema == null || targetSchema.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetSchema is required");
        }

        SchemaMapGenerator.GenerationResult result = schemaMapGenerator.generate(
                sourceSchema.toUpperCase(), targetSchema.toUpperCase(), partnerId);

        if (!result.isSuccess()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.getError());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mapId", result.getMapId());
        response.put("mapKey", result.getMapKey());
        response.put("status", result.getStatus());
        response.put("overallConfidence", result.getOverallConfidence());
        response.put("fieldMappingCount", result.getFieldMappingCount());
        response.put("fieldMappings", result.getFieldMappings());
        response.put("unmappedSourceFields", result.getUnmappedSourceFields());
        response.put("unmappedTargetFields", result.getUnmappedTargetFields());
        response.put("durationMs", result.getDurationMs());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** List all known schema names available for schema-based generation */
    @GetMapping("/schemas")
    public Map<String, Object> listSchemas() {
        return Map.of("schemas", schemaMapGenerator.listKnownSchemas());
    }

    // ===================================================================
    // MAPS (consumed by edi-converter)
    // ===================================================================

    /** List all active trained maps */
    @GetMapping("/maps")
    public List<TrainedMapStore.MapSummary> listMaps() {
        return mapStore.listActiveMaps();
    }

    /** Get the active map for a given key (primary endpoint for edi-converter) */
    @GetMapping("/maps/lookup")
    public ResponseEntity<MapResponse> lookupMap(
            @RequestParam String sourceFormat,
            @RequestParam(required = false) String sourceType,
            @RequestParam String targetFormat,
            @RequestParam(required = false) String partnerId) {

        Optional<ConversionMap> map = mapStore.getActiveMap(
                sourceFormat.toUpperCase(), sourceType, targetFormat.toUpperCase(), partnerId);

        if (map.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ConversionMap m = map.get();
        mapStore.recordUsage(m.getMapKey());

        return ResponseEntity.ok(MapResponse.builder()
                .mapKey(m.getMapKey())
                .mapId(m.getId().toString())
                .name(m.getName())
                .version(m.getVersion())
                .confidence(m.getConfidence())
                .fieldMappingsJson(m.getFieldMappingsJson())
                .generatedCode(m.getGeneratedCode())
                .sampleCount(m.getSampleCount())
                .testAccuracy(m.getTestAccuracy())
                .build());
    }

    /** Get version history for a map */
    @GetMapping("/maps/history")
    public List<TrainedMapStore.MapSummary> getMapHistory(@RequestParam String mapKey) {
        return mapStore.getVersionHistory(mapKey);
    }

    /** Rollback a map to a specific version */
    @PostMapping("/maps/rollback")
    public ResponseEntity<ConversionMap> rollbackMap(@RequestParam String mapKey,
                                                      @RequestParam int version) {
        return mapStore.rollbackToVersion(mapKey, version)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Refresh the map cache (useful after DB migration or manual edits) */
    @PostMapping("/maps/refresh-cache")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> refreshCache() {
        mapStore.refreshCache();
        return Map.of("status", "cache_refreshed");
    }

    // ===================================================================
    // TRAINING SESSIONS (audit trail)
    // ===================================================================

    /** Recent training sessions */
    @GetMapping("/sessions")
    public List<TrainingSession> getRecentSessions() {
        return mapStore.getRecentSessions();
    }

    /** Training sessions for a specific map */
    @GetMapping("/sessions/map")
    public List<TrainingSession> getSessionsForMap(@RequestParam String mapKey) {
        return mapStore.getSessionsForMap(mapKey);
    }

    // ===================================================================
    // SEED BUILT-IN TRAINING DATA
    // ===================================================================

    /** Seed built-in training samples (X12, EDIFACT, HL7, SWIFT) */
    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> seedTrainingData() {
        return trainingDataSeeder.seedAll();
    }

    // ===================================================================
    // HEALTH
    // ===================================================================

    @GetMapping("/health")
    public Map<String, Object> health() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "UP");
        result.put("service", "edi-map-training");
        result.put("activeMaps", mapStore.listActiveMaps().size());
        result.put("sampleCounts", trainingDataService.getSampleCounts());
        result.put("knownSchemas", schemaMapGenerator.listKnownSchemas().size());
        result.put("capabilities", List.of(
                "sample-ingestion", "bulk-ingestion", "quick-train",
                "multi-strategy-training", "versioned-maps", "rollback",
                "test-accuracy", "transform-detection", "partner-specific-maps",
                "schema-based-generation", "confidence-threshold"));
        return result;
    }

    // ===================================================================
    // Validation & DTOs
    // ===================================================================

    private void validateSampleRequest(EdiTrainingDataService.AddSampleRequest request) {
        if (request.getSourceFormat() == null || request.getSourceFormat().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceFormat is required");
        }
        if (request.getTargetFormat() == null || request.getTargetFormat().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetFormat is required");
        }
        if (request.getInputContent() == null || request.getInputContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inputContent is required");
        }
        if (request.getOutputContent() == null || request.getOutputContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outputContent is required");
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TrainRequest {
        private String sourceFormat;
        private String sourceType;
        private String targetFormat;
        private String targetType;
        private String partnerId;
        private String triggeredBy;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class QuickTrainRequest {
        private String sourceFormat;
        private String sourceType;
        private String targetFormat;
        private String targetType;
        private String partnerId;
        private String triggeredBy;
        private List<EdiTrainingDataService.AddSampleRequest> samples;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrainResponse {
        private String mapKey;
        private String mapId;
        private int version;
        private int confidence;
        private Integer testAccuracy;
        private int fieldMappingsCount;
        private int trainingSampleCount;
        private int testSampleCount;
        private List<String> strategiesUsed;
        private List<String> unmappedSourceFields;
        private List<String> unmappedTargetFields;
        private long durationMs;
        private String trainingReport;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MapResponse {
        private String mapKey;
        private String mapId;
        private String name;
        private int version;
        private int confidence;
        private String fieldMappingsJson;
        private String generatedCode;
        private int sampleCount;
        private Integer testAccuracy;
    }
}
