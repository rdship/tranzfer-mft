package com.filetransfer.fabric;

import java.util.Collection;

/**
 * Core Fabric API — the only thing services interact with directly.
 *
 * Implementations:
 * - KafkaFabricClient (real Redpanda/Kafka)
 * - InMemoryFabricClient (test/fallback when broker unreachable)
 */
public interface FabricClient {

    /**
     * Publish a message to a topic. Synchronous — returns after broker ack.
     *
     * @param topic e.g., "flow.intake", "events.account"
     * @param key   partition key (usually trackId or entity id)
     * @param value the payload (will be serialized to JSON)
     */
    void publish(String topic, String key, Object value);

    /**
     * Subscribe to a topic. Handler is invoked for each message received.
     * Handler is called on a dedicated consumer thread.
     * If handler throws, message is NOT committed and will be redelivered.
     *
     * @param topic    topic name
     * @param groupId  consumer group (all instances of same service share this)
     * @param handler  called once per message
     */
    void subscribe(String topic, String groupId, MessageHandler handler);

    /**
     * Pre-create a batch of topics on the broker in a single round-trip.
     * Optional optimization — callers that know up-front which topics they
     * will subscribe to can invoke this once before looping subscribe() calls
     * to amortize cluster-metadata cost. Implementations that don't need
     * pre-creation (e.g. in-memory) can no-op. Idempotent.
     *
     * <p>Added in R94 for boot-time optimization (FlowFabricConsumer subscribes
     * to 20 step-type topics).
     */
    default void ensureTopics(Collection<String> topics) {
        // Default: no-op. InMemoryFabricClient auto-creates on first use;
        // KafkaFabricClient overrides to batch createTopics().
    }

    /**
     * Check if the underlying broker is reachable.
     * Used by health checks and graceful degradation logic.
     */
    boolean isHealthy();

    /**
     * Returns true if this client is a real distributed implementation
     * (as opposed to in-memory fallback).
     */
    boolean isDistributed();

    /**
     * Handler for incoming messages.
     */
    @FunctionalInterface
    interface MessageHandler {
        void handle(FabricEvent event) throws Exception;
    }
}
