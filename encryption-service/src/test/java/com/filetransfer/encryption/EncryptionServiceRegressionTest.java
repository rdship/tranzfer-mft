package com.filetransfer.encryption;

import com.filetransfer.encryption.service.AesService;
import com.filetransfer.encryption.service.PgpService;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression, usability, and performance tests for encryption-service.
 * Pure JUnit 5 — no Spring context.
 */
class EncryptionServiceRegressionTest {

    private AesService aesService;
    private PgpService pgpService;

    private static String armoredPublicKey;
    private static String armoredPrivateKey;
    private static final char[] PASSPHRASE = "regression-test-pass".toCharArray();

    @BeforeAll
    static void generatePgpKeypair() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(HashAlgorithmTags.SHA1);

        PGPSecretKey secretKey = new PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION, pgpKeyPair,
                "Regression <regression@test.com>", sha1Calc,
                null, null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .setProvider("BC").build(PASSPHRASE));

        ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armoredPub = new ArmoredOutputStream(pubOut)) {
            secretKey.getPublicKey().encode(armoredPub);
        }
        armoredPublicKey = pubOut.toString();

        ByteArrayOutputStream privOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armoredPriv = new ArmoredOutputStream(privOut)) {
            secretKey.encode(armoredPriv);
        }
        armoredPrivateKey = privOut.toString();
    }

    @BeforeEach
    void setUp() {
        aesService = new AesService();
        pgpService = new PgpService();
    }

    // ── AES round-trip ─────────────────────────────────────────────────

    @Test
    void aesEncrypt_decrypt_roundtrip_shouldProduceOriginalData() throws Exception {
        String key = aesService.generateKey();
        byte[] original = "Regression test data with unicode: äöü".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = aesService.encrypt(original, key);
        byte[] decrypted = aesService.decrypt(encrypted, key);

        assertArrayEquals(original, decrypted,
                "AES encrypt/decrypt round-trip must produce original data");
    }

    // ── PGP sign (encrypt+decrypt) round-trip ──────────────────────────

    @Test
    void pgpService_encryptAndDecrypt_shouldValidate() throws Exception {
        byte[] plaintext = "PGP regression test content".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = pgpService.encrypt(plaintext, armoredPublicKey);
        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0);

        byte[] recovered = pgpService.decrypt(ciphertext, armoredPrivateKey, PASSPHRASE);
        assertArrayEquals(plaintext, recovered,
                "PGP encrypt/decrypt round-trip must recover original plaintext");
    }

    // ── AES empty input ────────────────────────────────────────────────

    @Test
    void aesEncrypt_emptyInput_shouldHandleGracefully() throws Exception {
        String key = aesService.generateKey();
        byte[] empty = new byte[0];

        byte[] ciphertext = aesService.encrypt(empty, key);
        assertNotNull(ciphertext, "Empty input should not return null");
        assertTrue(ciphertext.length > 0, "Empty plaintext should produce IV + auth tag");

        byte[] recovered = aesService.decrypt(ciphertext, key);
        assertArrayEquals(empty, recovered, "Decrypting empty plaintext should return empty");
    }

    // ── AES 10MB performance ───────────────────────────────────────────

    @Test
    void aesEncrypt_largePayload_10mb_shouldCompleteUnder2s() throws Exception {
        String key = aesService.generateKey();
        byte[] payload = new byte[10 * 1024 * 1024]; // 10 MB
        new SecureRandom().nextBytes(payload);

        long start = System.nanoTime();
        byte[] ciphertext = aesService.encrypt(payload, key);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(ciphertext);
        assertTrue(elapsedMs < 2000,
                "10 MB AES encrypt took " + elapsedMs + "ms — must complete under 2000ms");
    }

    // ── AES null input ─────────────────────────────────────────────────

    @Test
    void aesEncrypt_nullInput_shouldThrowClearError() throws Exception {
        String key = aesService.generateKey();

        Exception ex = assertThrows(Exception.class, () -> aesService.encrypt(null, key),
                "Null plaintext must throw an exception, not return null");
        assertNotNull(ex.getMessage(), "Exception should have a clear message");
    }

    // ── AES throughput benchmark ───────────────────────────────────────

    @Test
    void encryption_throughput_1mb_100iterations() throws Exception {
        String key = aesService.generateKey();
        byte[] payload = new byte[1024 * 1024]; // 1 MB
        new SecureRandom().nextBytes(payload);

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            aesService.encrypt(payload, key);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        double mbPerSec = (100.0 * 1.0) / (elapsedMs / 1000.0);
        System.out.println("[BENCHMARK] AES-256-GCM throughput: " + String.format("%.1f", mbPerSec) + " MB/s (" + elapsedMs + "ms for 100 MB)");

        // Sanity: 100 MB should complete in under 30 seconds on any modern hardware
        assertTrue(elapsedMs < 30_000,
                "100 x 1MB AES encrypt took " + elapsedMs + "ms — should complete under 30s");
    }
}
