package com.filetransfer.sentinel.collector;

import com.filetransfer.sentinel.config.SentinelConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsCollector {

    private final RestTemplate restTemplate;
    private final SentinelConfig config;

    @Getter
    private volatile Map<String, Object> dashboardData = Map.of();

    @Getter
    private volatile double failureRate = 0.0;
    @Getter
    private volatile double p95Latency = 0.0;
    @Getter
    private volatile long transfersToday = 0;
    @Getter
    private volatile long transfersLastHour = 0;

    @SuppressWarnings("unchecked")
    public void collect() {
        try {
            String analyticsUrl = config.getServices().getOrDefault("analytics-service", "http://localhost:8090");
            var data = restTemplate.getForObject(analyticsUrl + "/api/v1/analytics/dashboard", Map.class);
            if (data == null) return;
            dashboardData = data;

            if (data.get("successRateToday") instanceof Number n) {
                failureRate = 100.0 - n.doubleValue();
            }
            if (data.get("totalTransfersToday") instanceof Number n) {
                transfersToday = n.longValue();
            }
            if (data.get("totalTransfersLastHour") instanceof Number n) {
                transfersLastHour = n.longValue();
            }

            // Extract p95 from time series if available
            if (data.get("transfersPerHour") instanceof List<?> series) {
                double sumP95 = 0;
                int count = 0;
                for (Object entry : series) {
                    if (entry instanceof Map<?, ?> m && m.get("p95LatencyMs") instanceof Number n) {
                        double val = n.doubleValue();
                        if (val > 0) { sumP95 += val; count++; }
                    }
                }
                p95Latency = count > 0 ? sumP95 / count : 0.0;
            }

            log.debug("MetricsCollector: failureRate={:.1f}%, p95={:.0f}ms, transfers={}",
                    failureRate, p95Latency, transfersToday);
        } catch (Exception e) {
            log.debug("MetricsCollector: Analytics service unavailable — {}", e.getMessage());
        }
    }
}
