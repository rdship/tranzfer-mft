package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the Keystore Manager service (port 8093).
 * Centralized management of SSH host keys, TLS certificates, AES keys, and HMAC keys.
 *
 * <p>Error strategy: <b>swallow-and-log</b> — keystore unavailability should
 * not prevent services from starting; they fall back to local key generation.
 *
 * <p>Replaces the duplicated KeystoreManagerClient classes that were in
 * sftp-service and ftp-service.
 */
@Slf4j
@Component
public class KeystoreServiceClient extends ResilientServiceClient {

    public KeystoreServiceClient(RestTemplate restTemplate,
                                 PlatformConfig platformConfig,
                                 ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getKeystoreManager(), "keystore-manager");
    }

    // ── Key retrieval ───────────────────────────────────────────────────

    /** List all managed keys, optionally filtered by type, service, or partner. */
    public List<Map<String, Object>> listKeys(String type, String service, String partner) {
        try {
            StringBuilder path = new StringBuilder("/api/v1/keys?");
            if (type != null) path.append("type=").append(type).append("&");
            if (service != null) path.append("service=").append(service).append("&");
            if (partner != null) path.append("partner=").append(partner);
            String finalPath = path.toString();
            return withResilience("listKeys",
                    () -> get(finalPath, new ParameterizedTypeReference<List<Map<String, Object>>>() {}));
        } catch (Exception e) {
            log.warn("Could not list keys from Keystore Manager: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Retrieve a key by alias. Returns the full key object or null if not found. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getKey(String alias) {
        try {
            return withResilience("getKey",
                    () -> get("/api/v1/keys/" + alias, Map.class));
        } catch (Exception e) {
            log.warn("Could not retrieve key '{}' from Keystore Manager: {}", alias, e.getMessage());
            return null;
        }
    }

    /** Retrieve just the key material (PEM-encoded) for a given alias. */
    @SuppressWarnings("unchecked")
    public String getKeyMaterial(String alias) {
        Map<String, Object> key = getKey(alias);
        return key != null ? (String) key.get("keyMaterial") : null;
    }

    /** Retrieve the public key (PEM format) for a given alias. */
    public String getPublicKey(String alias) {
        try {
            return withResilience("getPublicKey",
                    () -> get("/api/v1/keys/" + alias + "/public", String.class));
        } catch (Exception e) {
            log.warn("Could not retrieve public key '{}': {}", alias, e.getMessage());
            return null;
        }
    }

    // ── Key generation ──────────────────────────────────────────────────

    /** Generate and store a new SSH host key. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateSshHostKey(String alias, String ownerService) {
        try {
            return withResilience("generateSshHostKey",
                    () -> post("/api/v1/keys/generate/ssh-host",
                            Map.of("alias", alias, "ownerService", ownerService), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate SSH host key in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    /** Generate and store a new SSH user key. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateSshUserKey(String alias, String partnerAccount, int keySize) {
        try {
            return withResilience("generateSshUserKey",
                    () -> post("/api/v1/keys/generate/ssh-user",
                            Map.of("alias", alias, "partnerAccount", partnerAccount,
                                   "keySize", String.valueOf(keySize)), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate SSH user key in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    /** Generate and store a new AES encryption key. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateAesKey(String alias, String ownerService) {
        try {
            return withResilience("generateAesKey",
                    () -> post("/api/v1/keys/generate/aes",
                            Map.of("alias", alias, "ownerService", ownerService), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate AES key in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    /** Generate and store a new TLS certificate. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateTlsCert(String alias, String cn, int validDays) {
        try {
            return withResilience("generateTlsCert",
                    () -> post("/api/v1/keys/generate/tls",
                            Map.of("alias", alias, "cn", cn, "validDays", String.valueOf(validDays)), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate TLS cert in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    /** Generate and store a new HMAC key. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateHmacKey(String alias, String ownerService) {
        try {
            return withResilience("generateHmacKey",
                    () -> post("/api/v1/keys/generate/hmac",
                            Map.of("alias", alias, "ownerService", ownerService), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate HMAC key in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    /** Generate and store a PGP keypair. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generatePgpKeypair(String alias, String identity, String passphrase) {
        try {
            return withResilience("generatePgpKeypair",
                    () -> post("/api/v1/keys/generate/pgp",
                            Map.of("alias", alias, "identity", identity,
                                   "passphrase", passphrase != null ? passphrase : ""), Map.class));
        } catch (Exception e) {
            log.warn("Could not generate PGP keypair in Keystore Manager: {}", e.getMessage());
            return null;
        }
    }

    // ── Key import and rotation ─────────────────────────────────────────

    /** Import an existing key into the Keystore Manager. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importKey(String alias, String keyType, String keyMaterial,
                                          String description, String ownerService) {
        try {
            return withResilience("importKey",
                    () -> post("/api/v1/keys/import",
                            Map.of("alias", alias, "keyType", keyType, "keyMaterial", keyMaterial,
                                   "description", description, "ownerService", ownerService), Map.class));
        } catch (Exception e) {
            log.warn("Could not import key '{}' to Keystore Manager: {}", alias, e.getMessage());
            return null;
        }
    }

    /** Rotate a key, optionally providing a new alias. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rotateKey(String alias, String newAlias) {
        try {
            Map<String, String> body = newAlias != null ? Map.of("newAlias", newAlias) : Map.of();
            return withResilience("rotateKey",
                    () -> post("/api/v1/keys/" + alias + "/rotate", body, Map.class));
        } catch (Exception e) {
            log.warn("Could not rotate key '{}': {}", alias, e.getMessage());
            return null;
        }
    }

    /** Deactivate a key by alias. */
    public boolean deactivateKey(String alias) {
        try {
            withResilience("deactivateKey",
                    () -> { delete("/api/v1/keys/" + alias); });
            return true;
        } catch (Exception e) {
            log.warn("Could not deactivate key '{}': {}", alias, e.getMessage());
            return false;
        }
    }

    // ── Convenience methods for SFTP/FTP ────────────────────────────────

    /** Retrieve an SSH host key by alias (returns PEM key material or null). */
    public String getHostKey(String alias) {
        return getKeyMaterial(alias);
    }

    /** Generate and store an SSH host key, returning the key material. */
    @SuppressWarnings("unchecked")
    public String generateAndStoreHostKey(String alias, String ownerService) {
        Map<String, Object> result = generateSshHostKey(alias, ownerService);
        return result != null ? (String) result.get("keyMaterial") : null;
    }

    /** Import an SSH host key. */
    public void importHostKey(String alias, String keyMaterial, String ownerService) {
        importKey(alias, "SSH_HOST_KEY", keyMaterial,
                "SSH host key for " + ownerService, ownerService);
    }

    /** Retrieve a TLS certificate by alias (returns key material or null). */
    public String getTlsCertificate(String alias) {
        return getKeyMaterial(alias);
    }

    /** Generate and store a TLS cert, returning the key material. */
    @SuppressWarnings("unchecked")
    public String generateAndStoreTlsCert(String alias, String cn, int validDays) {
        Map<String, Object> result = generateTlsCert(alias, cn, validDays);
        return result != null ? (String) result.get("keyMaterial") : null;
    }

    /** Import a TLS keystore. */
    public void importTlsKeystore(String alias, String keystoreBase64, String ownerService) {
        importKey(alias, "TLS_KEYSTORE", keystoreBase64,
                "TLS keystore for " + ownerService, ownerService);
    }

    @Override
    protected String healthPath() {
        return "/api/v1/keys/health";
    }
}
