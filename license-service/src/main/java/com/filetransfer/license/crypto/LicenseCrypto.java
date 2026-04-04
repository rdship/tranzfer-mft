package com.filetransfer.license.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filetransfer.license.dto.LicensePayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
@Slf4j
public class LicenseCrypto {

    private static final String ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String DELIMITER = ".";

    private final ObjectMapper objectMapper;
    private KeyPair keyPair;

    public LicenseCrypto() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.keyPair = generateOrLoadKeyPair();
    }

    private KeyPair generateOrLoadKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public String sign(LicensePayload payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(keyPair.getPrivate());
            sig.update(encodedPayload.getBytes(StandardCharsets.UTF_8));
            byte[] signature = sig.sign();
            String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

            return encodedPayload + "." + encodedSig;
        } catch (Exception e) {
            throw new RuntimeException("License signing failed", e);
        }
    }

    public LicensePayload verify(String licenseKey) {
        try {
            String[] parts = licenseKey.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid license key format");
            }
            String encodedPayload = parts[0];
            String encodedSig = parts[1];

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(keyPair.getPublic());
            sig.update(encodedPayload.getBytes(StandardCharsets.UTF_8));
            byte[] sigBytes = Base64.getUrlDecoder().decode(encodedSig);

            if (!sig.verify(sigBytes)) {
                throw new SecurityException("License signature verification failed");
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
            return objectMapper.readValue(payloadBytes, LicensePayload.class);
        } catch (SecurityException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("License key is invalid or corrupted: " + e.getMessage());
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
