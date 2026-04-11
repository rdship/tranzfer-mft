package com.filetransfer.fabric.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.client.InMemoryFabricClient;
import com.filetransfer.fabric.client.KafkaFabricClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring configuration for the Fabric layer.
 *
 * Behavior:
 * 1. If fabric.enabled=false → InMemoryFabricClient (no-op, useful for tests)
 * 2. If fabric.enabled=true and Redpanda reachable → KafkaFabricClient
 * 3. If fabric.enabled=true and Redpanda unreachable → KafkaFabricClient with degraded mode (logs warning, falls back to in-memory for publish, skips subscribe)
 */
@Configuration
@EnableConfigurationProperties(FabricProperties.class)
@Slf4j
public class FabricConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper fabricObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    @Primary
    public FabricClient fabricClient(FabricProperties properties, ObjectMapper mapper) {
        if (!properties.isEnabled()) {
            log.info("[Fabric] Disabled via fabric.enabled=false — using in-memory client");
            return new InMemoryFabricClient(mapper);
        }

        log.info("[Fabric] Initializing Kafka client → {}", properties.getBrokerUrl());
        try {
            KafkaFabricClient kafka = new KafkaFabricClient(properties, mapper);
            if (kafka.isHealthy()) {
                log.info("[Fabric] ✓ Kafka client healthy, broker reachable at {}", properties.getBrokerUrl());
                return kafka;
            } else {
                log.warn("[Fabric] ⚠ Broker unreachable at {} — falling back to in-memory client. " +
                         "Services will still start but fabric events will NOT be distributed. " +
                         "Check Redpanda container and restart to enable distributed mode.",
                         properties.getBrokerUrl());
                return new InMemoryFabricClient(mapper);
            }
        } catch (Exception e) {
            log.error("[Fabric] Failed to initialize Kafka client: {} — falling back to in-memory", e.getMessage());
            return new InMemoryFabricClient(mapper);
        }
    }
}
