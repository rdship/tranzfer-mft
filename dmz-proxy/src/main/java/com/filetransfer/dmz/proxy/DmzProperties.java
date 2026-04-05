package com.filetransfer.dmz.proxy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "dmz")
public class DmzProperties {
    private List<PortMapping> mappings;

    /** AI-powered security configuration */
    private Security security = new Security();

    @Data
    public static class Security {
        /** Enable AI-powered security layer */
        private boolean enabled = true;

        /** AI engine base URL for verdict and event APIs */
        private String aiEngineUrl = "http://ai-engine:8091";

        /** Timeout for verdict queries to AI engine (ms) */
        private long verdictTimeoutMs = 200;

        /** Default max connections per IP per minute */
        private int defaultRatePerMinute = 60;

        /** Default max concurrent connections per IP */
        private int defaultMaxConcurrent = 20;

        /** Default max bytes per IP per minute (500 MB) */
        private long defaultMaxBytesPerMinute = 500_000_000L;

        /** Global max connections per minute (DDoS threshold) */
        private int globalRatePerMinute = 10_000;

        /** Event queue capacity */
        private int eventQueueCapacity = 10_000;

        /** Event batch size for flushing to AI engine */
        private int eventBatchSize = 50;

        /** Event flush interval in milliseconds */
        private long eventFlushIntervalMs = 5_000;
    }
}
