package com.filetransfer.shared.actuator;

import com.filetransfer.shared.client.ResilientServiceClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Spring Boot Actuator endpoint that exposes Resilience4j circuit breaker states
 * for all ResilientServiceClient beans present in the current service.
 *
 * <p>Accessible at {@code /actuator/circuit-breakers} (no auth required — covered
 * by the platform-wide {@code /actuator/**} permit-all security rule).
 *
 * <p>Used by platform-sentinel to aggregate real-time CB states across all 18+ services.
 * Returns an empty list for services that have no ResilientServiceClient beans.
 */
@Component
@Endpoint(id = "circuitbreakers")
public class CircuitBreakerActuatorEndpoint {

    private final List<ResilientServiceClient> clients;

    /** ObjectProvider is used so the endpoint works even in services with no CB clients. */
    public CircuitBreakerActuatorEndpoint(ObjectProvider<ResilientServiceClient> clientProvider) {
        this.clients = clientProvider.stream().toList();
    }

    @ReadOperation
    public List<Map<String, Object>> circuitBreakers() {
        return clients.stream()
                .map(client -> {
                    CircuitBreaker.State state = client.getCircuitBreakerState();
                    CircuitBreaker.Metrics metrics = client.getCircuitBreakerMetrics();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", client.getServiceName());
                    entry.put("state", state.name());
                    entry.put("failureRate", metrics.getFailureRate());
                    entry.put("slowCallRate", metrics.getSlowCallRate());
                    entry.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
                    entry.put("failedCalls", metrics.getNumberOfFailedCalls());
                    entry.put("successfulCalls", metrics.getNumberOfSuccessfulCalls());
                    entry.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
                    return (Map<String, Object>) entry;
                })
                .toList();
    }
}
