package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the Storage Manager service (port 8096).
 * Provides file storage, retrieval, lifecycle management, and deduplication.
 *
 * <p>Error strategy: <b>fail-fast</b> for store/retrieve operations;
 * <b>graceful degradation</b> for metrics and lifecycle queries.
 */
@Slf4j
@Component
public class StorageServiceClient extends BaseServiceClient {

    public StorageServiceClient(RestTemplate restTemplate,
                                PlatformConfig platformConfig,
                                ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getStorageManager(), "storage-manager");
    }

    /** Store a file. Returns storage metadata (status, tier, SHA-256, throughput). */
    public Map<String, Object> store(Path filePath, String trackId, String account) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            if (trackId != null) params.put("trackId", trackId);
            if (account != null) params.put("account", account);
            return postMultipart("/api/v1/storage/store", filePath, params);
        } catch (Exception e) {
            throw serviceError("store", e);
        }
    }

    /** Store file bytes. Returns storage metadata. */
    public Map<String, Object> store(String filename, byte[] fileBytes, String trackId, String account) {
        try {
            Map<String, String> params = new java.util.HashMap<>();
            if (trackId != null) params.put("trackId", trackId);
            if (account != null) params.put("account", account);
            return postMultipartBytes("/api/v1/storage/store", filename, fileBytes, params);
        } catch (Exception e) {
            throw serviceError("store", e);
        }
    }

    /** Retrieve a stored file by track ID. Returns raw bytes. */
    public byte[] retrieve(String trackId) {
        try {
            HttpHeaders headers = jsonHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/storage/retrieve/" + trackId,
                    HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            throw serviceError("retrieve", e);
        }
    }

    /** List storage objects, optionally filtered by account and tier. */
    public List<Map<String, Object>> listObjects(String account, String tier) {
        try {
            StringBuilder path = new StringBuilder("/api/v1/storage/objects?");
            if (account != null) path.append("account=").append(account).append("&");
            if (tier != null) path.append("tier=").append(tier);
            return get(path.toString(), new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to list storage objects: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get storage metrics. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metrics() {
        try {
            return get("/api/v1/storage/metrics", Map.class);
        } catch (Exception e) {
            log.warn("Storage metrics unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Trigger storage lifecycle tiering. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerTiering() {
        return post("/api/v1/storage/lifecycle/tier", null, Map.class);
    }

    /** Trigger storage backup. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerBackup() {
        return post("/api/v1/storage/lifecycle/backup", null, Map.class);
    }

    @Override
    protected String healthPath() {
        return "/api/v1/storage/health";
    }
}
