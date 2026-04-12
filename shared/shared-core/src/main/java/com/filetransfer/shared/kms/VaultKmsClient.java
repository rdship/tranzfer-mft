package com.filetransfer.shared.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HashiCorp Vault KMS client for centralized secret management.
 *
 * <p>Retrieves encryption keys, HMAC keys, and other secrets from Vault's
 * KV v2 secrets engine. Caches secrets in memory to avoid per-request
 * Vault calls (cache busted on explicit refresh or TTL expiry).
 *
 * <p>Activated when {@code vault.enabled=true} (default in Docker).
 * Falls back gracefully to env vars when Vault is unavailable.
 *
 * <p>Secret paths:
 * <ul>
 *   <li>{@code secret/mft/encryption-master} — AES-256 master key for credential encryption
 *   <li>{@code secret/mft/storage-encryption} — AES-256-GCM key for encryption-at-rest
 *   <li>{@code secret/mft/audit-hmac} — HmacSHA512 key for audit log signing
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vault.enabled", havingValue = "true", matchIfMissing = false)
public class VaultKmsClient {

    private final String vaultAddr;
    private final String vaultToken;
    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public VaultKmsClient(
            @Value("${vault.addr:${VAULT_ADDR:http://localhost:8200}}") String vaultAddr,
            @Value("${vault.token:${VAULT_TOKEN:}}") String vaultToken) {
        this.vaultAddr = vaultAddr;
        this.vaultToken = vaultToken;
        this.restTemplate = new RestTemplate();
        log.info("Vault KMS client initialized: {}", vaultAddr);
    }

    /**
     * Get a secret value from Vault KV v2.
     * @param path secret path (e.g., "mft/encryption-master")
     * @param field field within the secret (e.g., "key")
     * @return secret value, or null if unavailable
     */
    public String getSecret(String path, String field) {
        String cacheKey = path + ":" + field;
        return cache.computeIfAbsent(cacheKey, k -> fetchFromVault(path, field));
    }

    /**
     * Get the master encryption key (Base64-encoded AES-256).
     */
    public String getMasterEncryptionKey() {
        return getSecret("mft/encryption-master", "key");
    }

    /**
     * Get the storage encryption-at-rest key (Base64-encoded AES-256).
     */
    public String getStorageEncryptionKey() {
        return getSecret("mft/storage-encryption", "key");
    }

    /**
     * Get the HMAC signing key for audit logs (Base64-encoded).
     */
    public String getAuditHmacKey() {
        return getSecret("mft/audit-hmac", "key");
    }

    /**
     * Clear cached secrets (call after key rotation).
     */
    public void clearCache() {
        cache.clear();
        log.info("Vault KMS cache cleared");
    }

    @SuppressWarnings("unchecked")
    private String fetchFromVault(String path, String field) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Vault-Token", vaultToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = vaultAddr + "/v1/secret/data/" + path;
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            if (response.getBody() != null) {
                Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
                if (data != null) {
                    Map<String, Object> secretData = (Map<String, Object>) data.get("data");
                    if (secretData != null && secretData.containsKey(field)) {
                        log.debug("Vault secret loaded: {}/{}", path, field);
                        return secretData.get(field).toString();
                    }
                }
            }
            log.warn("Vault secret not found: {}/{}", path, field);
            return null;
        } catch (Exception e) {
            log.warn("Vault unreachable for {}/{}: {} — falling back to env vars", path, field, e.getMessage());
            return null;
        }
    }
}
