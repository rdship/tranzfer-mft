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
 * Client for the Config Service (port 8084).
 * Provides access to file flows, connectors, delivery endpoints, external destinations,
 * security profiles, SLA configuration, and activity monitoring.
 *
 * <p>Error strategy: <b>fail-fast</b> for configuration lookups (services
 * need correct config to operate); <b>graceful degradation</b> for activity queries.
 */
@Slf4j
@Component
public class ConfigServiceClient extends ResilientServiceClient {

    public ConfigServiceClient(RestTemplate restTemplate,
                               PlatformConfig platformConfig,
                               ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getConfigService(), "config-service");
    }

    // ── File Flows ──────────────────────────────────────────────────────

    /** Get all file flows. */
    public List<Map<String, Object>> getAllFlows() {
        return withResilience("getAllFlows",
                () -> get("/api/flows", new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    /** Get a specific file flow by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFlow(UUID flowId) {
        return withResilience("getFlow",
                () -> get("/api/flows/" + flowId, Map.class));
    }

    /** Get flow executions, optionally filtered. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchExecutions(String trackId, String filename, String status,
                                                 int page, int size) {
        StringBuilder path = new StringBuilder("/api/flows/executions?page=" + page + "&size=" + size);
        if (trackId != null) path.append("&trackId=").append(trackId);
        if (filename != null) path.append("&filename=").append(filename);
        if (status != null) path.append("&status=").append(status);
        String finalPath = path.toString();
        return withResilience("searchExecutions",
                () -> get(finalPath, Map.class));
    }

    /** Get available flow step types. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStepTypes() {
        try {
            return withResilience("getStepTypes",
                    () -> get("/api/flows/step-types", Map.class));
        } catch (Exception e) {
            log.warn("Failed to fetch step types: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── Delivery Endpoints ──────────────────────────────────────────────

    /** List all delivery endpoints, optionally filtered by protocol or tag. */
    public List<Map<String, Object>> listDeliveryEndpoints(String protocol, String tag) {
        StringBuilder path = new StringBuilder("/api/delivery-endpoints?");
        if (protocol != null) path.append("protocol=").append(protocol).append("&");
        if (tag != null) path.append("tag=").append(tag);
        String finalPath = path.toString();
        return withResilience("listDeliveryEndpoints",
                () -> get(finalPath, new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    /** Get a delivery endpoint by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeliveryEndpoint(UUID id) {
        return withResilience("getDeliveryEndpoint",
                () -> get("/api/delivery-endpoints/" + id, Map.class));
    }

    /** Get delivery endpoint summary (counts by protocol). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deliveryEndpointSummary() {
        try {
            return withResilience("deliveryEndpointSummary",
                    () -> get("/api/delivery-endpoints/summary", Map.class));
        } catch (Exception e) {
            log.warn("Delivery endpoint summary unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── External Destinations ───────────────────────────────────────────

    /** List all external destinations, optionally filtered by type. */
    public List<Map<String, Object>> listExternalDestinations(String type) {
        String path = type != null ? "/api/external-destinations?type=" + type
                                   : "/api/external-destinations";
        return withResilience("listExternalDestinations",
                () -> get(path, new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    /** Get an external destination by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExternalDestination(UUID id) {
        return withResilience("getExternalDestination",
                () -> get("/api/external-destinations/" + id, Map.class));
    }

    // ── Connectors ──────────────────────────────────────────────────────

    /** List all webhook connectors. */
    public List<Map<String, Object>> listConnectors() {
        return withResilience("listConnectors",
                () -> get("/api/connectors", new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    /** Get available connector types. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getConnectorTypes() {
        try {
            return withResilience("getConnectorTypes",
                    () -> get("/api/connectors/types", Map.class));
        } catch (Exception e) {
            log.warn("Connector types unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Test a connector by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testConnector(UUID connectorId) {
        return withResilience("testConnector",
                () -> post("/api/connectors/" + connectorId + "/test", null, Map.class));
    }

    // ── AS2 Partnerships ────────────────────────────────────────────────

    /** List AS2 partnerships. */
    public List<Map<String, Object>> listAs2Partnerships() {
        return withResilience("listAs2Partnerships",
                () -> get("/api/as2-partnerships",
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    // ── Security Profiles ───────────────────────────────────────────────

    /** List security profiles. */
    public List<Map<String, Object>> listSecurityProfiles() {
        return withResilience("listSecurityProfiles",
                () -> get("/api/security-profiles",
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
    }

    // ── Activity ────────────────────────────────────────────────────────

    /** Get activity snapshot. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> activitySnapshot() {
        try {
            return withResilience("activitySnapshot",
                    () -> get("/api/activity/snapshot", Map.class));
        } catch (Exception e) {
            log.warn("Activity snapshot unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── SLA ─────────────────────────────────────────────────────────────

    /** List SLA agreements. */
    public List<Map<String, Object>> listSlaAgreements() {
        try {
            return withResilience("listSlaAgreements",
                    () -> get("/api/sla", new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception e) {
            log.warn("SLA data unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get SLA breaches. */
    public List<Map<String, Object>> getSlaBreaches() {
        try {
            return withResilience("getSlaBreaches",
                    () -> get("/api/sla/breaches",
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception e) {
            log.warn("SLA breach data unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
