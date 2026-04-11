package com.filetransfer.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for a single fabric event (one Kafka message).
 * Contains the raw value plus metadata about the source topic/partition/offset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FabricEvent {
    private String topic;
    private String key;              // partition key (usually trackId)
    private int partition;
    private long offset;
    private Instant timestamp;
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();
    private String rawValue;          // JSON string

    /**
     * Deserialize the raw JSON value to a typed object.
     */
    public <T> T payload(Class<T> type, ObjectMapper mapper) {
        if (rawValue == null) return null;
        try {
            return mapper.readValue(rawValue, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse fabric event payload: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payloadAsMap(ObjectMapper mapper) {
        return payload(Map.class, mapper);
    }
}
