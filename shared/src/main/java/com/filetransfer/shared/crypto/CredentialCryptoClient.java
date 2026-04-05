package com.filetransfer.shared.crypto;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for the Encryption Service's credential encrypt/decrypt API.
 * Uses AES-256-GCM with the platform master key — no keyId needed.
 *
 * Encrypt path: DeliveryEndpointController calls encrypt() before persisting secrets.
 * Decrypt path: Forwarder services call decrypt() before using credentials.
 */
@Slf4j
@Component
public class CredentialCryptoClient {

    @Value("${platform.encryption-service.url:http://encryption-service:8086}")
    private String encryptionServiceUrl;

    private final RestTemplate restTemplate;
    private final PlatformConfig platformConfig;

    public CredentialCryptoClient(RestTemplate restTemplate, PlatformConfig platformConfig) {
        this.restTemplate = restTemplate;
        this.platformConfig = platformConfig;
    }

    /**
     * Encrypt a plaintext credential value.
     * Returns a Base64-encoded AES-256-GCM ciphertext.
     */
    @SuppressWarnings("unchecked")
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("value", plaintext), headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    encryptionServiceUrl + "/api/encrypt/credential/encrypt",
                    request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("encrypted");
            }
            throw new RuntimeException("Unexpected response: " + response.getStatusCode());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Credential encryption failed: {}", e.getMessage());
            throw new RuntimeException("Credential encryption failed — is encryption-service reachable?", e);
        }
    }

    /**
     * Decrypt a Base64-encoded AES-256-GCM ciphertext back to plaintext.
     */
    @SuppressWarnings("unchecked")
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());

            HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("encrypted", encrypted), headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    encryptionServiceUrl + "/api/encrypt/credential/decrypt",
                    request, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("value");
            }
            throw new RuntimeException("Unexpected response: " + response.getStatusCode());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Credential decryption failed: {}", e.getMessage());
            throw new RuntimeException("Credential decryption failed — is encryption-service reachable?", e);
        }
    }
}
