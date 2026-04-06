package com.filetransfer.dmz.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackendHealthCheckerTest {

    private BackendHealthChecker checker;

    @BeforeEach
    void setUp() {
        checker = new BackendHealthChecker(10, 3, 3, 1);
    }

    @AfterEach
    void tearDown() {
        checker.shutdown();
    }

    // ── Test 1: constructor_invalidParams_throws ────────────────────────

    @Test
    void constructor_invalidParams_throws() {
        assertAll("All non-positive constructor params should throw IllegalArgumentException",
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(0, 3, 3, 1),
                "checkIntervalSeconds=0 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(-1, 3, 3, 1),
                "checkIntervalSeconds=-1 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(10, 0, 3, 1),
                "timeoutSeconds=0 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(10, -5, 3, 1),
                "timeoutSeconds=-5 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(10, 3, 0, 1),
                "unhealthyThreshold=0 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(10, 3, 3, 0),
                "healthyThreshold=0 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> new BackendHealthChecker(10, 3, 3, -2),
                "healthyThreshold=-2 should throw")
        );
    }

    // ── Test 2: registerBackend_success ──────────────────────────────────

    @Test
    void registerBackend_success() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);

        BackendHealthChecker.BackendHealth health = checker.getHealth("gateway-service");
        assertNotNull(health, "getHealth should return non-null for registered backend");
        assertEquals("gateway-service", health.name());
        assertEquals("gateway-service", health.host());
        assertEquals(2220, health.port());
        assertEquals(BackendHealthChecker.Status.UNKNOWN, health.status());
    }

    // ── Test 3: registerBackend_duplicateName_throws ────────────────────

    @Test
    void registerBackend_duplicateName_throws() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);

        assertThrows(IllegalArgumentException.class,
            () -> checker.registerBackend("gateway-service", "other-host", 9999),
            "Duplicate backend name should throw IllegalArgumentException");
    }

    // ── Test 4: registerBackend_nullHost_throws ─────────────────────────

    @Test
    void registerBackend_nullHost_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> checker.registerBackend("test-backend", null, 8080),
            "Null host should throw IllegalArgumentException");
    }

    // ── Test 5: registerBackend_invalidPort_throws ──────────────────────

    @Test
    void registerBackend_invalidPort_throws() {
        assertAll("Invalid ports should throw IllegalArgumentException",
            () -> assertThrows(IllegalArgumentException.class,
                () -> checker.registerBackend("test-zero", "localhost", 0),
                "Port 0 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> checker.registerBackend("test-over", "localhost", 65536),
                "Port 65536 should throw"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> checker.registerBackend("test-neg", "localhost", -1),
                "Port -1 should throw")
        );
    }

    // ── Test 6: removeBackend_removesEntry ──────────────────────────────

    @Test
    void removeBackend_removesEntry() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);
        assertNotNull(checker.getHealth("gateway-service"));

        checker.removeBackend("gateway-service");

        assertNull(checker.getHealth("gateway-service"),
            "getHealth should return null after backend is removed");
    }

    // ── Test 7: removeBackend_unknownName_isNoop ────────────────────────

    @Test
    void removeBackend_unknownName_isNoop() {
        assertDoesNotThrow(
            () -> checker.removeBackend("nonexistent-backend"),
            "Removing an unknown backend should not throw");
    }

    // ── Test 8: isHealthy_unknownBackend_returnsFalse ───────────────────

    @Test
    void isHealthy_unknownBackend_returnsFalse() {
        assertFalse(checker.isHealthy("nonexistent-backend"),
            "isHealthy should return false for unknown backend");
    }

    // ── Test 9: isHealthy_newBackend_returnsFalse ───────────────────────

    @Test
    void isHealthy_newBackend_returnsFalse() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);

        assertFalse(checker.isHealthy("gateway-service"),
            "isHealthy should return false for newly registered backend (status=UNKNOWN)");
    }

    // ── Test 10: getHealth_registeredBackend_hasCorrectFields ───────────

    @Test
    void getHealth_registeredBackend_hasCorrectFields() {
        checker.registerBackend("ftp-web-service", "ftp-web-service", 8083);

        BackendHealthChecker.BackendHealth health = checker.getHealth("ftp-web-service");

        assertAll("Health snapshot should have correct fields",
            () -> assertEquals("ftp-web-service", health.name()),
            () -> assertEquals("ftp-web-service", health.host()),
            () -> assertEquals(8083, health.port()),
            () -> assertEquals(BackendHealthChecker.Status.UNKNOWN, health.status()),
            () -> assertEquals(0, health.consecutiveFailures()),
            () -> assertEquals(0, health.consecutiveSuccesses()),
            () -> assertNull(health.lastCheck(), "lastCheck should be null before any probe"),
            () -> assertNotNull(health.lastStateChange(), "lastStateChange should be set on creation"),
            () -> assertEquals(0L, health.totalChecks()),
            () -> assertEquals(0L, health.totalFailures()),
            () -> assertNull(health.lastError(), "lastError should be null before any probe")
        );
    }

    // ── Test 11: getAllHealth_multipleBackends ───────────────────────────

    @Test
    void getAllHealth_multipleBackends() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);
        checker.registerBackend("ftp-web-service", "ftp-web-service", 8083);
        checker.registerBackend("screening-service", "screening-service", 8092);

        Map<String, BackendHealthChecker.BackendHealth> allHealth = checker.getAllHealth();

        assertEquals(3, allHealth.size(), "getAllHealth should return all 3 registered backends");
        assertTrue(allHealth.containsKey("gateway-service"));
        assertTrue(allHealth.containsKey("ftp-web-service"));
        assertTrue(allHealth.containsKey("screening-service"));
    }

    // ── Test 12: getUnhealthyBackends_initiallyEmpty ────────────────────

    @Test
    void getUnhealthyBackends_initiallyEmpty() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);
        checker.registerBackend("ftp-web-service", "ftp-web-service", 8083);

        List<String> unhealthy = checker.getUnhealthyBackends();

        assertTrue(unhealthy.isEmpty(),
            "getUnhealthyBackends should be empty when all backends are UNKNOWN (not UNHEALTHY)");
    }

    // ── Test 13: startAndShutdown_lifecycle ──────────────────────────────

    @Test
    void startAndShutdown_lifecycle() {
        checker.registerBackend("gateway-service", "gateway-service", 2220);

        assertDoesNotThrow(() -> checker.start(),
            "start() should not throw");
        assertDoesNotThrow(() -> checker.shutdown(),
            "shutdown() should not throw");
    }
}
