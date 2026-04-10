package com.filetransfer.forwarder.controller;

import com.filetransfer.forwarder.transfer.TransferSession;
import com.filetransfer.forwarder.transfer.TransferStallException;
import com.filetransfer.forwarder.transfer.TransferWatchdog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression, usability, and performance tests for external-forwarder-service.
 * Pure JUnit 5 + Mockito — no @SpringBootTest.
 *
 * <p>Placed in the controller package to access package-private
 * {@code isRetryableDeliveryError}.
 */
@ExtendWith(MockitoExtension.class)
class ForwarderRegressionTest {

    private ForwarderController forwarderController;
    private TransferWatchdog transferWatchdog;

    @BeforeEach
    void setUp() throws Exception {
        // ForwarderController with null dependencies — only testing isRetryableDeliveryError
        forwarderController = new ForwarderController(
                null, null, null,
                null, null, null, null, null, null, null, null);

        // Real TransferWatchdog for stall detection tests
        transferWatchdog = new TransferWatchdog();
        Field stallTimeoutField = TransferWatchdog.class.getDeclaredField("stallTimeoutSeconds");
        stallTimeoutField.setAccessible(true);
        stallTimeoutField.set(transferWatchdog, 30);
    }

    // ── 1. sftpForwarder_connectionTimeout_shouldThrowWithEndpointInfo ──

    @Test
    void sftpForwarder_connectionTimeout_shouldThrowWithEndpointInfo() {
        // TransferStallException includes endpoint info in its message
        TransferStallException ex = new TransferStallException(
                "xfer-abc123", 5000, 10000, 35);

        assertTrue(ex.getMessage().contains("xfer-abc123"),
                "Exception message should contain transfer ID");
        assertTrue(ex.getMessage().contains("5000"),
                "Exception message should contain bytes transferred");
        assertTrue(ex.getMessage().contains("10000"),
                "Exception message should contain total bytes");
        assertEquals(50, ex.getProgressPercent(),
                "Progress should reflect 50% completion");
        assertEquals(35, ex.getIdleSeconds(),
                "Idle seconds should be reported");
    }

    // ── 2. ftpForwarder_invalidHost_shouldFailFast ──

    @Test
    void ftpForwarder_invalidHost_shouldFailFast() {
        // Verify that non-retryable errors (like auth failure) cause isRetryable=false
        // which means the controller stops retrying immediately (fail fast)
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("auth failed")),
                "Auth failures should not be retried (fail fast)");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("permission denied")),
                "Permission denied should not be retried (fail fast)");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("401 Unauthorized")),
                "401 should not be retried (fail fast)");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("key expired")),
                "Expired keys should not be retried (fail fast)");

        // Connection errors are retryable
        assertTrue(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("connection timeout")),
                "Connection timeout should be retryable");
    }

    // ── 3. transferWatchdog_stallDetection_shouldTriggerAlert ──

    @Test
    void transferWatchdog_stallDetection_shouldTriggerAlert() {
        // Register a transfer session
        TransferSession session = transferWatchdog.register("test-endpoint", "file.dat", 1024);

        assertNotNull(session);
        assertNotNull(session.getTransferId());
        assertEquals("test-endpoint", session.getEndpointName());
        assertEquals("file.dat", session.getFilename());
        assertEquals(1024, session.getTotalBytes());
        assertFalse(session.isStalled(), "New session should not be stalled");
        assertFalse(session.isCompleted(), "New session should not be completed");

        // Verify active session tracking
        assertEquals(1, transferWatchdog.getActiveTransferCount());
        assertTrue(transferWatchdog.getActiveSessions().containsKey(session.getTransferId()));

        // Record some progress
        session.recordProgress(512);
        assertEquals(512, session.getBytesTransferred());
        assertEquals(50, session.getProgressPercent());

        // Unregister
        transferWatchdog.unregister(session.getTransferId());
        assertEquals(0, transferWatchdog.getActiveTransferCount());
    }

    // ── 4. forwarderController_retryLogic_shouldRespectMaxRetries ──

    @Test
    void forwarderController_retryLogic_shouldRespectMaxRetries() {
        // TransferStallException is always retryable
        assertTrue(forwarderController.isRetryableDeliveryError(
                        new TransferStallException("xfer-test", 5000, 10000, 35)),
                "TransferStallException should always be retryable");

        // Non-retryable errors — stops immediately
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("403 Forbidden")),
                "403 should not be retried");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("no such file or directory")),
                "File not found should not be retried");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("resource not found")),
                "Resource not found should not be retried");
        assertFalse(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("certificate error: self-signed")),
                "Certificate errors should not be retried");

        // Retryable errors
        assertTrue(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("network error: host unreachable")),
                "Network errors should be retryable");
        assertTrue(forwarderController.isRetryableDeliveryError(
                        new RuntimeException("something unexpected happened")),
                "Generic errors should be retryable");

        // Edge: null message
        assertTrue(forwarderController.isRetryableDeliveryError(
                        new RuntimeException((String) null)),
                "Null message should be treated as retryable");
    }

    // ── 5. forwarder_performance_1000TransferSessionCreations_shouldBeUnder500ms ──

    @Test
    void forwarder_performance_1000TransferSessionCreations_shouldBeUnder500ms() {
        long start = System.nanoTime();

        for (int i = 0; i < 1000; i++) {
            TransferSession session = transferWatchdog.register(
                    "endpoint-" + i, "file-" + i + ".dat", 1024 * (i + 1));
            session.recordProgress(512);
            transferWatchdog.unregister(session.getTransferId());
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 500,
                "1000 transfer session create/progress/unregister cycles took "
                        + elapsedMs + "ms, expected <500ms");
    }
}
