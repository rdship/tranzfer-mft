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
 * Client for the Onboarding API (port 8080).
 * Provides account management, service registry, and transfer operations.
 *
 * <p>Error strategy: <b>fail-fast</b> for account operations;
 * <b>graceful degradation</b> for registry lookups.
 */
@Slf4j
@Component
public class OnboardingApiClient extends BaseServiceClient {

    public OnboardingApiClient(RestTemplate restTemplate,
                               PlatformConfig platformConfig,
                               ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getOnboardingApi(), "onboarding-api");
    }

    // ── Accounts ────────────────────────────────────────────────────────

    /** Get an account by ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAccount(UUID accountId) {
        try {
            return get("/api/accounts/" + accountId, Map.class);
        } catch (Exception e) {
            throw serviceError("getAccount", e);
        }
    }

    /** List all accounts. */
    public List<Map<String, Object>> listAccounts() {
        try {
            return get("/api/accounts",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw serviceError("listAccounts", e);
        }
    }

    // ── Service Registry ────────────────────────────────────────────────

    /** List registered services, optionally filtered by type. */
    public List<Map<String, Object>> listServices(String type) {
        try {
            String path = type != null
                    ? "/api/service-registry?type=" + type
                    : "/api/service-registry";
            return get(path, new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Service registry unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Transfers ───────────────────────────────────────────────────────

    /** Get transfer details by track ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTransfer(String trackId) {
        try {
            return get("/api/v2/transfers/" + trackId, Map.class);
        } catch (Exception e) {
            throw serviceError("getTransfer", e);
        }
    }

    // ── Servers ─────────────────────────────────────────────────────────

    /** List server instances. */
    public List<Map<String, Object>> listServers() {
        try {
            return get("/api/servers",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Server list unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
