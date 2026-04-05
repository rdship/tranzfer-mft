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
 * Client for the Gateway Service (port 8085).
 * Provides gateway status and legacy server configuration.
 *
 * <p>Error strategy: <b>graceful degradation</b> — gateway status
 * queries should not block operations.
 */
@Slf4j
@Component
public class GatewayServiceClient extends BaseServiceClient {

    public GatewayServiceClient(RestTemplate restTemplate,
                                PlatformConfig platformConfig,
                                ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getGatewayService(), "gateway-service");
    }

    /** Get gateway status (port info, running state). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        try {
            return get("/internal/gateway/status", Map.class);
        } catch (Exception e) {
            log.warn("Gateway status unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** List legacy server configurations, optionally filtered by protocol. */
    public List<Map<String, Object>> legacyServers(String protocol) {
        try {
            String path = protocol != null
                    ? "/internal/gateway/legacy-servers?protocol=" + protocol
                    : "/internal/gateway/legacy-servers";
            return get(path, new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Legacy servers unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
