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
 * Client for the DMZ Proxy service (port 8088).
 * Manages proxy mappings, connection pools, and request forwarding.
 *
 * <p>Error strategy: <b>fail-fast</b> for proxy configuration operations;
 * <b>graceful degradation</b> for status queries.
 */
@Slf4j
@Component
public class DmzProxyClient extends BaseServiceClient {

    public DmzProxyClient(RestTemplate restTemplate,
                          PlatformConfig platformConfig,
                          ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getDmzProxy(), "dmz-proxy");
    }

    /** Get proxy status and active mappings. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        try {
            return get("/api/proxy/status", Map.class);
        } catch (Exception e) {
            log.warn("DMZ proxy status unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** List active proxy mappings. */
    public List<Map<String, Object>> listMappings() {
        try {
            return get("/api/proxy/mappings",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("DMZ proxy mappings unavailable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Create or update a proxy mapping. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createMapping(Map<String, Object> mapping) {
        try {
            return post("/api/proxy/mappings", mapping, Map.class);
        } catch (Exception e) {
            throw serviceError("createMapping", e);
        }
    }

    /** Delete a proxy mapping. */
    public void deleteMapping(String mappingId) {
        try {
            delete("/api/proxy/mappings/" + mappingId);
        } catch (Exception e) {
            log.warn("Failed to delete proxy mapping {}: {}", mappingId, e.getMessage());
        }
    }
}
