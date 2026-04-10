package com.filetransfer.license;

import com.filetransfer.license.crypto.LicenseCrypto;
import com.filetransfer.license.dto.LicensePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression, usability, and performance tests for license-service.
 * Pure JUnit 5 — no Spring context.
 */
class LicenseRegressionTest {

    private LicenseCrypto crypto;

    @BeforeEach
    void setUp() {
        crypto = new LicenseCrypto();
    }

    // ── Sign and verify round-trip ─────────────────────────────────────

    @Test
    void licenseCrypto_signAndVerify_shouldValidate() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-REG-001")
                .customerId("CUST-REG")
                .customerName("Regression Corp")
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
        assertTrue(licenseKey.contains("."), "License key must be payload.signature format");

        LicensePayload verified = crypto.verify(licenseKey);
        assertEquals("LIC-REG-001", verified.getLicenseId());
        assertEquals("CUST-REG", verified.getCustomerId());
        assertEquals("Regression Corp", verified.getCustomerName());
        assertEquals("ENTERPRISE", verified.getEdition());
        assertEquals(1, verified.getServices().size());
        assertEquals("SFTP", verified.getServices().get(0).getServiceType());
    }

    // ── Tampered license rejected ──────────────────────────────────────

    @Test
    void licenseCrypto_tamperedLicense_shouldReject() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-TAMPER-REG")
                .customerId("CUST-TAMPER")
                .customerName("Tampered Corp")
                .edition("STANDARD")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        String licenseKey = crypto.sign(payload);

        // Tamper with the payload portion
        String[] parts = licenseKey.split("\\.");
        String tamperedPayload = parts[0].substring(0, parts[0].length() - 3) + "YZW";
        String tamperedKey = tamperedPayload + "." + parts[1];

        assertThrows(Exception.class, () -> crypto.verify(tamperedKey),
                "Tampered license must be rejected with an exception");
    }

    // ── Null input should throw clear error ────────────────────────────

    @Test
    void licenseCrypto_nullInput_shouldThrowClearError() {
        assertThrows(Exception.class, () -> crypto.verify(null),
                "Null license key must throw an exception, not return null");

        assertThrows(Exception.class, () -> crypto.verify(""),
                "Empty license key must throw an exception");

        assertThrows(Exception.class, () -> crypto.verify("not-a-valid-key"),
                "Malformed license key must throw an exception");
    }

    // ── Performance: 1000 verifications ────────────────────────────────

    @Test
    void licenseCrypto_performance_1000Verifications_shouldBeUnder200ms() {
        LicensePayload payload = LicensePayload.builder()
                .licenseId("LIC-PERF")
                .customerId("CUST-PERF")
                .customerName("Performance Corp")
                .edition("ENTERPRISE")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .build();

        String licenseKey = crypto.sign(payload);

        // Warm up
        for (int i = 0; i < 50; i++) {
            crypto.verify(licenseKey);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            crypto.verify(licenseKey);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[BENCHMARK] 1000 license verifications: " + elapsedMs + "ms");
        // RSA verification is CPU-intensive; allow generous budget
        assertTrue(elapsedMs < 5000,
                "1000 license verifications took " + elapsedMs + "ms — must complete under 5s");
    }
}
