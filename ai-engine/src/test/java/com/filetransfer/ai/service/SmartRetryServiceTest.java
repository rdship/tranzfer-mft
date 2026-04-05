package com.filetransfer.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SmartRetryService failure classification and retry decisions.
 * No Spring context needed — pure logic.
 */
class SmartRetryServiceTest {

    private SmartRetryService service;

    @BeforeEach
    void setUp() {
        service = new SmartRetryService();
    }

    // ---- Network transient errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Connection timeout after 30s", "connection reset by peer", "Connection refused", "Service temporarily unavailable"})
    void classify_networkErrors_shouldRetry(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("RETRY", decision.getAction());
        assertEquals("NETWORK_TRANSIENT", decision.getFailureCategory());
        assertTrue(decision.getDelaySeconds() > 0);
    }

    @Test
    void classify_networkError_shouldUseBackoff() {
        SmartRetryService.RetryDecision retry0 = service.classify("timeout", "file.csv", 0);
        SmartRetryService.RetryDecision retry1 = service.classify("timeout", "file.csv", 1);
        SmartRetryService.RetryDecision retry2 = service.classify("timeout", "file.csv", 2);

        assertTrue(retry1.getDelaySeconds() > retry0.getDelaySeconds());
        assertTrue(retry2.getDelaySeconds() > retry1.getDelaySeconds());
    }

    // ---- Auth errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Authentication failed", "Permission denied", "Access denied for user", "HTTP 401", "HTTP 403 Forbidden"})
    void classify_authErrors_shouldNotRetry(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("ALERT_NO_RETRY", decision.getAction());
        assertEquals("AUTH_FAILURE", decision.getFailureCategory());
        assertEquals(0, decision.getDelaySeconds());
    }

    // ---- Storage errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Disk full", "No space left on device", "Quota exceeded"})
    void classify_storageErrors_shouldRetryDelayed(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("RETRY_DELAYED", decision.getAction());
        assertEquals("STORAGE_FULL", decision.getFailureCategory());
        assertEquals(600, decision.getDelaySeconds());
    }

    // ---- Integrity errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Checksum mismatch", "File integrity check failed", "Data corrupt"})
    void classify_integrityErrors_shouldReRequest(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("RE_REQUEST", decision.getAction());
        assertEquals("INTEGRITY_FAILURE", decision.getFailureCategory());
    }

    // ---- Encryption key errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Key expired", "Key not found", "Decrypt failed", "PGP decryption error"})
    void classify_encryptionErrors_shouldAlert(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("ALERT_NO_RETRY", decision.getAction());
        assertEquals("ENCRYPTION_KEY", decision.getFailureCategory());
    }

    // ---- Format errors ----

    @ParameterizedTest
    @ValueSource(strings = {"Schema validation failed", "Missing column 'name'", "Invalid format", "CSV parse error"})
    void classify_formatErrors_shouldQuarantine(String error) {
        SmartRetryService.RetryDecision decision = service.classify(error, "data.csv", 0);

        assertEquals("QUARANTINE", decision.getAction());
        assertEquals("FORMAT_ERROR", decision.getFailureCategory());
    }

    // ---- Unknown errors ----

    @Test
    void classify_unknownError_shouldRetryWithBackoff() {
        SmartRetryService.RetryDecision decision = service.classify("Something weird happened", "data.csv", 0);

        assertEquals("RETRY", decision.getAction());
        assertEquals("UNKNOWN", decision.getFailureCategory());
        assertEquals(60, decision.getDelaySeconds()); // 60 * (0+1)
    }

    @Test
    void classify_nullError_shouldReturnUnknown() {
        SmartRetryService.RetryDecision decision = service.classify(null, "data.csv", 0);

        assertEquals("RETRY", decision.getAction());
        assertEquals("UNKNOWN", decision.getFailureCategory());
    }

    @Test
    void classify_shouldBeCaseInsensitive() {
        SmartRetryService.RetryDecision decision = service.classify("CONNECTION TIMEOUT", "data.csv", 0);
        assertEquals("NETWORK_TRANSIENT", decision.getFailureCategory());

        decision = service.classify("PERMISSION DENIED", "data.csv", 0);
        assertEquals("AUTH_FAILURE", decision.getFailureCategory());
    }
}
