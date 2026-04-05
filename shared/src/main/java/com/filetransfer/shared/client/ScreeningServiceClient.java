package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the Screening Service (port 8092).
 * Provides malware scanning, content screening, and sanctions list checking.
 *
 * <p>Error strategy: <b>fail-fast</b> — screening must succeed before
 * a file is routed or delivered.
 */
@Slf4j
@Component
public class ScreeningServiceClient extends BaseServiceClient {

    public ScreeningServiceClient(RestTemplate restTemplate,
                                  PlatformConfig platformConfig,
                                  ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getScreeningService(), "screening-service");
    }

    /** Scan a file for malware and sensitive content. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> scanFile(Path filePath, String trackId, String account) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            if (trackId != null) params.put("trackId", trackId);
            if (account != null) params.put("account", account);
            return postMultipart("/api/v1/screening/scan", filePath, params);
        } catch (Exception e) {
            throw serviceError("scanFile", e);
        }
    }

    /** Scan text content for sensitive data (PCI, PII, PHI). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> scanText(String content, String filename, String trackId) {
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("content", content);
            if (filename != null) body.put("filename", filename);
            if (trackId != null) body.put("trackId", trackId);
            return post("/api/v1/screening/scan/text", body, Map.class);
        } catch (Exception e) {
            throw serviceError("scanText", e);
        }
    }

    /** Retrieve a previous screening result by track ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getResult(String trackId) {
        return get("/api/v1/screening/results/" + trackId, Map.class);
    }

    /** Get recent screening results. */
    public List<Map<String, Object>> recentResults() {
        try {
            return get("/api/v1/screening/results",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch recent screening results: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get screening hits (flagged items). */
    public List<Map<String, Object>> getHits() {
        try {
            return get("/api/v1/screening/hits",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to fetch screening hits: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Refresh sanctions/screening lists. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshLists() {
        return post("/api/v1/screening/lists/refresh", null, Map.class);
    }

    @Override
    protected String healthPath() {
        return "/api/v1/screening/health";
    }
}
