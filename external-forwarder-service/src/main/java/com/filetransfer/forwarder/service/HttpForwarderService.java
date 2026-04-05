package com.filetransfer.forwarder.service;

import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.enums.AuthType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;

/**
 * Forwards files to external HTTP/HTTPS/API endpoints.
 * Supports multiple authentication types: BASIC, BEARER_TOKEN, API_KEY, NONE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpForwarderService {

    private final CredentialCryptoClient credentialCrypto;

    public void forward(DeliveryEndpoint endpoint, String filename, byte[] fileBytes) throws Exception {
        String scheme = endpoint.isTlsEnabled() ? "https" : "http";
        int port = endpoint.getPort() != null ? endpoint.getPort() : (endpoint.isTlsEnabled() ? 443 : 80);
        String basePath = endpoint.getBasePath() != null ? endpoint.getBasePath() : "";
        if (!basePath.isEmpty() && !basePath.startsWith("/")) basePath = "/" + basePath;

        String url = scheme + "://" + endpoint.getHost() + ":" + port + basePath;

        // Determine HTTP method
        HttpMethod method = "PUT".equalsIgnoreCase(endpoint.getHttpMethod()) ? HttpMethod.PUT : HttpMethod.POST;

        // Build headers
        HttpHeaders headers = new HttpHeaders();

        // Content type
        String contentType = endpoint.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } else {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }

        // Filename header
        headers.set("X-Filename", filename);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename).build());

        // Custom headers from config
        if (endpoint.getHttpHeaders() != null) {
            for (Map.Entry<String, String> entry : endpoint.getHttpHeaders().entrySet()) {
                headers.set(entry.getKey(), entry.getValue());
            }
        }

        // Authentication
        applyAuth(headers, endpoint);

        HttpEntity<byte[]> entity = new HttpEntity<>(fileBytes, headers);

        RestTemplate rest = buildRestTemplate(endpoint);
        ResponseEntity<String> response = rest.exchange(url, method, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("HTTP delivery failed: " + response.getStatusCode()
                    + " " + response.getBody());
        }

        String proxyInfo = endpoint.isProxyEnabled() ? " via proxy " + endpoint.getProxyHost() + ":" + endpoint.getProxyPort() : "";
        log.info("HTTP forward complete: {} → {} {}{} ({} bytes, status={})",
                filename, method, url, proxyInfo, fileBytes.length, response.getStatusCode().value());
    }

    private void applyAuth(HttpHeaders headers, DeliveryEndpoint endpoint) {
        AuthType authType = endpoint.getAuthType();
        if (authType == null || authType == AuthType.NONE) return;

        switch (authType) {
            case BASIC -> {
                String credentials = endpoint.getUsername() + ":" + decryptSecret(endpoint.getEncryptedPassword());
                String encoded = java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
                headers.set("Authorization", "Basic " + encoded);
            }
            case BEARER_TOKEN -> {
                String token = decryptSecret(endpoint.getBearerToken());
                headers.setBearerAuth(token);
            }
            case API_KEY -> {
                String headerName = endpoint.getApiKeyHeader() != null ? endpoint.getApiKeyHeader() : "X-API-Key";
                headers.set(headerName, decryptSecret(endpoint.getApiKeyValue()));
            }
            case OAUTH2 -> {
                // OAuth2 token should be pre-fetched and stored as bearer token
                String token = decryptSecret(endpoint.getBearerToken());
                if (token != null) headers.setBearerAuth(token);
            }
            default -> log.warn("Unsupported auth type for HTTP: {}", authType);
        }
    }

    /**
     * Build a RestTemplate, optionally configured with HTTP or SOCKS5 proxy.
     * When proxyEnabled=false or proxyType=DMZ (handled at controller level), no proxy is set.
     */
    private RestTemplate buildRestTemplate(DeliveryEndpoint endpoint) {
        if (!endpoint.isProxyEnabled() || endpoint.getProxyHost() == null
                || "DMZ".equalsIgnoreCase(endpoint.getProxyType())) {
            return new RestTemplate();
        }

        Proxy.Type proxyType = "SOCKS5".equalsIgnoreCase(endpoint.getProxyType())
                ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        int proxyPort = endpoint.getProxyPort() != null ? endpoint.getProxyPort() : 8080;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(new Proxy(proxyType,
                new InetSocketAddress(endpoint.getProxyHost(), proxyPort)));
        factory.setConnectTimeout(endpoint.getConnectionTimeoutMs());
        factory.setReadTimeout(endpoint.getReadTimeoutMs());

        log.info("Using {} proxy {}:{} for HTTP delivery",
                proxyType, endpoint.getProxyHost(), proxyPort);
        return new RestTemplate(factory);
    }

    private String decryptSecret(String encrypted) {
        return credentialCrypto.decrypt(encrypted);
    }
}
