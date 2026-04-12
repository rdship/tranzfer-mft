package com.filetransfer.encryption.controller;

import com.filetransfer.encryption.service.AesService;
import com.filetransfer.encryption.service.PgpService;
import com.filetransfer.shared.client.KeystoreServiceClient;
import com.filetransfer.shared.entity.EncryptionKey;
import com.filetransfer.shared.enums.EncryptionAlgorithm;
import com.filetransfer.shared.repository.EncryptionKeyRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Encryption API
 *
 * POST /api/encrypt?keyId={id}         — encrypt uploaded file, returns encrypted bytes
 * POST /api/decrypt?keyId={id}         — decrypt uploaded file, returns plain bytes
 * POST /api/encrypt/base64?keyId={id}  — encrypt Base64 payload, return Base64
 * POST /api/decrypt/base64?keyId={id}  — decrypt Base64 payload, return Base64
 */
@Slf4j
@RestController
@RequestMapping("/api/encrypt")
@RequiredArgsConstructor
public class EncryptionController {

    private final PgpService pgpService;
    private final AesService aesService;
    private final EncryptionKeyRepository keyRepository;

    /** Keystore-manager client — single source of truth for keys in distributed deployments. */
    @Autowired(required = false)
    @Nullable
    private KeystoreServiceClient keystoreClient;

    @Value("${encryption.master-key}")
    private String masterKeyHex;

    private String masterKeyBase64;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    private static final String INSECURE_MASTER_KEY = "0000000000000000000000000000000000000000000000000000000000000000";

    @PostConstruct
    void initMasterKey() {
        // Fail-fast: refuse to start with default master key in production
        if (INSECURE_MASTER_KEY.equals(masterKeyHex)) {
            String msg = "Encryption master key is using the default insecure value! Set ENCRYPTION_MASTER_KEY environment variable.";
            if (activeProfile.contains("prod")) {
                throw new IllegalStateException(msg);
            }
            log.warn("SECURITY: {} (acceptable in dev, FATAL in production)", msg);
        }

        byte[] keyBytes = new byte[masterKeyHex.length() / 2];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) Integer.parseInt(masterKeyHex.substring(i * 2, i * 2 + 2), 16);
        }
        masterKeyBase64 = Base64.getEncoder().encodeToString(keyBytes);
        log.info("Master key initialized for credential encryption (AES-256-GCM)");
    }

    @PostMapping(value = "/encrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> encryptFile(@RequestParam UUID keyId,
                                               @RequestPart("file") MultipartFile file) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] result = performEncrypt(file.getBytes(), key);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + file.getOriginalFilename() + ".enc\"")
                .body(result);
    }

    @PostMapping(value = "/decrypt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> decryptFile(@RequestParam UUID keyId,
                                               @RequestParam(required = false) String passphrase,
                                               @RequestPart("file") MultipartFile file) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] result = performDecrypt(file.getBytes(), key, passphrase);
        String filename = file.getOriginalFilename();
        if (filename != null && filename.endsWith(".enc")) filename = filename.substring(0, filename.length() - 4);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(result);
    }

    @PostMapping("/encrypt/base64")
    public String encryptBase64(@RequestParam UUID keyId, @RequestBody String base64Input) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] plain = Base64.getDecoder().decode(base64Input);
        byte[] encrypted = performEncrypt(plain, key);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    @PostMapping("/decrypt/base64")
    public String decryptBase64(@RequestParam UUID keyId,
                                @RequestParam(required = false) String passphrase,
                                @RequestBody String base64Input) throws Exception {
        EncryptionKey key = findKey(keyId);
        byte[] cipher = Base64.getDecoder().decode(base64Input);
        byte[] plain = performDecrypt(cipher, key, passphrase);
        return Base64.getEncoder().encodeToString(plain);
    }

    // --- Credential encryption (uses master key directly, no keyId needed) ---

    @PostMapping("/credential/encrypt")
    public Map<String, String> encryptCredential(@RequestBody Map<String, String> request) throws Exception {
        String plaintext = request.get("value");
        if (plaintext == null || plaintext.isEmpty()) {
            return Map.of("encrypted", "");
        }
        byte[] encrypted = aesService.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), masterKeyBase64);
        return Map.of("encrypted", Base64.getEncoder().encodeToString(encrypted));
    }

    @PostMapping("/credential/decrypt")
    public Map<String, String> decryptCredential(@RequestBody Map<String, String> request) throws Exception {
        String encrypted = request.get("encrypted");
        if (encrypted == null || encrypted.isEmpty()) {
            return Map.of("value", "");
        }
        byte[] ciphertext = Base64.getDecoder().decode(encrypted);
        byte[] decrypted = aesService.decrypt(ciphertext, masterKeyBase64);
        return Map.of("value", new String(decrypted, StandardCharsets.UTF_8));
    }

    private byte[] performEncrypt(byte[] data, EncryptionKey key) throws Exception {
        if (key.getAlgorithm() == EncryptionAlgorithm.PGP) {
            if (key.getPublicKey() == null) throw new IllegalArgumentException("No public key for PGP encryption");
            return pgpService.encrypt(data, key.getPublicKey());
        } else {
            if (key.getEncryptedSymmetricKey() == null) throw new IllegalArgumentException("No symmetric key for AES encryption");
            String unwrappedKey = unwrapSymmetricKey(key.getEncryptedSymmetricKey());
            return aesService.encrypt(data, unwrappedKey);
        }
    }

    private byte[] performDecrypt(byte[] data, EncryptionKey key, String passphrase) throws Exception {
        if (key.getAlgorithm() == EncryptionAlgorithm.PGP) {
            if (key.getEncryptedPrivateKey() == null) throw new IllegalArgumentException("No private key for PGP decryption");
            char[] pass;
            if (passphrase != null && !passphrase.isEmpty()) {
                pass = passphrase.toCharArray();
            } else {
                log.warn("PGP decrypt for key '{}': no passphrase provided, using empty passphrase", key.getKeyName());
                pass = new char[0];
            }
            return pgpService.decrypt(data, key.getEncryptedPrivateKey(), pass);
        } else {
            if (key.getEncryptedSymmetricKey() == null) throw new IllegalArgumentException("No symmetric key for AES decryption");
            String unwrappedKey = unwrapSymmetricKey(key.getEncryptedSymmetricKey());
            return aesService.decrypt(data, unwrappedKey);
        }
    }

    /**
     * Unwrap an AES symmetric key that was wrapped (AES-KW) with the master key.
     * If unwrapping fails (e.g. key was stored as raw Base64 in dev mode),
     * falls back to using the key as-is for backwards compatibility.
     */
    private String unwrapSymmetricKey(String wrappedKeyBase64) {
        try {
            byte[] masterBytes = Base64.getDecoder().decode(masterKeyBase64);
            javax.crypto.SecretKey masterKey = new javax.crypto.spec.SecretKeySpec(masterBytes, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AESWrap");
            cipher.init(javax.crypto.Cipher.UNWRAP_MODE, masterKey);
            java.security.Key unwrapped = cipher.unwrap(
                    Base64.getDecoder().decode(wrappedKeyBase64), "AES", javax.crypto.Cipher.SECRET_KEY);
            return Base64.getEncoder().encodeToString(unwrapped.getEncoded());
        } catch (Exception e) {
            log.debug("AES key unwrap skipped (key may be stored as raw Base64): {}", e.getMessage());
            return wrappedKeyBase64;
        }
    }

    private EncryptionKey findKey(UUID keyId) {
        // Primary: local encryption_keys table
        var localKey = keyRepository.findByIdAndActiveTrue(keyId);
        if (localKey.isPresent()) return localKey.get();

        // Fallback: query keystore-manager by alias (UUID string as alias)
        if (keystoreClient != null) {
            try {
                Map<String, Object> managed = keystoreClient.getKey(keyId.toString());
                if (managed != null) {
                    log.info("Key {} resolved from keystore-manager (alias={})", keyId, managed.get("alias"));
                    return buildKeyFromKeystore(managed);
                }
            } catch (Exception e) {
                log.debug("Keystore-manager lookup failed for {}: {}", keyId, e.getMessage());
            }
        }

        throw new IllegalArgumentException("Encryption key not found or inactive: " + keyId);
    }

    /**
     * Resolve key by alias (string). Allows flows to reference keys by human-readable name
     * instead of UUID — e.g., config: {"keyAlias": "partner-acme-pgp"}.
     */
    EncryptionKey findKeyByAlias(String alias) {
        if (keystoreClient != null) {
            try {
                Map<String, Object> managed = keystoreClient.getKey(alias);
                if (managed != null) {
                    return buildKeyFromKeystore(managed);
                }
            } catch (Exception e) {
                log.debug("Keystore-manager alias lookup failed for '{}': {}", alias, e.getMessage());
            }
        }
        throw new IllegalArgumentException("Key not found by alias: " + alias);
    }

    /**
     * Bridge: convert a keystore-manager ManagedKey response into an EncryptionKey
     * so the existing encrypt/decrypt logic works unchanged.
     */
    private EncryptionKey buildKeyFromKeystore(Map<String, Object> managed) {
        String keyType = (String) managed.getOrDefault("keyType", "");
        String keyMaterial = (String) managed.get("keyMaterial");
        String publicKeyMaterial = (String) managed.get("publicKeyMaterial");

        EncryptionKey key = new EncryptionKey();
        key.setKeyName((String) managed.getOrDefault("alias", "keystore-managed"));
        key.setActive(true);

        if (keyType.contains("PGP")) {
            key.setAlgorithm(EncryptionAlgorithm.PGP);
            key.setPublicKey(publicKeyMaterial != null ? publicKeyMaterial : keyMaterial);
            key.setEncryptedPrivateKey(keyMaterial); // PGP private key (ASCII-armored)
        } else {
            key.setAlgorithm(EncryptionAlgorithm.AES_256_GCM);
            key.setEncryptedSymmetricKey(keyMaterial); // Raw Base64 key (unwrap will fallback)
        }
        return key;
    }
}
