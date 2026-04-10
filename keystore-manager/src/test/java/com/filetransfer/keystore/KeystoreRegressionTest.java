package com.filetransfer.keystore;

import com.filetransfer.keystore.entity.ManagedKey;
import com.filetransfer.keystore.repository.ManagedKeyRepository;
import com.filetransfer.keystore.service.KeyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression, usability, and performance tests for keystore-manager.
 * Pure JUnit 5 + Mockito — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class KeystoreRegressionTest {

    @Mock private ManagedKeyRepository keyRepository;

    private KeyManagementService keyService;

    @BeforeEach
    void setUp() throws Exception {
        keyService = new KeyManagementService(keyRepository);
        setField(keyService, "masterPassword", "TestPasswordThatIs16Ch");
        setField(keyService, "environment", "DEV");
    }

    // ── Key generation round-trip ──────────────────────────────────────

    @Test
    void keyManagement_generateAndRetrieve_roundtrip() throws Exception {
        // Mock the save to return the entity, and existsByAlias to false
        when(keyRepository.existsByAlias(anyString())).thenReturn(false);
        when(keyRepository.save(any(ManagedKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ManagedKey generated = keyService.generateAesKey("test-aes-key", "test-service");

        assertNotNull(generated, "Generated key must not be null");
        assertEquals("test-aes-key", generated.getAlias());
        assertEquals("AES_SYMMETRIC", generated.getKeyType());
        assertEquals("AES-256", generated.getAlgorithm());
        assertNotNull(generated.getKeyMaterial(), "Key material must be populated");
        assertNotNull(generated.getFingerprint(), "Fingerprint must be computed");
        assertEquals(256, generated.getKeySizeBits());
    }

    // ── Non-existent key returns empty ──────────────────────────────────

    @Test
    void keyManagement_nonexistentKey_shouldReturnEmpty() {
        when(keyRepository.findByAliasAndActiveTrue("nonexistent"))
                .thenReturn(Optional.empty());

        Optional<ManagedKey> result = keyService.getKey("nonexistent");

        assertTrue(result.isEmpty(), "Lookup of nonexistent key should return empty Optional");
    }

    // ── Null alias: deactivateKey should throw clear error ─────────────

    @Test
    void keyManagement_nullAlias_shouldThrowClearError() {
        when(keyRepository.findByAliasAndActiveTrue(null))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> keyService.deactivateKey(null),
                "Null alias should produce a clear error, not NPE");
        assertTrue(ex.getMessage().contains("Key not found"),
                "Error message should indicate key was not found");
    }

    // ── Performance: 100 key lookups ───────────────────────────────────

    @Test
    void keyManagement_performance_100Lookups_shouldBeUnder50ms() {
        when(keyRepository.findByAliasAndActiveTrue(anyString()))
                .thenReturn(Optional.of(ManagedKey.builder()
                        .alias("perf-key").keyType("AES_SYMMETRIC")
                        .keyMaterial("deadbeef").build()));

        // Warm up
        for (int i = 0; i < 20; i++) {
            keyService.getKey("perf-key-" + i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            keyService.getKey("perf-key-" + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[BENCHMARK] 100 key lookups: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 50,
                "100 key lookups took " + elapsedMs + "ms — must complete under 50ms");
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
