package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the Encryption Service (port 8086).
 * Provides file encryption/decryption and credential encryption via AES-256-GCM.
 *
 * <p>Error strategy: <b>fail-fast</b> — encryption/decryption must succeed;
 * failures propagate to the caller.
 */
@Slf4j
@Component
public class EncryptionServiceClient extends ResilientServiceClient {

    public EncryptionServiceClient(RestTemplate restTemplate,
                                   PlatformConfig platformConfig,
                                   ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getEncryptionService(), "encryption-service");
    }

    // ── File encryption ─────────────────────────────────────────────────

    /** Encrypt a file using the specified encryption key. Returns encrypted bytes. */
    public byte[] encryptFile(UUID keyId, Path filePath) {
        return withResilience("encryptFile",
                () -> postMultipartForBytes("/api/encrypt/encrypt?keyId=" + keyId, filePath));
    }

    /** Decrypt a file using the specified encryption key. Returns decrypted bytes. */
    public byte[] decryptFile(UUID keyId, Path filePath) {
        return withResilience("decryptFile",
                () -> postMultipartForBytes("/api/encrypt/decrypt?keyId=" + keyId, filePath));
    }

    /** Encrypt Base64-encoded content. */
    public String encryptBase64(UUID keyId, String base64Input) {
        return withResilience("encryptBase64",
                () -> post("/api/encrypt/encrypt/base64?keyId=" + keyId, base64Input, String.class));
    }

    /** Decrypt Base64-encoded content. */
    public String decryptBase64(UUID keyId, String base64Input) {
        return withResilience("decryptBase64",
                () -> post("/api/encrypt/decrypt/base64?keyId=" + keyId, base64Input, String.class));
    }

    // ── Credential encryption (master key, no keyId needed) ─────────────

    /** Encrypt a plaintext credential. Returns Base64-encoded AES-256-GCM ciphertext. */
    @SuppressWarnings("unchecked")
    public String encryptCredential(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        return withResilience("encryptCredential", () -> {
            Map<String, Object> response = post("/api/encrypt/credential/encrypt",
                    Map.of("value", plaintext), Map.class);
            return (String) response.get("encrypted");
        });
    }

    /** Decrypt a Base64-encoded ciphertext back to plaintext. */
    @SuppressWarnings("unchecked")
    public String decryptCredential(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return encrypted;
        return withResilience("decryptCredential", () -> {
            Map<String, Object> response = post("/api/encrypt/credential/decrypt",
                    Map.of("encrypted", encrypted), Map.class);
            return (String) response.get("value");
        });
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private byte[] postMultipartForBytes(String path, Path filePath) {
        try {
            var headers = multipartHeaders();
            var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
            body.add("file", new org.springframework.core.io.FileSystemResource(filePath.toFile()));
            var entity = new org.springframework.http.HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(baseUrl() + path, entity, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            throw serviceError("file encryption/decryption", e);
        }
    }

    @Override
    protected String healthPath() {
        return "/actuator/health";
    }
}
