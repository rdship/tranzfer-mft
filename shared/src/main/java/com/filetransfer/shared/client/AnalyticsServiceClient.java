package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the Analytics Service (port 8090).
 * Provides dashboard data, predictions, time-series metrics, and alert management.
 *
 * <p>Error strategy: <b>graceful degradation</b> — analytics failures should
 * never block core file transfer operations.
 */
@Slf4j
@Component
public class AnalyticsServiceClient extends BaseServiceClient {

    public AnalyticsServiceClient(RestTemplate restTemplate,
                                  PlatformConfig platformConfig,
                                  ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getAnalyticsService(), "analytics-service");
    }

    /** Get the dashboard summary (transfer volumes, success rates, etc.). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDashboard() {
        try {
            return get("/api/v1/analytics/dashboard", Map.class);
        } catch (Exception e) {
            log.warn("Analytics dashboard unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Get scaling predictions for all services. */
    public List<Map<String, Object>> getAllPredictions() {
        try {
            return get("/api/v1/analytics/predictions",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Analytics predictions unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get scaling prediction for a specific service type. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPrediction(String serviceType) {
        try {
            return get("/api/v1/analytics/predictions/" + serviceType, Map.class);
        } catch (Exception e) {
            log.warn("Analytics prediction unavailable for {}: {}", serviceType, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Get time-series metrics, optionally filtered by service and time window. */
    public List<Map<String, Object>> getTimeSeries(String service, int hours) {
        try {
            String path = "/api/v1/analytics/timeseries?hours=" + hours;
            if (service != null) path += "&service=" + service;
            return get(path, new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Analytics time-series unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get all alert rules. */
    public List<Map<String, Object>> getAlertRules() {
        try {
            return get("/api/v1/analytics/alerts",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Analytics alert rules unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Create a new alert rule. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createAlertRule(Map<String, Object> rule) {
        try {
            return post("/api/v1/analytics/alerts", rule, Map.class);
        } catch (Exception e) {
            throw serviceError("createAlertRule", e);
        }
    }

    /** Delete an alert rule. */
    public void deleteAlertRule(UUID ruleId) {
        try {
            delete("/api/v1/analytics/alerts/" + ruleId);
        } catch (Exception e) {
            log.warn("Failed to delete alert rule {}: {}", ruleId, e.getMessage());
        }
    }
}
