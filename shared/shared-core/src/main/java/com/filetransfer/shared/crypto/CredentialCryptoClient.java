package com.filetransfer.shared.crypto;

import com.filetransfer.shared.client.EncryptionServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Client for the Encryption Service's credential encrypt/decrypt API.
 * Uses AES-256-GCM with the platform master key — no keyId needed.
 *
 * <p>Delegates to {@link EncryptionServiceClient} for the actual HTTP calls.
 * Kept for backward compatibility — existing code that injects
 * CredentialCryptoClient continues to work unchanged.
 */
@Slf4j
@Component
public class CredentialCryptoClient {

    private final EncryptionServiceClient encryptionService;

    public CredentialCryptoClient(EncryptionServiceClient encryptionService) {
        this.encryptionService = encryptionService;
    }

    /**
     * Encrypt a plaintext credential value.
     * Returns a Base64-encoded AES-256-GCM ciphertext.
     */
    public String encrypt(String plaintext) {
        return encryptionService.encryptCredential(plaintext);
    }

    /**
     * Decrypt a Base64-encoded AES-256-GCM ciphertext back to plaintext.
     */
    public String decrypt(String encrypted) {
        return encryptionService.decryptCredential(encrypted);
    }
}
