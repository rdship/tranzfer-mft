package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the License Service (port 8089).
 * Provides license validation, feature entitlement checking, and catalog queries.
 *
 * <p>Error strategy: <b>graceful degradation with caching</b> — license
 * validation failures should use cached results for up to 24 hours.
 * The existing LicenseClient in license-service handles the caching;
 * this client provides the raw API access for other services.
 */
@Slf4j
@Component
public class LicenseServiceClient extends ResilientServiceClient {

    public LicenseServiceClient(RestTemplate restTemplate,
                                PlatformConfig platformConfig,
                                ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getLicenseService(), "license-service");
    }

    /**
     * Validate a license.
     *
     * @param licenseKey the license key
     * @param serviceType the service type requesting validation
     * @param hostId the cluster/host identifier
     * @return validation response map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String licenseKey, String serviceType, String hostId) {
        try {
            Map<String, String> request = Map.of(
                    "licenseKey", licenseKey,
                    "serviceType", serviceType,
                    "hostId", hostId
            );
            return withResilience("validate",
                    () -> post("/api/v1/licenses/validate", request, Map.class));
        } catch (Exception e) {
            log.warn("License validation failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Activate a trial license. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> activateTrial(String serviceType, String hostId) {
        try {
            return withResilience("activateTrial",
                    () -> post("/api/v1/licenses/trial",
                            Map.of("serviceType", serviceType, "hostId", hostId), Map.class));
        } catch (Exception e) {
            log.warn("Trial activation failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Get the component catalog (features and tiers). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCatalog() {
        try {
            return withResilience("getCatalog",
                    () -> get("/api/v1/licenses/catalog/components", Map.class));
        } catch (Exception e) {
            log.warn("License catalog unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Get product tier definitions. */
    public List<Map<String, Object>> getTiers() {
        try {
            return withResilience("getTiers",
                    () -> get("/api/v1/licenses/catalog/tiers",
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception e) {
            log.warn("License tiers unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Check which components a license key is entitled to use. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEntitledComponents(String licenseKey, String serviceType, String hostId) {
        try {
            return withResilience("getEntitledComponents",
                    () -> post("/api/v1/licenses/catalog/entitled",
                            Map.of("licenseKey", licenseKey, "serviceType", serviceType, "hostId", hostId),
                            Map.class));
        } catch (Exception e) {
            log.warn("Entitled components check failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    protected String healthPath() {
        return "/api/v1/licenses/health";
    }
}
