package com.filetransfer.edi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.UniversalEdiParser;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Fetches trained conversion maps from the AI Engine and applies them.
 *
 * This is the edi-converter's bridge to the AI training system.
 * Maps are cached locally with TTL to avoid hitting the AI engine on every request.
 *
 * Flow:
 *   1. Converter receives conversion request
 *   2. TrainedMapConsumer checks for a trained map matching the source→target
 *   3. If found, applies the learned field mappings + transforms
 *   4. If not found, falls back to the existing AiMappingGenerator/CanonicalMapper
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainedMapConsumer {

    private final UniversalEdiParser parser;
    private final ObjectMapper objectMapper;

    @Value("${platform.services.ai-engine.url:http://ai-engine:8091}")
    private String aiEngineUrl;

    @Value("${edi.trained-maps.storage-dir:${java.io.tmpdir}/edi-trained-maps}")
    private String storageDir;

    @Value("${edi.trained-maps.persist-to-disk:true}")
    private boolean persistToDisk;

    /** Local cache: cacheKey → CachedMap */
    private final ConcurrentHashMap<String, CachedMap> mapCache = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration CACHE_TTL_MISS = Duration.ofMinutes(1); // shorter TTL for misses

    private final RestTemplate restTemplate = new RestTemplate();

    /** Cache statistics counters */
    private final AtomicInteger hitCount = new AtomicInteger();
    private final AtomicInteger missCount = new AtomicInteger();
    private final AtomicInteger fetchCount = new AtomicInteger();

    @PostConstruct
    void initStorageDir() {
        if (persistToDisk) {
            try {
                Files.createDirectories(Path.of(storageDir));
                log.info("Trained map storage directory: {}", storageDir);
            } catch (IOException e) {
                log.warn("Could not create trained map storage directory {}: {}", storageDir, e.getMessage());
            }
        }
    }

    /**
     * Try to convert using a trained map. Returns empty if no trained map is available.
     */
    public Optional<TrainedConversionResult> convertWithTrainedMap(String sourceContent,
                                                                     String targetFormat,
                                                                     String partnerId) {
        // Parse source to determine format/type
        EdiDocument doc = parser.parse(sourceContent);
        if (doc == null || doc.getSourceFormat() == null) return Optional.empty();

        String sourceFormat = doc.getSourceFormat();
        String sourceType = doc.getDocumentType();

        // Try to get a trained map
        Optional<TrainedMap> map = getTrainedMap(sourceFormat, sourceType, targetFormat, partnerId);
        if (map.isEmpty()) return Optional.empty();

        // Apply the map
        return Optional.of(applyMap(doc, sourceContent, map.get()));
    }

    /**
     * Check if a trained map exists for a given conversion path.
     */
    public boolean hasTrainedMap(String sourceFormat, String sourceType,
                                  String targetFormat, String partnerId) {
        return getTrainedMap(sourceFormat, sourceType, targetFormat, partnerId).isPresent();
    }

    /**
     * Get a trained map, with local caching and disk-based persistence fallback.
     */
    public Optional<TrainedMap> getTrainedMap(String sourceFormat, String sourceType,
                                               String targetFormat, String partnerId) {
        String cacheKey = sourceFormat + ":" + sourceType + "→" + targetFormat + "@" + (partnerId != null ? partnerId : "_");
        CachedMap cached = mapCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            if (cached.map != null) {
                hitCount.incrementAndGet();
                return Optional.of(cached.map);
            }
            hitCount.incrementAndGet();
            return Optional.empty();
        }

        // Fetch from AI engine
        fetchCount.incrementAndGet();
        try {
            String url = aiEngineUrl + "/api/v1/edi/training/maps/lookup"
                    + "?sourceFormat=" + sourceFormat
                    + "&targetFormat=" + targetFormat
                    + (sourceType != null ? "&sourceType=" + sourceType : "")
                    + (partnerId != null ? "&partnerId=" + partnerId : "");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.containsKey("fieldMappingsJson")) {
                List<FieldMapping> mappings = objectMapper.readValue(
                        (String) response.get("fieldMappingsJson"),
                        new TypeReference<List<FieldMapping>>() {});

                TrainedMap trainedMap = TrainedMap.builder()
                        .mapKey((String) response.get("mapKey"))
                        .version((Integer) response.get("version"))
                        .confidence((Integer) response.get("confidence"))
                        .fieldMappings(mappings)
                        .generatedCode((String) response.get("generatedCode"))
                        .build();

                mapCache.put(cacheKey, new CachedMap(trainedMap, CACHE_TTL));
                persistMap(cacheKey, trainedMap);
                log.debug("Fetched trained map '{}' v{} ({}% confidence) from AI engine",
                        trainedMap.mapKey, trainedMap.version, trainedMap.confidence);
                return Optional.of(trainedMap);
            }
        } catch (Exception e) {
            log.debug("AI engine unavailable for {}: {}", cacheKey, e.getMessage());

            // Fallback to disk-persisted map
            Optional<TrainedMap> persisted = loadPersistedMap(cacheKey);
            if (persisted.isPresent()) {
                mapCache.put(cacheKey, new CachedMap(persisted.get(), CACHE_TTL));
                log.info("Loaded trained map from disk for {} (AI engine unavailable)", cacheKey);
                return persisted;
            }
        }

        // Cache the miss to avoid hammering AI engine
        missCount.incrementAndGet();
        mapCache.put(cacheKey, new CachedMap(null, CACHE_TTL_MISS));
        return Optional.empty();
    }

    /**
     * Apply a trained map to an EDI document.
     */
    private TrainedConversionResult applyMap(EdiDocument doc, String rawContent, TrainedMap map) {
        Map<String, String> sourceValues = extractSourceValues(doc);
        Map<String, Object> output = new LinkedHashMap<>();
        int appliedCount = 0;
        int skippedCount = 0;

        for (FieldMapping mapping : map.fieldMappings) {
            String sourceValue = sourceValues.get(mapping.sourceField);
            if (sourceValue != null) {
                String transformed = applyTransform(sourceValue, mapping.transform, mapping.transformParam);
                setNestedValue(output, mapping.targetField, transformed);
                appliedCount++;
            } else {
                skippedCount++;
            }
        }

        // Serialize output
        String outputContent;
        try {
            outputContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            outputContent = output.toString();
        }

        return TrainedConversionResult.builder()
                .output(outputContent)
                .mapKey(map.mapKey)
                .mapVersion(map.version)
                .mapConfidence(map.confidence)
                .fieldsApplied(appliedCount)
                .fieldsSkipped(skippedCount)
                .totalMappings(map.fieldMappings.size())
                .build();
    }

    private Map<String, String> extractSourceValues(EdiDocument doc) {
        Map<String, String> values = new LinkedHashMap<>();
        if (doc.getSenderId() != null) values.put("senderId", doc.getSenderId());
        if (doc.getReceiverId() != null) values.put("receiverId", doc.getReceiverId());
        if (doc.getDocumentType() != null) values.put("documentType", doc.getDocumentType());
        if (doc.getDocumentDate() != null) values.put("documentDate", doc.getDocumentDate());
        if (doc.getControlNumber() != null) values.put("controlNumber", doc.getControlNumber());

        for (int i = 0; i < doc.getSegments().size(); i++) {
            EdiDocument.Segment seg = doc.getSegments().get(i);
            List<String> elems = seg.getElements() != null ? seg.getElements() : List.of();
            for (int j = 0; j < elems.size(); j++) {
                String key = seg.getId() + "*" + String.format("%02d", j + 1);
                if (!elems.get(j).trim().isEmpty()) {
                    values.put(key, elems.get(j));
                }
            }
        }

        if (doc.getBusinessData() != null) {
            for (Map.Entry<String, Object> e : doc.getBusinessData().entrySet()) {
                if (e.getValue() != null) values.put("biz." + e.getKey(), e.getValue().toString());
            }
        }
        return values;
    }

    private String applyTransform(String value, String transform, String param) {
        if (value == null) return "";
        return switch (transform != null ? transform : "DIRECT") {
            case "TRIM" -> value.trim();
            case "UPPERCASE" -> value.toUpperCase();
            case "LOWERCASE" -> value.toLowerCase();
            case "ZERO_PAD" -> {
                int len = param != null ? Integer.parseInt(param) : value.length();
                yield String.format("%" + len + "s", value.trim()).replace(' ', '0');
            }
            case "DATE_REFORMAT" -> reformatDate(value, param);
            default -> value;
        };
    }

    private String reformatDate(String value, String param) {
        if (param == null || !param.contains("→")) return value;
        try {
            if (value.length() == 8 && param.startsWith("yyyyMMdd")) {
                return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
            }
            if (value.length() == 10 && value.contains("-") && param.startsWith("yyyy-MM-dd")) {
                return value.replace("-", "");
            }
        } catch (Exception e) { /* fall through */ }
        return value;
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String path, String value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], k -> new LinkedHashMap<>());
        }
        current.put(parts[parts.length - 1], value);
    }

    /**
     * Apply custom field mappings to source EDI content (without requiring a trained map in cache).
     * Used by the mapping correction flow to test corrections before persisting them.
     */
    public TrainedConversionResult applyCustomMappings(String sourceContent, List<FieldMapping> customMappings) {
        EdiDocument doc = parser.parse(sourceContent);
        if (doc == null) {
            return TrainedConversionResult.builder()
                    .output("{}").fieldsApplied(0).fieldsSkipped(customMappings.size())
                    .totalMappings(customMappings.size()).build();
        }

        TrainedMap tempMap = TrainedMap.builder()
                .mapKey("custom-test")
                .version(0)
                .confidence(0)
                .fieldMappings(customMappings)
                .build();

        return applyMap(doc, sourceContent, tempMap);
    }

    /** Invalidate the in-memory cache only (disk files are preserved for restart resilience) */
    public void invalidateCache() {
        mapCache.clear();
        log.info("Trained map in-memory cache invalidated (disk files preserved)");
    }

    /** Invalidate both in-memory cache AND persisted disk files */
    public void invalidateAll() {
        mapCache.clear();
        if (persistToDisk) {
            try {
                Path dir = Path.of(storageDir);
                if (Files.exists(dir)) {
                    try (Stream<Path> files = Files.list(dir)) {
                        files.filter(p -> p.toString().endsWith(".json"))
                                .forEach(p -> {
                                    try { Files.deleteIfExists(p); }
                                    catch (IOException e) { log.warn("Failed to delete persisted map {}: {}", p, e.getMessage()); }
                                });
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to clear persisted maps: {}", e.getMessage());
            }
        }
        log.info("Trained map cache fully invalidated (memory + disk)");
    }

    /** Invalidate a specific map from both in-memory cache and disk */
    public void invalidateMap(String sourceFormat, String sourceType, String targetFormat, String partnerId) {
        String cacheKey = sourceFormat + ":" + sourceType + "→" + targetFormat + "@" + (partnerId != null ? partnerId : "_");
        mapCache.remove(cacheKey);
        if (persistToDisk) {
            Path file = Path.of(storageDir, cacheKeyToFileName(cacheKey));
            try {
                Files.deleteIfExists(file);
                log.info("Invalidated trained map: {} (memory + disk)", cacheKey);
            } catch (IOException e) {
                log.warn("Failed to delete persisted map file {}: {}", file, e.getMessage());
            }
        }
    }

    // === Persistence methods ===

    /** Persist a trained map to disk as JSON */
    private void persistMap(String cacheKey, TrainedMap map) {
        if (!persistToDisk) return;
        try {
            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(cacheKeyToFileName(cacheKey));
            PersistedMapWrapper wrapper = PersistedMapWrapper.builder()
                    .cacheKey(cacheKey)
                    .map(map)
                    .persistedAtEpochMillis(Instant.now().toEpochMilli())
                    .build();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), wrapper);
            log.debug("Persisted trained map to {}", file);
        } catch (IOException e) {
            log.warn("Failed to persist trained map for {}: {}", cacheKey, e.getMessage());
        }
    }

    /** Load a persisted map from disk */
    private Optional<TrainedMap> loadPersistedMap(String cacheKey) {
        if (!persistToDisk) return Optional.empty();
        Path file = Path.of(storageDir, cacheKeyToFileName(cacheKey));
        if (!Files.exists(file)) return Optional.empty();
        try {
            PersistedMapWrapper wrapper = objectMapper.readValue(file.toFile(), PersistedMapWrapper.class);
            return Optional.ofNullable(wrapper.getMap());
        } catch (IOException e) {
            log.warn("Failed to load persisted map from {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** List all persisted maps on disk (for diagnostics) */
    public List<TrainedMapInfo> listPersistedMaps() {
        List<TrainedMapInfo> result = new ArrayList<>();
        if (!persistToDisk) return result;
        Path dir = Path.of(storageDir);
        if (!Files.exists(dir)) return result;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            PersistedMapWrapper wrapper = objectMapper.readValue(p.toFile(), PersistedMapWrapper.class);
                            TrainedMap map = wrapper.getMap();
                            if (map != null) {
                                result.add(TrainedMapInfo.builder()
                                        .mapKey(map.getMapKey())
                                        .version(map.getVersion())
                                        .confidence(map.getConfidence())
                                        .fieldCount(map.getFieldMappings() != null ? map.getFieldMappings().size() : 0)
                                        .sourceFormat(extractFromCacheKey(wrapper.getCacheKey(), "sourceFormat"))
                                        .targetFormat(extractFromCacheKey(wrapper.getCacheKey(), "targetFormat"))
                                        .partnerId(extractFromCacheKey(wrapper.getCacheKey(), "partnerId"))
                                        .persistedAt(Instant.ofEpochMilli(wrapper.getPersistedAtEpochMillis()))
                                        .build());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to read persisted map {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list persisted maps: {}", e.getMessage());
        }
        return result;
    }

    /** Get cache statistics */
    public CacheStats getCacheStats() {
        int persistedCount = 0;
        if (persistToDisk) {
            Path dir = Path.of(storageDir);
            if (Files.exists(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    persistedCount = (int) files.filter(p -> p.toString().endsWith(".json")).count();
                } catch (IOException e) {
                    log.warn("Failed to count persisted maps: {}", e.getMessage());
                }
            }
        }
        return CacheStats.builder()
                .inMemoryCount(mapCache.size())
                .persistedCount(persistedCount)
                .hitCount(hitCount.get())
                .missCount(missCount.get())
                .fetchCount(fetchCount.get())
                .build();
    }

    // === Utility methods for persistence ===

    /** Convert a cache key like "X12:850→JSON@ACME" to a safe filename */
    static String cacheKeyToFileName(String cacheKey) {
        // Replace unsafe filename chars: : → _, → → _, @ → _
        return cacheKey.replace(":", "_")
                .replace("→", "_to_")
                .replace("@", "_at_")
                .replace(" ", "_")
                + ".json";
    }

    /** Extract parts from a cache key like "X12:850→JSON@ACME" */
    private String extractFromCacheKey(String cacheKey, String part) {
        if (cacheKey == null) return null;
        try {
            // Format: sourceFormat:sourceType→targetFormat@partnerId
            String[] atParts = cacheKey.split("@");
            String partnerId = atParts.length > 1 ? atParts[1] : "_";
            String[] arrowParts = atParts[0].split("→");
            String targetFormat = arrowParts.length > 1 ? arrowParts[1] : null;
            String[] colonParts = arrowParts[0].split(":");
            String sourceFormat = colonParts[0];

            return switch (part) {
                case "sourceFormat" -> sourceFormat;
                case "targetFormat" -> targetFormat;
                case "partnerId" -> "_".equals(partnerId) ? null : partnerId;
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    // === Inner types ===

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrainedMap {
        private String mapKey;
        private int version;
        private int confidence;
        private List<FieldMapping> fieldMappings;
        private String generatedCode;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FieldMapping {
        private String sourceField;
        private String targetField;
        private String transform;
        private String transformParam;
        private int confidence;
        private String strategy;
        private String reasoning;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrainedConversionResult {
        private String output;
        private String mapKey;
        private int mapVersion;
        private int mapConfidence;
        private int fieldsApplied;
        private int fieldsSkipped;
        private int totalMappings;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TrainedMapInfo {
        private String mapKey;
        private int version;
        private int confidence;
        private int fieldCount;
        private String sourceFormat;
        private String targetFormat;
        private String partnerId;
        private Instant persistedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CacheStats {
        private int inMemoryCount;
        private int persistedCount;
        private int hitCount;
        private int missCount;
        private int fetchCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    static class PersistedMapWrapper {
        private String cacheKey;
        private TrainedMap map;
        /** Epoch millis — avoids requiring jackson-datatype-jsr310 for Instant */
        private long persistedAtEpochMillis;
    }

    private static class CachedMap {
        final TrainedMap map;
        final Instant expiresAt;

        CachedMap(TrainedMap map, Duration ttl) {
            this.map = map;
            this.expiresAt = Instant.now().plus(ttl);
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
