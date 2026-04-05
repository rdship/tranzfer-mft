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
public class ConfigServiceClient extends BaseServiceClient {

    public ConfigServiceClient(RestTemplate restTemplate,
                               PlatformConfig platformConfig,
                               ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getConfigService(), "config-service");
    }

    // ── File Flows ──────────────────────────────────────────────────────

    /** Get all file flows. */
    public List<Map<String, Object>> getAllFlows() {
        try {
            return get("/api/flows", new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("getAllFlows", e);
        }
    }

    /** Get a specific file flow by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFlow(UUID flowId) {
        try {
            return get("/api/flows/" + flowId, Map.class);
        } catch (Exception e) {
            throw serviceError("getFlow", e);
        }
    }

    /** Get flow executions, optionally filtered. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchExecutions(String trackId, String filename, String status,
                                                 int page, int size) {
        try {
            StringBuilder path = new StringBuilder("/api/flows/executions?page=" + page + "&size=" + size);
            if (trackId != null) path.append("&trackId=").append(trackId);
            if (filename != null) path.append("&filename=").append(filename);
            if (status != null) path.append("&status=").append(status);
            return get(path.toString(), Map.class);
        } catch (Exception e) {
            throw serviceError("searchExecutions", e);
        }
    }

    /** Get available flow step types. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStepTypes() {
        try {
            return get("/api/flows/step-types", Map.class);
        } catch (Exception e) {
            log.warn("Failed to fetch step types: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── Delivery Endpoints ──────────────────────────────────────────────

    /** List all delivery endpoints, optionally filtered by protocol or tag. */
    public List<Map<String, Object>> listDeliveryEndpoints(String protocol, String tag) {
        try {
            StringBuilder path = new StringBuilder("/api/delivery-endpoints?");
            if (protocol != null) path.append("protocol=").append(protocol).append("&");
            if (tag != null) path.append("tag=").append(tag);
            return get(path.toString(), new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listDeliveryEndpoints", e);
        }
    }

    /** Get a delivery endpoint by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeliveryEndpoint(UUID id) {
        try {
            return get("/api/delivery-endpoints/" + id, Map.class);
        } catch (Exception e) {
            throw serviceError("getDeliveryEndpoint", e);
        }
    }

    /** Get delivery endpoint summary (counts by protocol). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deliveryEndpointSummary() {
        try {
            return get("/api/delivery-endpoints/summary", Map.class);
        } catch (Exception e) {
            log.warn("Delivery endpoint summary unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── External Destinations ───────────────────────────────────────────

    /** List all external destinations, optionally filtered by type. */
    public List<Map<String, Object>> listExternalDestinations(String type) {
        try {
            String path = type != null ? "/api/external-destinations?type=" + type
                                       : "/api/external-destinations";
            return get(path, new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listExternalDestinations", e);
        }
    }

    /** Get an external destination by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExternalDestination(UUID id) {
        try {
            return get("/api/external-destinations/" + id, Map.class);
        } catch (Exception e) {
            throw serviceError("getExternalDestination", e);
        }
    }

    // ── Connectors ──────────────────────────────────────────────────────

    /** List all webhook connectors. */
    public List<Map<String, Object>> listConnectors() {
        try {
            return get("/api/connectors", new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listConnectors", e);
        }
    }

    /** Get available connector types. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getConnectorTypes() {
        try {
            return get("/api/connectors/types", Map.class);
        } catch (Exception e) {
            log.warn("Connector types unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Test a connector by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testConnector(UUID connectorId) {
        try {
            return post("/api/connectors/" + connectorId + "/test", null, Map.class);
        } catch (Exception e) {
            throw serviceError("testConnector", e);
        }
    }

    // ── AS2 Partnerships ────────────────────────────────────────────────

    /** List AS2 partnerships. */
    public List<Map<String, Object>> listAs2Partnerships() {
        try {
            return get("/api/as2-partnerships",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listAs2Partnerships", e);
        }
    }

    // ── Security Profiles ───────────────────────────────────────────────

    /** List security profiles. */
    public List<Map<String, Object>> listSecurityProfiles() {
        try {
            return get("/api/security-profiles",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listSecurityProfiles", e);
        }
    }

    // ── Activity ────────────────────────────────────────────────────────

    /** Get activity snapshot. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> activitySnapshot() {
        try {
            return get("/api/activity/snapshot", Map.class);
        } catch (Exception e) {
            log.warn("Activity snapshot unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ── SLA ─────────────────────────────────────────────────────────────

    /** List SLA agreements. */
    public List<Map<String, Object>> listSlaAgreements() {
        try {
            return get("/api/sla", new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("SLA data unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get SLA breaches. */
    public List<Map<String, Object>> getSlaBreaches() {
        try {
            return get("/api/sla/breaches",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("SLA breach data unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
