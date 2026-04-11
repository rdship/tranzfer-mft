package com.filetransfer.fabric.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.FabricEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fallback FabricClient.
 * Used when:
 * - fabric.enabled=false
 * - Redpanda is unreachable at startup
 * - Tests
 *
 * Publishes go to in-memory buffers and are immediately dispatched to handlers
 * registered on the same topic. This is NOT distributed — messages published
 * on one instance are invisible to other instances.
 */
@RequiredArgsConstructor
@Slf4j
public class InMemoryFabricClient implements FabricClient {

    private final ObjectMapper mapper;
    private final Map<String, List<MessageHandler>> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> offsets = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String key, Object value) {
        try {
            String jsonValue = mapper.writeValueAsString(value);
            long offset = offsets.computeIfAbsent(topic, t -> new AtomicLong()).getAndIncrement();

            FabricEvent event = FabricEvent.builder()
                .topic(topic)
                .key(key)
                .partition(0)
                .offset(offset)
                .timestamp(Instant.now())
                .rawValue(jsonValue)
                .build();

            List<MessageHandler> topicHandlers = handlers.get(topic);
            if (topicHandlers != null) {
                for (MessageHandler h : topicHandlers) {
                    try {
                        h.handle(event);
                    } catch (Exception e) {
                        log.warn("[Fabric/InMemory] Handler failed for topic {}: {}", topic, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Fabric/InMemory] Failed to publish to {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void subscribe(String topic, String groupId, MessageHandler handler) {
        handlers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("[Fabric/InMemory] Subscribed to {} (group: {})", topic, groupId);
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public boolean isDistributed() {
        return false;
    }
}
