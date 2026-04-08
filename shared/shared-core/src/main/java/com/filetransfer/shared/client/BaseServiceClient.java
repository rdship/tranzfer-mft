package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * Base class for all inter-service REST clients in the platform.
 * Provides common patterns:
 * <ul>
 *   <li>Authentication via SPIFFE JWT-SVID (workload identity)</li>
 *   <li>Consistent error handling and logging</li>
 *   <li>Health check support</li>
 *   <li>Enabled/disabled toggle</li>
 * </ul>
 *
 * <p>Subclasses implement typed methods for each service's API and choose
 * their error strategy (fail-fast, graceful degradation, or swallow-and-log).
 */
@Slf4j
public abstract class BaseServiceClient {

    protected final RestTemplate restTemplate;
    protected final PlatformConfig platformConfig;
    private final ServiceClientProperties.ServiceEndpoint endpoint;
    private final String serviceName;

    /**
     * Optional SPIFFE workload client — auto-wired when {@code spiffe.enabled=true}.
     * When present, outbound calls use a short-lived JWT-SVID instead of
     * When absent, outbound calls proceed without a workload identity token.
     */
    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeWorkloadClient;

    protected BaseServiceClient(RestTemplate restTemplate,
                                PlatformConfig platformConfig,
                                ServiceClientProperties.ServiceEndpoint endpoint,
                                String serviceName) {
        this.restTemplate = restTemplate;
        this.platformConfig = platformConfig;
        this.endpoint = endpoint;
        this.serviceName = serviceName;
    }

    /** Whether this client is enabled and configured. */
    public boolean isEnabled() {
        return endpoint.isEnabled() && endpoint.getUrl() != null && !endpoint.getUrl().isBlank();
    }

    /** Base URL for the target service (no trailing slash). */
    protected String baseUrl() {
        return endpoint.getUrl();
    }

    /** Human-readable name for log messages. */
    protected String serviceName() {
        return serviceName;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    /** Build headers with internal auth and JSON content type. */
    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(headers);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }
        return headers;
    }

    /** Build headers with internal auth and multipart content type. */
    protected HttpHeaders multipartHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        addInternalAuth(headers);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }
        return headers;
    }

    /**
     * Adds SPIFFE JWT-SVID authentication to outbound inter-service headers.
     *
     * <p>Uses the SPIRE Workload API to fetch a short-lived (1h), auto-rotating
     * JWT-SVID for the target service. If SPIFFE is unavailable, no auth header
     * is added — the target service will return 401 (fail-safe, no static secret fallback).
     */
    protected void addInternalAuth(HttpHeaders headers) {
        if (spiffeWorkloadClient != null && spiffeWorkloadClient.isAvailable()) {
            String target = deriveTargetServiceName();
            String jwtSvid = spiffeWorkloadClient.getJwtSvidFor(target);
            if (jwtSvid != null) {
                headers.setBearerAuth(jwtSvid);
            }
        }
    }

    /**
     * Derives the target service's SPIFFE path segment from the configured URL.
     * e.g. {@code http://gateway-service:8085/...} → {@code gateway-service}
     */
    private String deriveTargetServiceName() {
        try {
            return new URI(baseUrl()).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** GET with auth headers, returning the response body mapped to the given type. */
    @SuppressWarnings("unchecked")
    protected <T> T get(String path, Class<T> responseType) {
        HttpHeaders headers = jsonHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<T> response = restTemplate.exchange(
                baseUrl() + path, HttpMethod.GET, entity, responseType);
        return response.getBody();
    }

    /** GET returning a parameterized type (e.g. List&lt;Foo&gt;). */
    protected <T> T get(String path, ParameterizedTypeReference<T> responseType) {
        HttpHeaders headers = jsonHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<T> response = restTemplate.exchange(
                baseUrl() + path, HttpMethod.GET, entity, responseType);
        return response.getBody();
    }

    /** POST JSON body with auth headers, returning the response body. */
    protected <T> T post(String path, Object body, Class<T> responseType) {
        HttpEntity<Object> entity = new HttpEntity<>(body, jsonHeaders());
        ResponseEntity<T> response = restTemplate.postForEntity(
                baseUrl() + path, entity, responseType);
        return response.getBody();
    }

    /** POST returning a parameterized type. */
    protected <T> T post(String path, Object body, ParameterizedTypeReference<T> responseType) {
        HttpEntity<Object> entity = new HttpEntity<>(body, jsonHeaders());
        ResponseEntity<T> response = restTemplate.exchange(
                baseUrl() + path, HttpMethod.POST, entity, responseType);
        return response.getBody();
    }

    /** POST a multipart file upload with auth headers. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postMultipart(String path, Path filePath, Map<String, String> params) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(filePath.toFile()));
        if (params != null) {
            params.forEach(body::add);
        }
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, multipartHeaders());
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + path, entity, Map.class);
        return response.getBody();
    }

    /** POST multipart with byte array content. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> postMultipartBytes(String path, String filename, byte[] fileBytes,
                                                      Map<String, String> params) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(fileBytes) {
            @Override public String getFilename() { return filename; }
        });
        if (params != null) {
            params.forEach(body::add);
        }
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, multipartHeaders());
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + path, entity, Map.class);
        return response.getBody();
    }

    /** PUT JSON body with auth headers. */
    protected <T> T put(String path, Object body, Class<T> responseType) {
        HttpEntity<Object> entity = new HttpEntity<>(body, jsonHeaders());
        ResponseEntity<T> response = restTemplate.exchange(
                baseUrl() + path, HttpMethod.PUT, entity, responseType);
        return response.getBody();
    }

    /** DELETE with auth headers. */
    protected void delete(String path) {
        HttpEntity<Void> entity = new HttpEntity<>(jsonHeaders());
        restTemplate.exchange(baseUrl() + path, HttpMethod.DELETE, entity, Void.class);
    }

    // ── Health check ────────────────────────────────────────────────────

    /**
     * Check if the target service is reachable.
     * Calls the service's health endpoint (if it has one) or just the base URL.
     */
    public boolean isHealthy() {
        if (!isEnabled()) return false;
        try {
            HttpHeaders headers = new HttpHeaders();
            addInternalAuth(headers);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl() + healthPath(), HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("{} health check failed: {}", serviceName, e.getMessage());
            return false;
        }
    }

    /** Override to customize the health endpoint path. Default: /actuator/health */
    protected String healthPath() {
        return "/actuator/health";
    }

    // ── Error helpers ───────────────────────────────────────────────────

    /** Wrap an exception with service context for better diagnostics. */
    protected RuntimeException serviceError(String operation, Exception e) {
        String msg = String.format("%s: %s failed — is %s reachable at %s?",
                serviceName, operation, serviceName, baseUrl());
        if (e instanceof HttpStatusCodeException hsce) {
            msg += " (HTTP " + hsce.getStatusCode() + ")";
        }
        return new RuntimeException(msg, e);
    }

    /** Check if an exception indicates the service is unreachable (vs a logic error). */
    protected boolean isConnectionError(Exception e) {
        return e instanceof ResourceAccessException;
    }
}
