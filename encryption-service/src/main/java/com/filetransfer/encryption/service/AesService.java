package com.filetransfer.encryption.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encrypt / decrypt.
 * Output format: Base64( IV[12 bytes] || CipherText || AuthTag[16 bytes] )
 */
@Slf4j
@Service
public class AesService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    /** Encrypt plaintext with the given Base64-encoded AES-256 key. */
    public byte[] encrypt(byte[] plaintext, String base64Key) throws Exception {
        SecretKey key = decodeKey(base64Key);
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] cipherText = cipher.doFinal(plaintext);

        // Prepend IV
        byte[] result = new byte[IV_LENGTH + cipherText.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH);
        System.arraycopy(cipherText, 0, result, IV_LENGTH, cipherText.length);
        return result;
    }

    /** Decrypt ciphertext (IV-prepended) with the given Base64-encoded AES-256 key. */
    public byte[] decrypt(byte[] cipherTextWithIv, String base64Key) throws Exception {
        SecretKey key = decodeKey(base64Key);
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(cipherTextWithIv, 0, iv, 0, IV_LENGTH);

        byte[] cipherText = new byte[cipherTextWithIv.length - IV_LENGTH];
        System.arraycopy(cipherTextWithIv, IV_LENGTH, cipherText, 0, cipherText.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        return cipher.doFinal(cipherText);
    }

    /** Generate a new random AES-256 key, returned as Base64. */
    public String generateKey() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256, new SecureRandom());
        return Base64.getEncoder().encodeToString(gen.generateKey().getEncoded());
    }

    private SecretKey decodeKey(String base64Key) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }
}
