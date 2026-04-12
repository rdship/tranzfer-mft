package com.filetransfer.storage.backend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Transparent encryption-at-rest wrapper for any StorageBackend.
 *
 * <p>Wraps the active backend (local or S3) and encrypts all written data
 * with AES-256-GCM before it reaches disk/S3. Decrypts on read.
 * The encryption key is loaded from the STORAGE_ENCRYPTION_KEY env var
 * (Base64-encoded 256-bit key).
 *
 * <p>Each file gets a unique 12-byte IV prepended to the ciphertext.
 * Format: [IV (12 bytes)][AES-GCM ciphertext + auth tag]
 *
 * <p>Enable with: {@code storage.encryption.enabled=true}
 * <p>Compliant with: PCI-DSS 3.4, HIPAA 164.312(a)(2)(iv)
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "storage.encryption.enabled", havingValue = "true")
public class EncryptionAtRestWrapper implements StorageBackend {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128; // bits

    private final StorageBackend delegate;
    private final SecretKey encryptionKey;

    public EncryptionAtRestWrapper(
            StorageBackend delegate,
            @Value("${storage.encryption.key:}") String keyBase64) {
        this.delegate = delegate;
        if (keyBase64 == null || keyBase64.isBlank()) {
            // Generate ephemeral key for dev — log warning
            byte[] devKey = new byte[32];
            new SecureRandom().nextBytes(devKey);
            this.encryptionKey = new SecretKeySpec(devKey, "AES");
            log.warn("SECURITY: storage.encryption.key not set — using ephemeral key. "
                    + "Data will be unreadable after restart! Set STORAGE_ENCRYPTION_KEY env var.");
        } else {
            this.encryptionKey = new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
            log.info("Storage encryption-at-rest enabled (AES-256-GCM)");
        }
    }

    @Override
    public String type() {
        return delegate.type() + "+encrypted";
    }

    @Override
    public WriteResult write(InputStream data, long sizeBytes, String filename) throws Exception {
        // Generate unique IV
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // Create encrypting stream: IV prefix + AES-GCM ciphertext
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));

        // Wrap input in encrypting pipeline: prepend IV, then encrypted data
        InputStream encryptedStream = new SequenceInputStream(
                new ByteArrayInputStream(iv),
                new CipherInputStream(data, cipher));

        // Delegate to underlying backend (local or S3)
        // Size estimate: IV + ciphertext (slightly larger due to GCM tag)
        long estimatedSize = IV_LENGTH + sizeBytes + 16; // 16 bytes for GCM auth tag
        return delegate.write(encryptedStream, estimatedSize, filename);
    }

    @Override
    @Deprecated
    public ReadResult read(String storageKey) throws Exception {
        ReadResult encrypted = delegate.read(storageKey);
        byte[] raw = encrypted.data();

        // Extract IV from first 12 bytes
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);

        // Decrypt remaining bytes
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);

        return new ReadResult(plain, plain.length, storageKey, encrypted.contentType());
    }

    @Override
    public void readTo(String storageKey, OutputStream target) throws Exception {
        // Stream decrypt: read IV, then decrypt rest
        try (InputStream encrypted = delegate.readStream(storageKey)) {
            byte[] iv = encrypted.readNBytes(IV_LENGTH);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));

            try (CipherInputStream decrypted = new CipherInputStream(encrypted, cipher)) {
                decrypted.transferTo(target);
            }
        }
    }

    @Override
    public InputStream readStream(String storageKey) throws Exception {
        InputStream encrypted = delegate.readStream(storageKey);
        byte[] iv = encrypted.readNBytes(IV_LENGTH);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));
        return new CipherInputStream(encrypted, cipher);
    }

    @Override
    public boolean exists(String storageKey) {
        return delegate.exists(storageKey);
    }

    @Override
    public void delete(String storageKey) {
        delegate.delete(storageKey);
    }
}
