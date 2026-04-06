package com.filetransfer.encryption.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AesService (AES-256-GCM).
 * No Spring context — pure crypto validation.
 */
class AesServiceTest {

    private AesService aesService;

    @BeforeEach
    void setUp() {
        aesService = new AesService();
    }

    // --- Key generation ---

    @Test
    void generateKey_shouldProduceBase64Encoded256BitKey() throws Exception {
        String key = aesService.generateKey();
        assertNotNull(key);
        assertFalse(key.isBlank());

        byte[] decoded = Base64.getDecoder().decode(key);
        assertEquals(32, decoded.length, "AES-256 key must decode to 32 bytes");
    }

    @Test
    void generateKey_shouldProduceDifferentKeysEachTime() throws Exception {
        String key1 = aesService.generateKey();
        String key2 = aesService.generateKey();
        assertNotEquals(key1, key2, "Two generated keys should not be identical");
    }

    // --- Encryption basics ---

    @Test
    void encrypt_shouldProduceOutputDifferentFromPlaintext() throws Exception {
        String key = aesService.generateKey();
        byte[] plaintext = "Sensitive data here".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesService.encrypt(plaintext, key);

        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0);
        assertFalse(Arrays.equals(plaintext, ciphertext),
                "Ciphertext must differ from plaintext");
    }

    @Test
    void encrypt_outputShouldBeValidBytes_andBase64Encodable() throws Exception {
        String key = aesService.generateKey();
        byte[] plaintext = "test payload".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesService.encrypt(plaintext, key);

        // The raw output is IV + ciphertext bytes; confirm it can be Base64-encoded
        String encoded = Base64.getEncoder().encodeToString(ciphertext);
        assertNotNull(encoded);
        assertFalse(encoded.isBlank());
        // Round-trip through Base64
        assertArrayEquals(ciphertext, Base64.getDecoder().decode(encoded));
    }

    // --- Round-trip encrypt/decrypt ---

    @Test
    void roundTrip_shouldRecoverOriginalPlaintext() throws Exception {
        String key = aesService.generateKey();
        byte[] plaintext = "Round-trip test: \u00e9\u00e8\u00ea unicode!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesService.encrypt(plaintext, key);
        byte[] recovered = aesService.decrypt(ciphertext, key);

        assertArrayEquals(plaintext, recovered,
                "Decrypted output must exactly match original plaintext");
    }

    // --- Different plaintexts produce different ciphertexts ---

    @Test
    void encrypt_differentPlaintexts_shouldProduceDifferentCiphertexts() throws Exception {
        String key = aesService.generateKey();
        byte[] plain1 = "Message Alpha".getBytes(StandardCharsets.UTF_8);
        byte[] plain2 = "Message Bravo".getBytes(StandardCharsets.UTF_8);

        byte[] cipher1 = aesService.encrypt(plain1, key);
        byte[] cipher2 = aesService.encrypt(plain2, key);

        assertFalse(Arrays.equals(cipher1, cipher2),
                "Different plaintexts must produce different ciphertexts");
    }

    // --- Random IV: same plaintext encrypted twice differs ---

    @Test
    void encrypt_samePlaintextTwice_shouldProduceDifferentCiphertexts() throws Exception {
        String key = aesService.generateKey();
        byte[] plaintext = "identical content".getBytes(StandardCharsets.UTF_8);

        byte[] cipher1 = aesService.encrypt(plaintext, key);
        byte[] cipher2 = aesService.encrypt(plaintext, key);

        assertFalse(Arrays.equals(cipher1, cipher2),
                "Same plaintext encrypted twice must differ due to random IV");
    }

    // --- Decrypt with wrong key ---

    @Test
    void decrypt_withWrongKey_shouldThrowException() throws Exception {
        String correctKey = aesService.generateKey();
        String wrongKey = aesService.generateKey();
        byte[] plaintext = "secret stuff".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = aesService.encrypt(plaintext, correctKey);

        assertThrows(Exception.class, () -> aesService.decrypt(ciphertext, wrongKey),
                "Decrypting with wrong key must throw (AES-GCM tag mismatch)");
    }

    // --- Null/empty plaintext ---

    @Test
    void encrypt_emptyPlaintext_shouldHandleGracefully() throws Exception {
        String key = aesService.generateKey();
        byte[] empty = new byte[0];

        byte[] ciphertext = aesService.encrypt(empty, key);
        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0, "Even empty plaintext should produce IV + auth tag");

        byte[] recovered = aesService.decrypt(ciphertext, key);
        assertArrayEquals(empty, recovered, "Decrypting empty plaintext should return empty");
    }

    @Test
    void encrypt_nullPlaintext_shouldThrowException() {
        assertThrows(Exception.class, () -> {
            String key = aesService.generateKey();
            aesService.encrypt(null, key);
        }, "Null plaintext should throw");
    }

    // --- Large payload (1 MB) ---

    @Test
    void roundTrip_largePayload_shouldPreserveDataExactly() throws Exception {
        String key = aesService.generateKey();
        byte[] largePayload = new byte[1024 * 1024]; // 1 MB
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 251);
        }

        byte[] ciphertext = aesService.encrypt(largePayload, key);
        assertNotNull(ciphertext);
        assertFalse(Arrays.equals(largePayload, ciphertext));

        byte[] recovered = aesService.decrypt(ciphertext, key);
        assertArrayEquals(largePayload, recovered,
                "1 MB payload must survive encrypt/decrypt round-trip intact");
    }
}
