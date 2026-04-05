package com.filetransfer.ftp.keystore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for the centralized Keystore Manager service.
 * <p>
 * The FTP service uses this to store and retrieve TLS certificates from the
 * Keystore Manager rather than generating self-signed certs locally. This enables:
 * <ul>
 *   <li>Centralized certificate lifecycle management (rotation, expiry tracking)</li>
 *   <li>Consistent TLS certificates across FTP cluster nodes</li>
 *   <li>Audit trail for all certificate operations</li>
 *   <li>Compliance with PCI-DSS certificate management requirements</li>
 * </ul>
 * <p>
 * If the Keystore Manager is unavailable, the FTP service falls back to
 * local self-signed certificate generation for backward compatibility.
 */
@Slf4j
@Component
public class KeystoreManagerClient {

    private final RestTemplate restTemplate;
    private final String keystoreManagerUrl;
    private final boolean enabled;
    private final String instanceId;

    public KeystoreManagerClient(
            @Value("${ftp.keystore.manager-url:}") String keystoreManagerUrl,
            @Value("${ftp.keystore.enabled:false}") boolean enabled,
            @Value("${ftp.instance-id:ftp-default}") String instanceId) {
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
     * Retrieve a TLS certificate from the Keystore Manager by alias.
     *
     * @param alias the key alias (e.g., "ftp-tls-cert-ftp-1")
     * @return the key material (PEM-encoded) or null if not found/unavailable
     */
    @SuppressWarnings("unchecked")
    public String getTlsCertificate(String alias) {
        if (!enabled) return null;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/" + alias;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("keyMaterial")) {
                log.info("Retrieved TLS certificate '{}' from Keystore Manager", alias);
                return (String) response.get("keyMaterial");
            }
        } catch (Exception e) {
            log.warn("Could not retrieve TLS cert '{}' from Keystore Manager: {}", alias, e.getMessage());
        }
        return null;
    }

    /**
     * Generate and store a new TLS certificate in the Keystore Manager.
     *
     * @param alias the key alias
     * @param cn the Common Name for the certificate
     * @param validDays validity period in days
     * @return the generated key material or null if generation failed
     */
    @SuppressWarnings("unchecked")
    public String generateAndStoreTlsCert(String alias, String cn, int validDays) {
        if (!enabled) return null;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/generate/tls";
            Map<String, String> request = Map.of(
                    "alias", alias,
                    "cn", cn,
                    "validDays", String.valueOf(validDays)
            );
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("keyMaterial")) {
                log.info("Generated and stored TLS certificate '{}' in Keystore Manager", alias);
                return (String) response.get("keyMaterial");
            }
        } catch (Exception e) {
            log.warn("Could not generate TLS cert in Keystore Manager: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Import an existing local TLS keystore into the Keystore Manager.
     *
     * @param alias the key alias
     * @param keystoreBase64 the Base64-encoded keystore content
     */
    public void importTlsKeystore(String alias, String keystoreBase64) {
        if (!enabled) return;

        try {
            String url = keystoreManagerUrl + "/api/v1/keys/import";
            Map<String, String> request = Map.of(
                    "alias", alias,
                    "keyType", "TLS_KEYSTORE",
                    "keyMaterial", keystoreBase64,
                    "description", "FTP TLS keystore for " + instanceId,
                    "ownerService", "ftp-service-" + instanceId
            );
            restTemplate.postForObject(url, request, Map.class);
            log.info("Imported local TLS keystore '{}' to Keystore Manager", alias);
        } catch (Exception e) {
            log.warn("Could not import TLS keystore to Keystore Manager: {}", e.getMessage());
        }
    }

    /**
     * Get the default alias for this FTP instance's TLS certificate.
     */
    public String getDefaultAlias() {
        return "ftp-tls-cert-" + instanceId;
    }
}
