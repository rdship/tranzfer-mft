package com.filetransfer.fabric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Fabric layer.
 *
 * Defaults: Fabric is ENABLED by default (production-ready rollout).
 * If Redpanda is unreachable at startup, services log a warning and fall
 * back to in-memory fabric client (no crash).
 */
@Data
@ConfigurationProperties(prefix = "fabric")
public class FabricProperties {

    /** Master switch. If false, fabric is fully dormant. */
    private boolean enabled = true;

    /** Kafka/Redpanda bootstrap servers. */
    private String brokerUrl = "redpanda:9092";

    /** Timeout in ms for broker health check at startup. */
    private long healthCheckTimeoutMs = 5000;

    /** Producer properties. */
    private Producer producer = new Producer();

    /** Consumer properties. */
    private Consumer consumer = new Consumer();

    /** Flow processing flags. */
    private Flow flow = new Flow();

    /** Event publishing flags. */
    private Events events = new Events();

    /** Checkpoint store flags. */
    private Checkpoint checkpoint = new Checkpoint();

    /** Observability endpoint flags. */
    private Observability observability = new Observability();

    @Data
    public static class Producer {
        private String acks = "all";
        private int retries = 5;
        private int lingerMs = 10;
        private int batchSize = 16384;
        private String compressionType = "snappy";
    }

    @Data
    public static class Consumer {
        private String autoOffsetReset = "earliest";
        private boolean enableAutoCommit = false;   // manual commit for safety
        private int maxPollRecords = 100;
        private int sessionTimeoutMs = 30000;
        private int heartbeatIntervalMs = 10000;
    }

    @Data
    public static class Flow {
        private boolean publish = true;          // publish to flow.intake
        private boolean consume = true;           // consume from flow topics
        private int partitionCount = 32;
        private long leaseDurationSeconds = 300;  // 5 min default
    }

    @Data
    public static class Events {
        private boolean accountPublish = true;
        private boolean accountConsume = true;
        private boolean flowRulePublish = true;
        private boolean flowRuleConsume = true;
        private boolean notificationPublish = true;
        private boolean notificationConsume = true;
    }

    @Data
    public static class Checkpoint {
        private boolean enabled = true;
    }

    @Data
    public static class Observability {
        private boolean enabled = true;
    }
}
