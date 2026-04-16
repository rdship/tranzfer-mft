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

    /** Register an existing CAS object with a trackId (no re-upload). Used by VIRTUAL-mode routing. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> register(String trackId, String sha256, String filename, String account, long sizeBytes) {
        try {
            String path = "/api/v1/storage/register?trackId=" + trackId
                    + "&sha256=" + sha256
                    + "&filename=" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                    + "&sizeBytes=" + sizeBytes
                    + (account != null ? "&account=" + java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8) : "");
            return withResilience("register", () -> post(path, null, Map.class));
        } catch (Exception e) {
            log.debug("Storage register failed for trackId={}: {}", trackId, e.getMessage());
            return Map.of("status", "FAILED", "error", e.getMessage());
        }
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

    /**
     * Retrieve file bytes by SHA-256 CAS key.
     * Used by the VIRTUAL-mode flow pipeline to fetch content without disk I/O.
     */
    public byte[] retrieveBySha256(String sha256) {
        return withResilience("retrieveBySha256", () -> {
            HttpHeaders headers = jsonHeaders();
            headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/storage/retrieve-by-key/" + sha256,
                    HttpMethod.GET, entity, byte[].class);
            return response.getBody();
        });
    }

    /**
     * Stream-upload an {@link java.io.InputStream} to storage-manager via {@code /store-stream}.
     *
     * <p>The stream is piped directly as the HTTP request body — no intermediate byte buffer
     * is allocated on the caller side. One-shot: does not use the resilience retry wrapper
     * because an InputStream can only be consumed once.
     *
     * @param data          byte stream to upload
     * @param estimatedSize byte count hint for {@code Content-Length}; pass {@code -1} if unknown
     * @param filename      target filename in storage-manager
     * @param account       account namespace (may be null)
     * @param trackId       platform track ID (may be null)
     * @return storage metadata map: sha256, sizeBytes, status, tier, etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> storeStream(java.io.InputStream data, long estimatedSize,
                                            String filename, String account, String trackId) {
        try {
            StringBuilder url = new StringBuilder(baseUrl())
                    .append("/api/v1/storage/store-stream?filename=")
                    .append(java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8));
            if (account != null)
                url.append("&account=").append(java.net.URLEncoder.encode(account, java.nio.charset.StandardCharsets.UTF_8));
            if (trackId != null)
                url.append("&trackId=").append(java.net.URLEncoder.encode(trackId, java.nio.charset.StandardCharsets.UTF_8));

            return restTemplate.execute(url.toString(), HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
                        if (estimatedSize > 0) request.getHeaders().setContentLength(estimatedSize);
                        addInternalAuth(request.getHeaders());
                        data.transferTo(request.getBody());
                    },
                    response -> {
                        byte[] body = response.getBody().readAllBytes();
                        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                    });
        } catch (Exception e) {
            throw new RuntimeException("storeStream failed for '" + filename + "': " + e.getMessage(), e);
        }
    }

    /**
     * Stream a CAS object directly to an {@link java.io.OutputStream} — truly zero-copy
     * from storage-manager disk to the caller's output stream.
     *
     * <p>Used by the step-preview endpoint to pipe file content to the HTTP response
     * without allocating an intermediate byte buffer. Bytes flow:
     * {@code storage-manager disk → HTTP response → OutputStream}.
     *
     * @param sha256 CAS key
     * @param out    destination — typically an HTTP response OutputStream
     */
    public void streamToOutput(String sha256, java.io.OutputStream out) {
        try {
            restTemplate.execute(
                    baseUrl() + "/api/v1/storage/stream/" + sha256,
                    org.springframework.http.HttpMethod.GET,
                    request -> {
                        addInternalAuth(request.getHeaders());
                        request.getHeaders().setAccept(
                                List.of(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
                    },
                    response -> {
                        response.getBody().transferTo(out);
                        return null;
                    });
        } catch (Exception e) {
            throw new RuntimeException("streamToOutput failed for key=" + sha256 + ": " + e.getMessage(), e);
        }
    }

    /**
     * Read only the first {@code maxBytes} of a CAS object — for header/magic-byte detection.
     * Uses HTTP Range header so storage-manager streams only the requested prefix.
     * Falls back to full retrieve + truncate if Range is not supported.
     */
    public byte[] retrieveHeader(String sha256, int maxBytes) {
        return withResilience("retrieveHeader", () -> {
            HttpHeaders headers = jsonHeaders();
            headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
            headers.set("Range", "bytes=0-" + (maxBytes - 1));
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/storage/stream/" + sha256,
                    HttpMethod.GET, entity, byte[].class);
            byte[] body = response.getBody();
            if (body == null) return null;
            return body.length <= maxBytes ? body : java.util.Arrays.copyOf(body, maxBytes);
        });
    }

    @Override
    protected String healthPath() {
        return "/api/v1/storage/health";
    }
}
