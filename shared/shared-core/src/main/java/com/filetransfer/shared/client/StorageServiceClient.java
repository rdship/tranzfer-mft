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
public class StorageServiceClient extends ResilientServiceClient {

    public StorageServiceClient(RestTemplate restTemplate,
                                PlatformConfig platformConfig,
                                ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getStorageManager(), "storage-manager");
    }

    /** Store a file. Returns storage metadata (status, tier, SHA-256, throughput). */
    public Map<String, Object> store(Path filePath, String trackId, String account) {
        Map<String, String> params = new java.util.HashMap<>();
        if (trackId != null) params.put("trackId", trackId);
        if (account != null) params.put("account", account);
        return withResilience("store",
                () -> postMultipart("/api/v1/storage/store", filePath, params));
    }

    /** Store file bytes. Returns storage metadata. */
    public Map<String, Object> store(String filename, byte[] fileBytes, String trackId, String account) {
        Map<String, String> params = new java.util.HashMap<>();
        if (trackId != null) params.put("trackId", trackId);
        if (account != null) params.put("account", account);
        return withResilience("store",
                () -> postMultipartBytes("/api/v1/storage/store", filename, fileBytes, params));
    }

    /** Retrieve a stored file by track ID. Returns raw bytes. */
    public byte[] retrieve(String trackId) {
        return withResilience("retrieve", () -> {
            HttpHeaders headers = jsonHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/storage/retrieve/" + trackId,
                    HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        });
    }

    /** Check if a CAS object exists by SHA-256 key. Used by WAIP recovery. */
    @SuppressWarnings("unchecked")
    public boolean existsBySha256(String sha256) {
        try {
            Map<String, Object> result = withResilience("existsBySha256",
                    () -> get("/api/v1/storage/ref-count/" + sha256, Map.class));
            return Boolean.TRUE.equals(result.get("exists"));
        } catch (Exception e) {
            log.warn("CAS existence check failed for {}: {}", sha256, e.getMessage());
            return false;
        }
    }

    /** List storage objects, optionally filtered by account and tier. */
    public List<Map<String, Object>> listObjects(String account, String tier) {
        try {
            StringBuilder path = new StringBuilder("/api/v1/storage/objects?");
            if (account != null) path.append("account=").append(account).append("&");
            if (tier != null) path.append("tier=").append(tier);
            String finalPath = path.toString();
            return withResilience("listObjects",
                    () -> get(finalPath, new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception e) {
            log.warn("Failed to list storage objects: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get storage metrics. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metrics() {
        try {
            return withResilience("metrics",
                    () -> get("/api/v1/storage/metrics", Map.class));
        } catch (Exception e) {
            log.warn("Storage metrics unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Trigger storage lifecycle tiering. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerTiering() {
        return withResilience("triggerTiering",
                () -> post("/api/v1/storage/lifecycle/tier", null, Map.class));
    }

    /** Trigger storage backup. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerBackup() {
        return withResilience("triggerBackup",
                () -> post("/api/v1/storage/lifecycle/backup", null, Map.class));
    }

    /** Soft-delete a CAS object by SHA-256 key (used by CasOrphanReaper GC). */
    public void deleteBySha256(String sha256) {
        withResilience("deleteBySha256",
                (Runnable) () -> delete("/api/v1/storage/objects/" + sha256));
    }

    @Override
    protected String healthPath() {
        return "/api/v1/storage/health";
    }
}
