package com.filetransfer.edi.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.service.TrainedMapConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the best map for a source->target conversion.
 * Cascade: Partner custom -> Trained -> Standard (classpath).
 * Standard maps are lazy-loaded from classpath resources.
 * Trained/partner maps are fetched from ai-engine on first use with 5min TTL cache.
 */
@Service
@Slf4j
public class MapResolver {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Standard maps: loaded from classpath resources (src/main/resources/maps/standard/)
    private final Map<String, ConversionMapDefinition> standardMaps = new ConcurrentHashMap<>();
    // Trained maps cache: key = "sourceType:targetType", TTL 5 min
    private final Map<String, CacheEntry> trainedMapCache = new ConcurrentHashMap<>();
    // Partner maps cache: key = "partnerId:sourceType:targetType"
    private final Map<String, CacheEntry> partnerMapCache = new ConcurrentHashMap<>();

    // Index of available standard maps (loaded at startup, maps loaded lazily)
    private final Map<String, String> standardMapIndex = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private TrainedMapConsumer trainedMapConsumer;

    @PostConstruct
    void init() {
        buildStandardMapIndex();
        log.info("MapResolver initialized: {} standard maps indexed", standardMapIndex.size());
    }

    /**
     * Resolve the best map for a conversion.
     * @param sourceType e.g., "X12_850", "EDIFACT_ORDERS", "SWIFT_MT103"
     * @param targetType e.g., "PURCHASE_ORDER_INH", "EDIFACT_ORDERS"
     * @param partnerId optional — for partner-specific maps
     * @return the map definition, or empty if no map found
     */
    public Optional<ConversionMapDefinition> resolve(String sourceType, String targetType, String partnerId) {
        String key = sourceType + ":" + targetType;

        // 1. Partner custom map (highest priority)
        if (partnerId != null && !partnerId.isEmpty()) {
            Optional<ConversionMapDefinition> partnerMap = getPartnerMap(partnerId, sourceType, targetType);
            if (partnerMap.isPresent()) {
                log.debug("Resolved PARTNER map for {} (partner={})", key, partnerId);
                return partnerMap;
            }
        }

        // 2. Trained map (from ai-engine)
        Optional<ConversionMapDefinition> trained = getTrainedMap(sourceType, targetType);
        if (trained.isPresent()) {
            log.debug("Resolved TRAINED map for {}", key);
            return trained;
        }

        // 3. Standard map (from classpath)
        Optional<ConversionMapDefinition> standard = getStandardMap(sourceType, targetType);
        if (standard.isPresent()) {
            log.debug("Resolved STANDARD map for {}", key);
        }
        return standard;
    }

    /**
     * List all available maps (standard + trained + partner).
     */
    public List<MapSummary> listAvailableMaps() {
        List<MapSummary> result = new ArrayList<>();
        for (var entry : standardMapIndex.entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length == 2) {
                result.add(new MapSummary(entry.getValue(), parts[0], parts[1], "STANDARD", null, 1.0));
            }
        }
        return result;
    }

    /**
     * Get a specific standard map by ID.
     */
    public Optional<ConversionMapDefinition> getStandardMapById(String mapId) {
        ConversionMapDefinition cached = standardMaps.get(mapId);
        if (cached != null) return Optional.of(cached);

        // Try loading from classpath
        try (InputStream is = getClass().getResourceAsStream("/maps/standard/" + mapId + ".json")) {
            if (is == null) return Optional.empty();
            ConversionMapDefinition map = objectMapper.readValue(is, ConversionMapDefinition.class);
            standardMaps.put(mapId, map);
            return Optional.of(map);
        } catch (Exception e) {
            log.warn("Failed to load standard map {}: {}", mapId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get count of indexed standard maps.
     */
    public int getStandardMapCount() {
        return standardMapIndex.size();
    }

    // --- internal ---

    private void buildStandardMapIndex() {
        // Scan classpath for map JSON files
        String[] mapFiles = {
            "STD-PO-001", "STD-PO-002", "STD-PO-003",
            "STD-INV-001", "STD-INV-002", "STD-INV-003",
            "STD-ASN-001", "STD-ASN-002", "STD-ASN-003",
            "STD-PAY-001", "STD-PAY-002",
            "STD-ACK-001", "STD-ACK-002", "STD-ACK-003",
            "STD-SWF-001", "STD-SWF-002", "STD-SWF-005", "STD-SWF-006", "STD-SWF-007",
            "STD-ACH-001", "STD-ACH-002",
            "STD-HIP-001", "STD-HIP-002", "STD-HIP-003",
            "STD-BAI-001",
            "STD-TRD-001", "STD-TRD-002"
        };

        for (String mapId : mapFiles) {
            try (InputStream is = getClass().getResourceAsStream("/maps/standard/" + mapId + ".json")) {
                if (is != null) {
                    ConversionMapDefinition map = objectMapper.readValue(is, ConversionMapDefinition.class);
                    String key = map.getSourceType() + ":" + map.getTargetType();
                    standardMapIndex.put(key, mapId);
                    standardMaps.put(mapId, map);
                    if (map.isBidirectional()) {
                        String reverseKey = map.getTargetType() + ":" + map.getSourceType();
                        standardMapIndex.put(reverseKey, mapId + "_REV");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to index standard map {}: {}", mapId, e.getMessage());
            }
        }
    }

    private Optional<ConversionMapDefinition> getStandardMap(String sourceType, String targetType) {
        String key = sourceType + ":" + targetType;
        String mapId = standardMapIndex.get(key);
        if (mapId == null) return Optional.empty();

        // Handle reverse maps
        if (mapId.endsWith("_REV")) {
            String originalId = mapId.substring(0, mapId.length() - 4);
            ConversionMapDefinition original = standardMaps.get(originalId);
            if (original != null) return Optional.of(original);
        }

        // Check if already loaded
        ConversionMapDefinition cached = standardMaps.get(mapId);
        if (cached != null) return Optional.of(cached);

        // Lazy load from classpath
        try (InputStream is = getClass().getResourceAsStream("/maps/standard/" + mapId + ".json")) {
            if (is == null) return Optional.empty();
            ConversionMapDefinition map = objectMapper.readValue(is, ConversionMapDefinition.class);
            standardMaps.put(mapId, map);
            return Optional.of(map);
        } catch (Exception e) {
            log.warn("Failed to load standard map {}: {}", mapId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ConversionMapDefinition> getTrainedMap(String sourceType, String targetType) {
        if (trainedMapConsumer == null) return Optional.empty();

        String cacheKey = sourceType + ":" + targetType;
        CacheEntry cached = trainedMapCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.map());
        }

        // Delegate to TrainedMapConsumer — extract source format and doc type from sourceType
        // sourceType format: "X12_850" -> sourceFormat="X12", sourceType="850"
        String[] parts = sourceType.split("_", 2);
        if (parts.length < 2) return Optional.empty();

        String sourceFormat = parts[0];
        String docType = parts[1];

        Optional<TrainedMapConsumer.TrainedMap> trainedOpt =
            trainedMapConsumer.getTrainedMap(sourceFormat, docType, targetType, null);

        if (trainedOpt.isPresent()) {
            // Convert TrainedMapConsumer.TrainedMap to ConversionMapDefinition
            TrainedMapConsumer.TrainedMap tm = trainedOpt.get();
            ConversionMapDefinition cmd = convertTrainedMap(tm, sourceType, targetType);
            trainedMapCache.put(cacheKey, new CacheEntry(cmd, System.currentTimeMillis() + 300_000L));
            return Optional.of(cmd);
        }

        // Cache the miss
        trainedMapCache.put(cacheKey, new CacheEntry(null, System.currentTimeMillis() + 60_000L));
        return Optional.empty();
    }

    private Optional<ConversionMapDefinition> getPartnerMap(String partnerId, String sourceType, String targetType) {
        String cacheKey = partnerId + ":" + sourceType + ":" + targetType;
        CacheEntry cached = partnerMapCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return Optional.ofNullable(cached.map());
        }

        // Partner maps fetched from ai-engine DB via REST — placeholder for future wiring
        // Cache the miss
        partnerMapCache.put(cacheKey, new CacheEntry(null, System.currentTimeMillis() + 60_000L));
        return Optional.empty();
    }

    private ConversionMapDefinition convertTrainedMap(TrainedMapConsumer.TrainedMap tm,
                                                       String sourceType, String targetType) {
        List<ConversionMapDefinition.FieldMapping> fieldMappings = new ArrayList<>();
        if (tm.getFieldMappings() != null) {
            for (var fm : tm.getFieldMappings()) {
                fieldMappings.add(ConversionMapDefinition.FieldMapping.builder()
                    .sourcePath(fm.getSourceField())
                    .targetPath(fm.getTargetField())
                    .transform(fm.getTransform() != null ? fm.getTransform() : "COPY")
                    .confidence(fm.getConfidence() / 100.0)
                    .build());
            }
        }

        return ConversionMapDefinition.builder()
            .mapId(tm.getMapKey())
            .name("Trained: " + tm.getMapKey())
            .version(String.valueOf(tm.getVersion()))
            .sourceType(sourceType)
            .targetType(targetType)
            .status("ACTIVE")
            .confidence(tm.getConfidence() / 100.0)
            .fieldMappings(fieldMappings)
            .build();
    }

    // --- Cache entry ---
    private record CacheEntry(ConversionMapDefinition map, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
}
