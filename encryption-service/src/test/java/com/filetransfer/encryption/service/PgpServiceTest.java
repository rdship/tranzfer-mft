package com.filetransfer.encryption.service;

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
 * Unit tests for PgpService (Bouncy Castle PGP).
 * Bouncy Castle is available (bcpg-jdk18on 1.77 + bcprov-jdk18on 1.77 in pom.xml).
 * No Spring context needed.
 */
class PgpServiceTest {

    private PgpService pgpService;

    private static String armoredPublicKey;
    private static String armoredPrivateKey;
    private static final char[] PASSPHRASE = "test-passphrase-1234".toCharArray();

    @BeforeAll
    static void generateTestKeypair() throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());

        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(HashAlgorithmTags.SHA1);

        PGPSecretKey secretKey = new PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION, pgpKeyPair,
                "Test User <test@example.com>", sha1Calc,
                null, null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .setProvider("BC").build(PASSPHRASE));

        // Export armored public key
        ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armoredPub = new ArmoredOutputStream(pubOut)) {
            secretKey.getPublicKey().encode(armoredPub);
        }
        armoredPublicKey = pubOut.toString();

        // Export armored private key
        ByteArrayOutputStream privOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armoredPriv = new ArmoredOutputStream(privOut)) {
            secretKey.encode(armoredPriv);
        }
        armoredPrivateKey = privOut.toString();
    }

    @BeforeEach
    void setUp() {
        pgpService = new PgpService();
    }

    // --- Encrypt produces non-empty output ---

    @Test
    void encrypt_withPublicKey_shouldProduceNonEmptyOutput() throws Exception {
        byte[] plaintext = "Hello PGP!".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = pgpService.encrypt(plaintext, armoredPublicKey);

        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0, "PGP ciphertext must not be empty");
        // Ciphertext should differ from plaintext
        assertFalse(java.util.Arrays.equals(plaintext, ciphertext),
                "PGP ciphertext must not be identical to plaintext");
    }

    // --- Round-trip encrypt/decrypt ---

    @Test
    void roundTrip_shouldRecoverOriginalPlaintext() throws Exception {
        byte[] plaintext = "PGP round-trip test content \u00e9\u00e8".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = pgpService.encrypt(plaintext, armoredPublicKey);
        byte[] recovered = pgpService.decrypt(ciphertext, armoredPrivateKey, PASSPHRASE);

        assertArrayEquals(plaintext, recovered,
                "PGP decrypt must recover the original plaintext exactly");
    }

    // --- Decrypt with wrong key fails ---

    @Test
    void decrypt_withWrongKey_shouldFail() throws Exception {
        // Generate a second, different keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048, new SecureRandom());
        KeyPair wrongKp = kpg.generateKeyPair();

        PGPKeyPair wrongPgpPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, wrongKp, new Date());
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build()
                .get(HashAlgorithmTags.SHA1);
        char[] wrongPass = "wrong-pass".toCharArray();
        PGPSecretKey wrongSecret = new PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION, wrongPgpPair,
                "Wrong User <wrong@example.com>", sha1Calc,
                null, null,
                new JcaPGPContentSignerBuilder(wrongPgpPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc)
                        .setProvider("BC").build(wrongPass));

        ByteArrayOutputStream wrongPrivOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armoredPriv = new ArmoredOutputStream(wrongPrivOut)) {
            wrongSecret.encode(armoredPriv);
        }
        String wrongArmoredPrivateKey = wrongPrivOut.toString();

        // Encrypt with the correct public key
        byte[] plaintext = "secret for correct recipient".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = pgpService.encrypt(plaintext, armoredPublicKey);

        // Decrypt with the wrong private key should fail
        assertThrows(Exception.class,
                () -> pgpService.decrypt(ciphertext, wrongArmoredPrivateKey, wrongPass),
                "Decrypting with a non-matching private key must throw");
    }

    // --- Large payload ---

    @Test
    void roundTrip_largePayload_shouldPreserveData() throws Exception {
        byte[] largePayload = new byte[64 * 1024]; // 64 KB
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 251);
        }

        byte[] ciphertext = pgpService.encrypt(largePayload, armoredPublicKey);
        byte[] recovered = pgpService.decrypt(ciphertext, armoredPrivateKey, PASSPHRASE);

        assertArrayEquals(largePayload, recovered,
                "PGP round-trip on large payload must preserve data exactly");
    }

    // --- Empty plaintext ---

    @Test
    void roundTrip_emptyPlaintext_shouldHandleGracefully() throws Exception {
        byte[] empty = new byte[0];

        byte[] ciphertext = pgpService.encrypt(empty, armoredPublicKey);
        assertNotNull(ciphertext);
        assertTrue(ciphertext.length > 0);

        byte[] recovered = pgpService.decrypt(ciphertext, armoredPrivateKey, PASSPHRASE);
        assertArrayEquals(empty, recovered, "PGP round-trip on empty input should return empty");
    }
}
