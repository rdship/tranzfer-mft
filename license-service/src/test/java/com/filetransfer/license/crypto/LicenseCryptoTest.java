package com.filetransfer.license.crypto;

import com.filetransfer.license.dto.LicensePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the RSA sign/verify crypto for license keys.
 * No Spring context needed — LicenseCrypto generates its own keys.
 */
class LicenseCryptoTest {

    private LicenseCrypto crypto;

    @BeforeEach
    void setUp() {
        crypto = new LicenseCrypto();
    }

    @Test
    void signAndVerify_roundTrip_shouldPreservePayload() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-TEST123")
                .customerId("CUST-001")
                .customerName("Acme Corp")
                .edition("ENTERPRISE")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .services(List.of(
                        LicensePayload.ServiceLicense.builder()
                                .serviceType("SFTP")
                                .maxInstances(10)
                                .maxConcurrentConnections(500)
                                .features(List.of("HA", "CLUSTERING"))
                                .build()
                ))
                .build();

        String licenseKey = crypto.sign(payload);
        assertNotNull(licenseKey);
        assertTrue(licenseKey.contains("."), "License key should be payload.signature format");

        LicensePayload verified = crypto.verify(licenseKey);
        assertEquals("LIC-TEST123", verified.getLicenseId());
        assertEquals("CUST-001", verified.getCustomerId());
        assertEquals("Acme Corp", verified.getCustomerName());
        assertEquals("ENTERPRISE", verified.getEdition());
        assertEquals(1, verified.getServices().size());
        assertEquals("SFTP", verified.getServices().get(0).getServiceType());
        assertEquals(10, verified.getServices().get(0).getMaxInstances());
    }

    @Test
    void verify_tamperedPayload_shouldThrow() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-TAMPER")
                .customerId("CUST-002")
                .customerName("Evil Corp")
                .edition("STANDARD")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        String licenseKey = crypto.sign(payload);

        // Tamper with the payload (first part before the dot)
        String[] parts = licenseKey.split("\\.");
        String tamperedPayload = parts[0].substring(0, parts[0].length() - 3) + "XXX";
        String tamperedKey = tamperedPayload + "." + parts[1];

        assertThrows(Exception.class, () -> crypto.verify(tamperedKey));
    }

    @Test
    void verify_invalidFormat_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> crypto.verify("not-a-valid-key"));
    }

    @Test
    void verify_emptySignature_shouldThrow() {
        assertThrows(Exception.class, () -> crypto.verify("payload."));
    }

    @Test
    void verify_differentKeyPair_shouldFail() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-CROSS")
                .customerId("CUST-003")
                .customerName("Cross Corp")
                .edition("PROFESSIONAL")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(90, ChronoUnit.DAYS))
                .build();

        // Sign with one instance
        String licenseKey = crypto.sign(payload);

        // Verify with a different instance (different key pair)
        LicenseCrypto otherCrypto = new LicenseCrypto();
        assertThrows(Exception.class, () -> otherCrypto.verify(licenseKey));
    }

    @Test
    void sign_minimalPayload_shouldWork() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-MIN")
                .build();

        String licenseKey = crypto.sign(payload);
        assertNotNull(licenseKey);

        LicensePayload verified = crypto.verify(licenseKey);
        assertEquals("LIC-MIN", verified.getLicenseId());
    }

    @Test
    void getPublicKeyBase64_shouldReturnNonEmpty() {
        String pubKey = crypto.getPublicKeyBase64();
        assertNotNull(pubKey);
        assertFalse(pubKey.isBlank());
        // Should be valid Base64
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(pubKey));
    }

    @Test
    void sign_multipleServices_shouldPreserveAll() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-MULTI")
                .customerName("Multi Corp")
                .edition("ENTERPRISE")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .services(List.of(
                        LicensePayload.ServiceLicense.builder()
                                .serviceType("SFTP").maxInstances(5).maxConcurrentConnections(200)
                                .features(List.of("HA")).build(),
                        LicensePayload.ServiceLicense.builder()
                                .serviceType("FTP").maxInstances(3).maxConcurrentConnections(100)
                                .features(List.of("TLS")).build(),
                        LicensePayload.ServiceLicense.builder()
                                .serviceType("AI_ENGINE").maxInstances(2).maxConcurrentConnections(50)
                                .features(List.of("CLASSIFICATION", "ANOMALY")).build()
                ))
                .build();

        String licenseKey = crypto.sign(payload);
        LicensePayload verified = crypto.verify(licenseKey);

        assertEquals(3, verified.getServices().size());
        assertEquals("AI_ENGINE", verified.getServices().get(2).getServiceType());
        assertEquals(List.of("CLASSIFICATION", "ANOMALY"), verified.getServices().get(2).getFeatures());
    }
}
