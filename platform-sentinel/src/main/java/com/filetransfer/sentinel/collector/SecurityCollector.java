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
public class SecurityCollector {

    private final RestTemplate restTemplate;
    private final SentinelConfig config;

    @Getter
    private volatile List<Map<String, Object>> activeAnomalies = List.of();

    @Getter
    private volatile long quarantineCount = 0;

    @Getter
    private volatile long screeningHits = 0;

    @SuppressWarnings("unchecked")
    public void collect() {
        try {
            String aiUrl = config.getServices().getOrDefault("ai-engine", "http://localhost:8091");
            var response = restTemplate.getForObject(aiUrl + "/api/v1/ai/anomalies", Map.class);
            if (response != null && response.get("anomalies") instanceof List<?> list) {
                activeAnomalies = (List<Map<String, Object>>) list;
            }
        } catch (Exception e) {
            log.debug("SecurityCollector: AI engine unavailable — {}", e.getMessage());
            activeAnomalies = List.of();
        }

        try {
            String screeningUrl = config.getServices().getOrDefault("screening-service", "http://localhost:8092");
            var response = restTemplate.getForObject(screeningUrl + "/api/v1/screening/hits", Map.class);
            if (response != null && response.get("totalHits") instanceof Number n) {
                screeningHits = n.longValue();
            }
        } catch (Exception e) {
            log.debug("SecurityCollector: Screening service unavailable — {}", e.getMessage());
        }
    }
}
