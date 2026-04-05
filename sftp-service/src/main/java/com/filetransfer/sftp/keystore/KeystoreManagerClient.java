package com.filetransfer.sftp.keystore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for the centralized Keystore Manager service.
 * <p>
 * The SFTP service uses this to store and retrieve SSH host keys from the
 * Keystore Manager rather than generating them locally. This enables:
 * <ul>
 *   <li>Centralized key lifecycle management (rotation, expiry tracking)</li>
 *   <li>Consistent host keys across SFTP cluster nodes</li>
 *   <li>Audit trail for all key operations</li>
 *   <li>Compliance with PCI-DSS key management requirements</li>
 * </ul>
 * <p>
 * If the Keystore Manager is unavailable, the SFTP service falls back to
 * local key generation for backward compatibility.
 */
@Slf4j
@Component
public class KeystoreManagerClient {

    private final RestTemplate restTemplate;
    private final String keystoreManagerUrl;
    private final boolean enabled;
    private final String instanceId;

    public KeystoreManagerClient(
            @Value("${sftp.keystore.manager-url:}") String keystoreManagerUrl,
            @Value("${sftp.keystore.enabled:false}") boolean enabled,
            @Value("${sftp.instance-id:sftp-default}") String instanceId) {
        this.restTemplate = new RestTemplate();
        this.keystoreManagerUrl = keystoreManagerUrl;
        this.enabled = enabled && keystoreManagerUrl != null && !keystoreManagerUrl.isBlank();
        this.instanceId = instanceId;
    }

    /**
     * Whether Keystore Manager integration is enabled and configured.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Retrieve an SSH host key from the Keystore Manager by alias.
     *
     * @param alias the key alias (e.g., "sftp-host-key-sftp-1")
     * @return the key material (PEM-encoded private key) or null if not found/unavailable
     */
    @SuppressWarnings("unchecked")
    public String getHostKey(String alias) {
        if (!enabled) return null;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/" + alias;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("keyMaterial")) {
                log.info("Retrieved SSH host key '{}' from Keystore Manager", alias);
                return (String) response.get("keyMaterial");
            }
        } catch (Exception e) {
            log.warn("Could not retrieve host key '{}' from Keystore Manager: {}", alias, e.getMessage());
        }
        return null;
    }

    /**
     * Generate and store a new SSH host key in the Keystore Manager.
     *
     * @param alias the key alias
     * @return the generated key material or null if generation failed
     */
    @SuppressWarnings("unchecked")
    public String generateAndStoreHostKey(String alias) {
        if (!enabled) return null;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/generate/ssh-host";
            Map<String, String> request = Map.of(
                    "alias", alias,
                    "ownerService", "sftp-service-" + instanceId
            );
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("keyMaterial")) {
                log.info("Generated and stored SSH host key '{}' in Keystore Manager", alias);
                return (String) response.get("keyMaterial");
            }
        } catch (Exception e) {
            log.warn("Could not generate host key in Keystore Manager: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Import an existing local host key into the Keystore Manager.
     *
     * @param alias the key alias
     * @param keyMaterial the PEM-encoded private key
     */
    public void importHostKey(String alias, String keyMaterial) {
        if (!enabled) return;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/import";
            Map<String, String> request = Map.of(
                    "alias", alias,
                    "keyType", "SSH_HOST_KEY",
                    "keyMaterial", keyMaterial,
                    "description", "SFTP host key for " + instanceId,
                    "ownerService", "sftp-service-" + instanceId
            );
            restTemplate.postForObject(url, request, Map.class);
            log.info("Imported local SSH host key '{}' to Keystore Manager", alias);
        } catch (Exception e) {
            log.warn("Could not import host key to Keystore Manager: {}", e.getMessage());
        }
    }

    /**
     * Get the default alias for this SFTP instance's host key.
     */
    public String getDefaultAlias() {
        return "sftp-host-key-" + instanceId;
    }
}
