package com.filetransfer.sentinel.collector;

import com.filetransfer.sentinel.config.SentinelConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthCollector {

    private final RestTemplate restTemplate;
    private final SentinelConfig config;

    @Getter
    private final Map<String, ServiceHealth> serviceHealth = new ConcurrentHashMap<>();

    public void collect() {
        config.getServices().forEach((name, baseUrl) -> {
            try {
                long start = System.currentTimeMillis();
                var response = restTemplate.getForObject(baseUrl + "/actuator/health/liveness", Map.class);
                long latency = System.currentTimeMillis() - start;
                String status = response != null && "UP".equals(response.get("status")) ? "UP" : "UNKNOWN";
                serviceHealth.put(name, new ServiceHealth(status, latency, null));
            } catch (Exception e) {
                serviceHealth.put(name, new ServiceHealth("DOWN", -1, e.getMessage()));
            }
        });

        long up = serviceHealth.values().stream().filter(h -> "UP".equals(h.status())).count();
        long total = serviceHealth.size();
        log.debug("HealthCollector: {}/{} services UP", up, total);
    }

    public long getHealthyCount() {
        return serviceHealth.values().stream().filter(h -> "UP".equals(h.status())).count();
    }

    public long getTotalCount() {
        return serviceHealth.size();
    }

    public boolean isServiceUp(String name) {
        ServiceHealth h = serviceHealth.get(name);
        return h != null && "UP".equals(h.status());
    }

    public record ServiceHealth(String status, long latencyMs, String error) {}
}
