package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Adds circuit breaker, retry, and timeout patterns to any BaseServiceClient.
 *
 * <p>Automatically wraps outbound REST calls with:
 * <ul>
 *   <li><b>Circuit Breaker</b>: Opens after 50% failure rate in 10-call sliding window,
 *       half-open after 30s, requires 5 minimum calls before evaluating</li>
 *   <li><b>Retry</b>: 3 attempts with 500ms wait, only for connection errors
 *       (ResourceAccessException, ConnectException)</li>
 * </ul>
 *
 * <p>Usage: Subclasses extend this instead of BaseServiceClient. No code changes needed
 * in existing service clients beyond changing the extends and wrapping REST calls
 * in {@link #withResilience(String, Supplier)}.
 */
@Slf4j
public abstract class ResilientServiceClient extends BaseServiceClient {

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    protected ResilientServiceClient(RestTemplate restTemplate,
                                     PlatformConfig platformConfig,
                                     ServiceClientProperties.ServiceEndpoint endpoint,
                                     String serviceName) {
        super(restTemplate, platformConfig, endpoint, serviceName);

        // Circuit breaker: opens after 50% failure rate in sliding window of 10 calls
        this.circuitBreaker = CircuitBreaker.of(serviceName,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .recordExceptions(ResourceAccessException.class, ConnectException.class)
                        .ignoreExceptions(HttpClientErrorException.class)
                        .build());

        // Retry: 3 attempts with 500ms wait, only on connection errors
        this.retry = Retry.of(serviceName,
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(500))
                        .retryExceptions(ResourceAccessException.class, ConnectException.class)
                        .ignoreExceptions(HttpClientErrorException.class)
                        .build());

        // Log state transitions
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        log.warn("[{}] Circuit breaker: {}", serviceName, event.getStateTransition()));
        retry.getEventPublisher()
                .onRetry(event ->
                        log.warn("[{}] Retry attempt #{}: {}", serviceName,
                                event.getNumberOfRetryAttempts(), event.getLastThrowable().getMessage()));
    }

    /**
     * Execute a supplier with circuit breaker + retry protection.
     * Use this in subclass methods that make REST calls.
     *
     * @param operation descriptive name for logging (e.g. "encrypt", "listKeys")
     * @param supplier  the REST call to protect
     * @param <T>       return type
     * @return the supplier's result
     * @throws RuntimeException wrapping the original exception if all attempts fail
     */
    protected <T> T withResilience(String operation, Supplier<T> supplier) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, supplier));
        try {
            return decorated.get();
        } catch (Exception e) {
            log.error("[{}] {} failed after resilience: {}", serviceName(), operation, e.getMessage());
            throw serviceError(operation, e);
        }
    }

    /**
     * Execute a runnable with circuit breaker + retry protection (void operations).
     *
     * @param operation descriptive name for logging
     * @param runnable  the REST call to protect
     */
    protected void withResilience(String operation, Runnable runnable) {
        Runnable decorated = CircuitBreaker.decorateRunnable(circuitBreaker,
                Retry.decorateRunnable(retry, runnable));
        try {
            decorated.run();
        } catch (Exception e) {
            log.error("[{}] {} failed after resilience: {}", serviceName(), operation, e.getMessage());
            throw serviceError(operation, e);
        }
    }

    /** Expose circuit breaker state for health checks and monitoring. */
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /** Expose circuit breaker metrics for dashboards and alerts. */
    public CircuitBreaker.Metrics getCircuitBreakerMetrics() {
        return circuitBreaker.getMetrics();
    }

    /**
     * Overrides health check to short-circuit when the circuit breaker is OPEN.
     * Avoids hammering a service that is already known to be down.
     */
    @Override
    public boolean isHealthy() {
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.debug("[{}] Circuit breaker is OPEN — skipping health check", serviceName());
            return false;
        }
        return super.isHealthy();
    }
}
