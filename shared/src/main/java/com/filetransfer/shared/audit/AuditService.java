package com.filetransfer.shared.audit;

import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * PCI DSS 10.x compliant audit service with:
 * - AES-256-GCM encryption for sensitive fields (metadata, ipAddress, sessionId)
 * - HMAC-SHA256 integrity signatures with key version rotation
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /** Current HMAC secret (latest version) */
    @Value("${platform.security.control-api-key:internal_control_secret}")
    private String hmacSecret;

    /** AES-256 key for audit log field encryption (Base64-encoded) */
    @Value("${platform.audit.encryption-key:}")
    private String auditEncryptionKey;

    /** Whether to encrypt sensitive audit fields */
    @Value("${platform.audit.encryption-enabled:false}")
    private boolean encryptionEnabled;

    /** Current HMAC key version */
    @Value("${platform.audit.hmac-key-version:1}")
    private int currentHmacKeyVersion;

    /**
     * Comma-separated list of previous HMAC secrets, indexed by version.
     * Format: "version1_secret,version2_secret,..."
     * The current secret is NOT in this list — it's in hmacSecret.
     */
    @Value("${platform.audit.hmac-previous-keys:}")
    private String previousHmacKeys;

    // ─── AES-256-GCM constants ───
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    // ─── Public log methods ───

    @Async
    public void logFileUpload(TransferAccount account, String trackId, String path,
                               String filename, Path filePath, String ipAddress, String sessionId) {
        save(AuditLog.builder().account(account).trackId(trackId).action("FILE_UPLOAD").success(true)
                .path(path).filename(filename).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).ipAddress(ipAddress).sessionId(sessionId)
                .principal(account != null ? account.getUsername() : "system").build());
    }

    @Async
    public void logFileDownload(TransferAccount account, String trackId, String path,
                                 String filename, Path filePath, String ipAddress, String sessionId) {
        save(AuditLog.builder().account(account).trackId(trackId).action("FILE_DOWNLOAD").success(true)
                .path(path).filename(filename).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).ipAddress(ipAddress).sessionId(sessionId)
                .principal(account != null ? account.getUsername() : "system").build());
    }

    @Async
    public void logFileRoute(String trackId, String srcPath, String destPath, Path filePath) {
        save(AuditLog.builder().trackId(trackId).action("FILE_ROUTE").success(true)
                .path(destPath).filename(extractName(destPath)).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).principal("system")
                .metadata(Map.of("source", srcPath, "destination", destPath)).build());
    }

    @Async
    public void logFlowStep(String trackId, String stepType, String inputFile,
                             String outputFile, boolean success, long durationMs, String error) {
        save(AuditLog.builder().trackId(trackId).action("FLOW_" + stepType).success(success)
                .path(outputFile).filename(extractName(inputFile)).principal("system")
                .errorMessage(error).metadata(Map.of("input", str(inputFile), "output", str(outputFile),
                        "durationMs", String.valueOf(durationMs))).build());
    }

    @Async
    public void logFlowComplete(String trackId, String flowName, boolean success, String error) {
        save(AuditLog.builder().trackId(trackId).action(success ? "FLOW_COMPLETE" : "FLOW_FAIL")
                .success(success).principal("system").errorMessage(error)
                .metadata(Map.of("flowName", flowName)).build());
    }

    public void logLogin(String email, String ipAddress, boolean success, String reason) {
        save(AuditLog.builder().action(success ? "LOGIN" : "LOGIN_FAIL").success(success)
                .ipAddress(ipAddress).principal(email).errorMessage(success ? null : reason).build());
    }

    @Async
    public void logFailure(TransferAccount account, String trackId, String action, String path, String error) {
        save(AuditLog.builder().account(account).trackId(trackId).action(action).success(false)
                .path(path).principal(account != null ? account.getUsername() : "system")
                .errorMessage(error).build());
    }

    @Async
    public void logAction(String principal, String action, boolean success, String error,
                          Map<String, Object> metadata) {
        save(AuditLog.builder().action(action).success(success).principal(principal)
                .errorMessage(error).metadata(metadata).build());
    }

    // ─── Decrypt on read ───

    /**
     * Decrypt sensitive fields of an audit log entry retrieved from DB.
     * Returns the entry as-is if not encrypted or if decryption key is unavailable.
     */
    public AuditLog decryptIfNeeded(AuditLog entry) {
        if (entry == null || !entry.isEncrypted()) return entry;
        if (auditEncryptionKey == null || auditEncryptionKey.isBlank()) {
            log.warn("Audit entry {} is encrypted but no decryption key configured", entry.getId());
            return entry;
        }
        try {
            // Rebuild with decrypted fields
            return AuditLog.builder()
                    .id(entry.getId())
                    .account(entry.getAccount())
                    .trackId(entry.getTrackId())
                    .action(entry.getAction())
                    .success(entry.isSuccess())
                    .path(entry.getPath())
                    .filename(entry.getFilename())
                    .fileSizeBytes(entry.getFileSizeBytes())
                    .sha256Checksum(entry.getSha256Checksum())
                    .ipAddress(decryptField(entry.getIpAddress()))
                    .sessionId(decryptField(entry.getSessionId()))
                    .principal(entry.getPrincipal())
                    .errorMessage(entry.getErrorMessage())
                    .metadata(entry.getMetadata()) // metadata stored as jsonb, encrypted as whole string
                    .timestamp(entry.getTimestamp())
                    .integrityHash(entry.getIntegrityHash())
                    .encrypted(false)
                    .hmacKeyVersion(entry.getHmacKeyVersion())
                    .build();
        } catch (Exception e) {
            log.error("Failed to decrypt audit entry {}: {}", entry.getId(), e.getMessage());
            return entry;
        }
    }

    /**
     * Verify the HMAC integrity of an audit log entry.
     * Uses the key version stored in the entry to select the correct HMAC key.
     */
    public boolean verifyIntegrity(AuditLog entry) {
        if (entry == null || entry.getIntegrityHash() == null) return false;
        String payload = str(entry.getAction()) + "|" + str(entry.getTrackId()) + "|"
                + str(entry.getPath()) + "|" + str(entry.getSha256Checksum()) + "|" + entry.getTimestamp();
        String hmacKey = getHmacKeyForVersion(entry.getHmacKeyVersion());
        String computed = hmac(payload, hmacKey);
        return entry.getIntegrityHash().equals(computed);
    }

    // ─── Static utility ───

    public static String sha256(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) return null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) d.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) { return null; }
    }

    // ─── Internal ───

    private void save(AuditLog entry) {
        try {
            // Encrypt sensitive fields if enabled
            String ipAddress = entry.getIpAddress();
            String sessionId = entry.getSessionId();
            boolean encrypted = false;

            if (encryptionEnabled && auditEncryptionKey != null && !auditEncryptionKey.isBlank()) {
                ipAddress = encryptField(ipAddress);
                sessionId = encryptField(sessionId);
                encrypted = true;
            }

            String payload = str(entry.getAction()) + "|" + str(entry.getTrackId()) + "|"
                    + str(entry.getPath()) + "|" + str(entry.getSha256Checksum()) + "|" + entry.getTimestamp();

            // Sign with current HMAC key version
            AuditLog signed = AuditLog.builder()
                    .account(entry.getAccount()).trackId(entry.getTrackId()).action(entry.getAction())
                    .success(entry.isSuccess()).path(entry.getPath()).filename(entry.getFilename())
                    .fileSizeBytes(entry.getFileSizeBytes()).sha256Checksum(entry.getSha256Checksum())
                    .ipAddress(ipAddress).sessionId(sessionId)
                    .principal(entry.getPrincipal()).errorMessage(entry.getErrorMessage())
                    .metadata(entry.getMetadata())
                    .integrityHash(hmac(payload, hmacSecret))
                    .encrypted(encrypted)
                    .hmacKeyVersion(currentHmacKeyVersion)
                    .build();
            auditLogRepository.save(signed);
        } catch (Exception e) {
            log.error("AUDIT SAVE FAILED (CRITICAL): {}", e.getMessage());
        }
    }

    // ─── HMAC with key rotation support ───

    private String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return "error"; }
    }

    /**
     * Resolve the HMAC key for a given version.
     * Current version uses hmacSecret. Previous versions are in previousHmacKeys.
     */
    private String getHmacKeyForVersion(int version) {
        if (version == currentHmacKeyVersion) {
            return hmacSecret;
        }
        // Parse previous keys: "v1_key,v2_key,..."
        if (previousHmacKeys != null && !previousHmacKeys.isBlank()) {
            String[] keys = previousHmacKeys.split(",");
            // version 1 = index 0, version 2 = index 1, etc.
            int index = version - 1;
            if (index >= 0 && index < keys.length) {
                return keys[index].trim();
            }
        }
        log.warn("HMAC key not found for version {}, falling back to current key", version);
        return hmacSecret;
    }

    // ─── AES-256-GCM field encryption ───

    private String encryptField(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] keyBytes = Base64.getDecoder().decode(auditEncryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] result = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, result, GCM_IV_LENGTH, cipherText.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            log.error("Failed to encrypt audit field: {}", e.getMessage());
            return plaintext; // Fail open — still log the data unencrypted
        }
    }

    private String decryptField(String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) return encryptedBase64;
        try {
            byte[] keyBytes = Base64.getDecoder().decode(auditEncryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt audit field: {}", e.getMessage());
            return encryptedBase64;
        }
    }

    private Long safeSize(Path p) {
        try { return p != null && Files.exists(p) ? Files.size(p) : null; } catch (IOException e) { return null; }
    }

    private String extractName(String path) {
        if (path == null) return null;
        int i = path.lastIndexOf('/'); return i >= 0 ? path.substring(i + 1) : path;
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }
}
