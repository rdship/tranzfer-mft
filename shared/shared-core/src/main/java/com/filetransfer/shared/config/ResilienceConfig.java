package com.filetransfer.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Platform-wide resilience defaults. Individual service clients can override
 * these values in their own application.yml.
 *
 * <p>Usage in application.yml:
 * <pre>
 * platform:
 *   resilience:
 *     circuit-breaker:
 *       failure-rate-threshold: 50
 *       sliding-window-size: 10
 *       wait-duration-seconds: 30
 *     retry:
 *       max-attempts: 3
 *       wait-duration-ms: 500
 *     timeout:
 *       duration-seconds: 30
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "platform.resilience")
@Getter @Setter
public class ResilienceConfig {

    private CircuitBreakerDefaults circuitBreaker = new CircuitBreakerDefaults();
    private RetryDefaults retry = new RetryDefaults();
    private TimeoutDefaults timeout = new TimeoutDefaults();

    @Getter @Setter
    public static class CircuitBreakerDefaults {
        private float failureRateThreshold = 50;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
        private int waitDurationSeconds = 30;
        private int permittedCallsInHalfOpen = 3;
    }

    @Getter @Setter
    public static class RetryDefaults {
        private int maxAttempts = 3;
        private long waitDurationMs = 500;
    }

    @Getter @Setter
    public static class TimeoutDefaults {
        private int durationSeconds = 30;
    }
}
