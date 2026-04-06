package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.entity.edi.TrainingSession;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import com.filetransfer.ai.repository.edi.TrainingSessionRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Versioned map store — persists trained maps to PostgreSQL
 * and serves them with an in-memory hot cache for converter consumption.
 *
 * Each map key can have multiple versions; only one is active at a time.
 * The converter always fetches the active version.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainedMapStore {

    private final ConversionMapRepository mapRepo;
    private final TrainingSessionRepository sessionRepo;
    private final ObjectMapper objectMapper;

    /** Hot cache: mapKey → active ConversionMap */
    private final ConcurrentHashMap<String, ConversionMap> activeMapCache = new ConcurrentHashMap<>();

    /**
     * Store a training result as a new map version.
     * Deactivates previous versions and activates the new one.
     */
    @Transactional
    public ConversionMap storeTrainingResult(EdiMapTrainingEngine.TrainingResult result,
                                              String triggeredBy) {
        String mapKey = result.getMapKey();

        // Determine next version
        int nextVersion = mapRepo.findMaxVersionByMapKey(mapKey).orElse(0) + 1;

        // Get previous active map for improvement calculation
        Optional<ConversionMap> previousActive = mapRepo.findByMapKeyAndActiveTrue(mapKey);
        int improvementDelta = previousActive
                .map(prev -> result.getConfidence() - prev.getConfidence())
                .orElse(0);

        // Deactivate all previous versions
        mapRepo.deactivateAllByMapKey(mapKey);

        // Serialize field mappings
        String fieldMappingsJson;
        String unmappedSourceJson;
        String unmappedTargetJson;
        try {
            fieldMappingsJson = objectMapper.writeValueAsString(result.getFieldMappings());
            unmappedSourceJson = objectMapper.writeValueAsString(result.getUnmappedSourceFields());
            unmappedTargetJson = objectMapper.writeValueAsString(result.getUnmappedTargetFields());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize training result", e);
        }

        // Parse mapKey to extract components
        MapKeyComponents components = parseMapKey(mapKey);

        // Create new map entity
        ConversionMap newMap = ConversionMap.builder()
                .mapKey(mapKey)
                .name(buildMapName(components))
                .sourceFormat(components.sourceFormat)
                .sourceType(components.sourceType)
                .targetFormat(components.targetFormat)
                .targetType(components.targetType)
                .partnerId(components.partnerId)
                .version(nextVersion)
                .active(true)
                .confidence(result.getConfidence())
                .sampleCount(result.getTrainingSampleCount())
                .fieldMappingCount(result.getFieldMappings().size())
                .fieldMappingsJson(fieldMappingsJson)
                .generatedCode(result.getGeneratedCode())
                .unmappedSourceFieldsJson(unmappedSourceJson)
                .unmappedTargetFieldsJson(unmappedTargetJson)
                .testAccuracy(result.getTestAccuracy())
                .build();

        newMap = mapRepo.save(newMap);

        // Update hot cache
        activeMapCache.put(mapKey, newMap);

        // Record training session
        TrainingSession session = TrainingSession.builder()
                .mapKey(mapKey)
                .status(TrainingSession.Status.COMPLETED)
                .trainingSampleCount(result.getTrainingSampleCount())
                .testSampleCount(result.getTestSampleCount())
                .strategiesUsed(String.join(",", result.getStrategiesUsed()))
                .producedMapVersion(nextVersion)
                .producedMapConfidence(result.getConfidence())
                .testAccuracy(result.getTestAccuracy())
                .fieldMappingsDiscovered(result.getFieldMappings().size())
                .improvementDelta(improvementDelta)
                .durationMs(result.getDurationMs())
                .trainingReport(result.getTrainingReport())
                .triggeredBy(triggeredBy)
                .build();
        session.markCompleted();
        sessionRepo.save(session);

        log.info("Stored map '{}' v{} ({} mappings, {}% confidence, delta={})",
                mapKey, nextVersion, result.getFieldMappings().size(),
                result.getConfidence(), improvementDelta);

        return newMap;
    }

    /**
     * Get the active map for a given key. Checks cache first, then DB.
     */
    public Optional<ConversionMap> getActiveMap(String mapKey) {
        ConversionMap cached = activeMapCache.get(mapKey);
        if (cached != null) return Optional.of(cached);

        Optional<ConversionMap> fromDb = mapRepo.findByMapKeyAndActiveTrue(mapKey);
        fromDb.ifPresent(m -> activeMapCache.put(mapKey, m));
        return fromDb;
    }

    /**
     * Get active map by components (used by converter service which doesn't know mapKey format).
     */
    public Optional<ConversionMap> getActiveMap(String sourceFormat, String sourceType,
                                                 String targetFormat, String partnerId) {
        // Try partner-specific first, then default
        String partnerKey = buildMapKey(sourceFormat, sourceType, targetFormat, null, partnerId);
        Optional<ConversionMap> partnerMap = getActiveMap(partnerKey);
        if (partnerMap.isPresent()) return partnerMap;

        // Fall back to default (no partner)
        String defaultKey = buildMapKey(sourceFormat, sourceType, targetFormat, null, null);
        return getActiveMap(defaultKey);
    }

    /**
     * Deserialize field mappings from a stored map.
     */
    public List<EdiMapTrainingEngine.FieldMapping> getFieldMappings(ConversionMap map) {
        try {
            return objectMapper.readValue(map.getFieldMappingsJson(),
                    new TypeReference<List<EdiMapTrainingEngine.FieldMapping>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize field mappings for map {}", map.getId(), e);
            return List.of();
        }
    }

    /**
     * List all active maps (for dashboard/management).
     */
    public List<MapSummary> listActiveMaps() {
        return mapRepo.findByActiveTrue().stream()
                .map(m -> MapSummary.builder()
                        .id(m.getId().toString())
                        .mapKey(m.getMapKey())
                        .name(m.getName())
                        .sourceFormat(m.getSourceFormat())
                        .sourceType(m.getSourceType())
                        .targetFormat(m.getTargetFormat())
                        .targetType(m.getTargetType())
                        .partnerId(m.getPartnerId())
                        .version(m.getVersion())
                        .confidence(m.getConfidence())
                        .sampleCount(m.getSampleCount())
                        .fieldMappingCount(m.getFieldMappingCount())
                        .testAccuracy(m.getTestAccuracy())
                        .usageCount(m.getUsageCount())
                        .lastUsedAt(m.getLastUsedAt())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Get version history for a map key.
     */
    public List<MapSummary> getVersionHistory(String mapKey) {
        return mapRepo.findByMapKeyOrderByVersionDesc(mapKey).stream()
                .map(m -> MapSummary.builder()
                        .id(m.getId().toString())
                        .mapKey(m.getMapKey())
                        .name(m.getName())
                        .version(m.getVersion())
                        .active(m.isActive())
                        .confidence(m.getConfidence())
                        .sampleCount(m.getSampleCount())
                        .fieldMappingCount(m.getFieldMappingCount())
                        .testAccuracy(m.getTestAccuracy())
                        .usageCount(m.getUsageCount())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Rollback to a specific version.
     */
    @Transactional
    public Optional<ConversionMap> rollbackToVersion(String mapKey, int version) {
        Optional<ConversionMap> target = mapRepo.findByMapKeyAndVersion(mapKey, version);
        if (target.isEmpty()) return Optional.empty();

        mapRepo.deactivateAllByMapKey(mapKey);
        ConversionMap map = target.get();
        map.setActive(true);
        map = mapRepo.save(map);
        activeMapCache.put(mapKey, map);

        log.info("Rolled back map '{}' to version {}", mapKey, version);
        return Optional.of(map);
    }

    /**
     * Record that a map was used for conversion (usage tracking).
     */
    @Transactional
    public void recordUsage(String mapKey) {
        ConversionMap cached = activeMapCache.get(mapKey);
        if (cached != null) {
            cached.incrementUsage();
            mapRepo.save(cached);
        }
    }

    /**
     * Get recent training sessions.
     */
    public List<TrainingSession> getRecentSessions() {
        return sessionRepo.findTop20ByOrderByStartedAtDesc();
    }

    /**
     * Get training sessions for a specific map.
     */
    public List<TrainingSession> getSessionsForMap(String mapKey) {
        return sessionRepo.findByMapKeyOrderByStartedAtDesc(mapKey);
    }

    /**
     * Refresh the cache from DB (e.g., after another node trained a map).
     */
    public void refreshCache() {
        activeMapCache.clear();
        mapRepo.findByActiveTrue().forEach(m -> activeMapCache.put(m.getMapKey(), m));
        log.info("Refreshed map cache: {} active maps", activeMapCache.size());
    }

    // === Helpers ===

    public static String buildMapKey(String sourceFormat, String sourceType,
                                      String targetFormat, String targetType, String partnerId) {
        String partner = (partnerId != null && !partnerId.isBlank()) ? partnerId : "_default";
        return sourceFormat + ":" + (sourceType != null ? sourceType : "*")
                + "→" + targetFormat + ":" + (targetType != null ? targetType : "*")
                + "@" + partner;
    }

    private MapKeyComponents parseMapKey(String mapKey) {
        MapKeyComponents c = new MapKeyComponents();
        try {
            String[] atParts = mapKey.split("@");
            c.partnerId = atParts.length > 1 && !"_default".equals(atParts[1]) ? atParts[1] : null;

            String[] arrowParts = atParts[0].split("→");
            String[] sourceParts = arrowParts[0].split(":");
            c.sourceFormat = sourceParts[0];
            c.sourceType = sourceParts.length > 1 && !"*".equals(sourceParts[1]) ? sourceParts[1] : null;

            if (arrowParts.length > 1) {
                String[] targetParts = arrowParts[1].split(":");
                c.targetFormat = targetParts[0];
                c.targetType = targetParts.length > 1 && !"*".equals(targetParts[1]) ? targetParts[1] : null;
            }
        } catch (Exception e) {
            log.warn("Failed to parse mapKey '{}': {}", mapKey, e.getMessage());
        }
        return c;
    }

    private String buildMapName(MapKeyComponents c) {
        StringBuilder name = new StringBuilder();
        name.append(c.sourceFormat != null ? c.sourceFormat : "?");
        if (c.sourceType != null) name.append(" ").append(c.sourceType);
        name.append(" → ");
        name.append(c.targetFormat != null ? c.targetFormat : "?");
        if (c.targetType != null) name.append(" ").append(c.targetType);
        if (c.partnerId != null) name.append(" for ").append(c.partnerId);
        return name.toString();
    }

    private static class MapKeyComponents {
        String sourceFormat, sourceType, targetFormat, targetType, partnerId;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MapSummary {
        private String id;
        private String mapKey;
        private String name;
        private String sourceFormat;
        private String sourceType;
        private String targetFormat;
        private String targetType;
        private String partnerId;
        private int version;
        private boolean active;
        private int confidence;
        private int sampleCount;
        private int fieldMappingCount;
        private Integer testAccuracy;
        private long usageCount;
        private Instant lastUsedAt;
        private Instant createdAt;
    }
}
