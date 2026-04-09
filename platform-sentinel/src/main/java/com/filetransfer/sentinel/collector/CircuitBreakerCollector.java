package com.filetransfer.sentinel.collector;

import com.filetransfer.sentinel.config.SentinelConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Polls the /actuator/circuit-breakers endpoint on every platform service
 * and maintains a live snapshot of Resilience4j circuit breaker states.
 *
 * <p>Runs every 30 seconds (faster than 5-min analyzer cycle — this is live monitoring data).
 * Uses the same plain RestTemplate as HealthCollector since /actuator/** is permit-all.
 *
 * <p>Services that are down or don't expose the endpoint get an UNKNOWN placeholder entry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CircuitBreakerCollector {

    private final RestTemplate restTemplate;
    private final SentinelConfig config;

    /** serviceName → list of circuit breaker snapshots (empty if service is down) */
    @Getter
    private final Map<String, List<Map<String, Object>>> serviceCircuitBreakers = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void collect() {
        config.getServices().forEach((serviceName, baseUrl) -> {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cbs = restTemplate.getForObject(
                        baseUrl + "/actuator/circuit-breakers", List.class);
                serviceCircuitBreakers.put(serviceName, cbs != null ? cbs : List.of());
            } catch (Exception e) {
                log.debug("CircuitBreakerCollector: {} unavailable — {}", serviceName, e.getMessage());
                serviceCircuitBreakers.put(serviceName, List.of());
            }
        });

        long open = getOpenCount();
        if (open > 0) {
            log.warn("CircuitBreakerCollector: {} circuit breaker(s) currently OPEN", open);
        }
    }

    /**
     * Returns all circuit breakers enriched with their parent service name,
     * sorted by service then by name. Services that are down appear as UNKNOWN entries.
     */
    public List<Map<String, Object>> getAllCircuitBreakers() {
        List<Map<String, Object>> all = new ArrayList<>();

        serviceCircuitBreakers.forEach((serviceName, cbs) -> {
            if (cbs.isEmpty()) {
                // Service down or doesn't expose circuit breakers — show as UNKNOWN
                Map<String, Object> placeholder = new LinkedHashMap<>();
                placeholder.put("service", serviceName);
                placeholder.put("name", serviceName);
                placeholder.put("state", "UNKNOWN");
                placeholder.put("failureRate", -1.0f);
                placeholder.put("slowCallRate", -1.0f);
                placeholder.put("bufferedCalls", 0);
                placeholder.put("failedCalls", 0);
                placeholder.put("successfulCalls", 0);
                placeholder.put("notPermittedCalls", 0);
                all.add(placeholder);
            } else {
                for (Map<String, Object> cb : cbs) {
                    Map<String, Object> enriched = new LinkedHashMap<>(cb);
                    enriched.put("service", serviceName);
                    all.add(enriched);
                }
            }
        });

        return all.stream()
                .sorted(Comparator.comparing(m -> (String) m.getOrDefault("service", "")))
                .collect(Collectors.toList());
    }

    public long getTotalCount() {
        return serviceCircuitBreakers.values().stream()
                .mapToLong(cbs -> Math.max(cbs.size(), 1)) // at least 1 per service (UNKNOWN)
                .sum();
    }

    public long getClosedCount() {
        return serviceCircuitBreakers.values().stream()
                .flatMap(List::stream)
                .filter(cb -> "CLOSED".equals(cb.get("state")))
                .count();
    }

    public long getOpenCount() {
        return serviceCircuitBreakers.values().stream()
                .flatMap(List::stream)
                .filter(cb -> "OPEN".equals(cb.get("state")))
                .count();
    }

    public long getHalfOpenCount() {
        return serviceCircuitBreakers.values().stream()
                .flatMap(List::stream)
                .filter(cb -> "HALF_OPEN".equals(cb.get("state")))
                .count();
    }

    public long getUnknownCount() {
        return serviceCircuitBreakers.values().stream()
                .filter(List::isEmpty)
                .count();
    }
}
