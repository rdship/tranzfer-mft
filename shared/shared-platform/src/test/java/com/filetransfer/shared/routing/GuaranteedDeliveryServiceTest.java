package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuaranteedDeliveryServiceTest {

    private FileTransferRecordRepository recordRepository;
    private AuditService auditService;
    private ConnectorDispatcher connectorDispatcher;
    private ObjectProvider<ConnectorDispatcher> connectorDispatcherProvider;
    private GuaranteedDeliveryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recordRepository = mock(FileTransferRecordRepository.class);
        auditService = mock(AuditService.class);
        connectorDispatcher = mock(ConnectorDispatcher.class);
        connectorDispatcherProvider = mock(ObjectProvider.class);
        // ifAvailable runs the consumer with the mock dispatcher — mirrors prod behavior.
        doAnswer(inv -> {
            java.util.function.Consumer<ConnectorDispatcher> c = inv.getArgument(0);
            c.accept(connectorDispatcher);
            return null;
        }).when(connectorDispatcherProvider).ifAvailable(any());
        service = new GuaranteedDeliveryService(recordRepository, auditService, connectorDispatcherProvider);
    }

    // --- classifyFailure tests ---

    @Test
    void classifyFailure_nullMessage_returnsRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.RETRY,
                service.classifyFailure(null, 0));
    }

    @Test
    void classifyFailure_authFailed_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("auth failed", 0));
    }

    @Test
    void classifyFailure_permissionDenied_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("permission denied", 0));
    }

    @Test
    void classifyFailure_accessDenied_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("access denied", 0));
    }

    @Test
    void classifyFailure_http401_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("HTTP 401 Unauthorized", 1));
    }

    @Test
    void classifyFailure_http403_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("HTTP 403 Forbidden", 2));
    }

    @Test
    void classifyFailure_keyExpired_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("key expired for partner X", 0));
    }

    @Test
    void classifyFailure_keyNotFound_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("key not found in keystore", 0));
    }

    @Test
    void classifyFailure_decryptFailed_returnsNoRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.NO_RETRY,
                service.classifyFailure("decrypt failed: bad padding", 0));
    }

    @Test
    void classifyFailure_schemaError_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("schema validation error", 0));
    }

    @Test
    void classifyFailure_formatError_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("format error in payload", 0));
    }

    @Test
    void classifyFailure_parseError_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("parse error at line 5", 0));
    }

    @Test
    void classifyFailure_malformed_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("malformed XML document", 0));
    }

    @Test
    void classifyFailure_sanctions_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("sanctions list match", 0));
    }

    @Test
    void classifyFailure_ofac_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("ofac screening failed", 0));
    }

    @Test
    void classifyFailure_blocked_returnsQuarantine() {
        assertEquals(GuaranteedDeliveryService.RetryAction.QUARANTINE,
                service.classifyFailure("transfer blocked by compliance", 0));
    }

    @Test
    void classifyFailure_connectionTimeout_returnsRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.RETRY,
                service.classifyFailure("connection timeout after 30s", 0));
    }

    @Test
    void classifyFailure_networkError_returnsRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.RETRY,
                service.classifyFailure("network error: host unreachable", 0));
    }

    @Test
    void classifyFailure_unknownError_returnsRetry() {
        assertEquals(GuaranteedDeliveryService.RetryAction.RETRY,
                service.classifyFailure("something unexpected happened", 3));
    }

    // --- classifyCategory tests ---

    @Test
    void classifyCategory_null_returnsUnknown() {
        assertEquals("UNKNOWN", service.classifyCategory(null));
    }

    @Test
    void classifyCategory_timeout_returnsNetworkTransient() {
        assertEquals("NETWORK_TRANSIENT", service.classifyCategory("connection timeout"));
    }

    @Test
    void classifyCategory_connectionError_returnsNetworkTransient() {
        assertEquals("NETWORK_TRANSIENT", service.classifyCategory("connection refused"));
    }

    @Test
    void classifyCategory_auth_returnsAuthFailure() {
        assertEquals("AUTH_FAILURE", service.classifyCategory("auth failed"));
    }

    @Test
    void classifyCategory_permission_returnsAuthFailure() {
        assertEquals("AUTH_FAILURE", service.classifyCategory("permission denied"));
    }

    @Test
    void classifyCategory_http401_returnsAuthFailure() {
        assertEquals("AUTH_FAILURE", service.classifyCategory("HTTP 401"));
    }

    @Test
    void classifyCategory_disk_returnsStorageFull() {
        assertEquals("STORAGE_FULL", service.classifyCategory("disk full"));
    }

    @Test
    void classifyCategory_space_returnsStorageFull() {
        assertEquals("STORAGE_FULL", service.classifyCategory("no space left on device"));
    }

    @Test
    void classifyCategory_quota_returnsStorageFull() {
        assertEquals("STORAGE_FULL", service.classifyCategory("quota exceeded"));
    }

    @Test
    void classifyCategory_checksum_returnsIntegrityFailure() {
        assertEquals("INTEGRITY_FAILURE", service.classifyCategory("checksum mismatch"));
    }

    @Test
    void classifyCategory_integrity_returnsIntegrityFailure() {
        assertEquals("INTEGRITY_FAILURE", service.classifyCategory("integrity verification failed"));
    }

    @Test
    void classifyCategory_keyExpired_returnsEncryptionKey() {
        assertEquals("ENCRYPTION_KEY", service.classifyCategory("key expired"));
    }

    @Test
    void classifyCategory_decrypt_returnsEncryptionKey() {
        assertEquals("ENCRYPTION_KEY", service.classifyCategory("decrypt failed"));
    }

    @Test
    void classifyCategory_schema_returnsFormatError() {
        assertEquals("FORMAT_ERROR", service.classifyCategory("schema validation error"));
    }

    @Test
    void classifyCategory_format_returnsFormatError() {
        assertEquals("FORMAT_ERROR", service.classifyCategory("invalid format"));
    }

    @Test
    void classifyCategory_parse_returnsFormatError() {
        assertEquals("FORMAT_ERROR", service.classifyCategory("XML parse failed"));
    }

    @Test
    void classifyCategory_unknownMessage_returnsUnknown() {
        assertEquals("UNKNOWN", service.classifyCategory("something went wrong"));
    }

    // --- computeBackoffDelay tests ---

    @Test
    void computeBackoffDelay_retryCountZero_returnsApproximately30s() {
        long delay = service.computeBackoffDelay(0);
        // BASE_DELAY=30, 2^0=1, so base=30. Jitter = +/- 25% => range [22.5, 37.5]
        assertTrue(delay >= 22, "delay should be >= 22 but was " + delay);
        assertTrue(delay <= 38, "delay should be <= 38 but was " + delay);
    }

    @Test
    void computeBackoffDelay_retryCount3_returnsApproximately240s() {
        long delay = service.computeBackoffDelay(3);
        // BASE_DELAY=30, 2^3=8, so base=240. Jitter range [180, 300]
        assertTrue(delay >= 180, "delay should be >= 180 but was " + delay);
        assertTrue(delay <= 300, "delay should be <= 300 but was " + delay);
    }

    @Test
    void computeBackoffDelay_retryCount10_cappedAtMax() {
        long delay = service.computeBackoffDelay(10);
        // 30 * 2^10 = 30720 > MAX(1800), so capped at 1800. Jitter range [1350, 2250]
        assertTrue(delay >= 1350, "delay should be >= 1350 but was " + delay);
        assertTrue(delay <= 2250, "delay should be <= 2250 but was " + delay);
    }

    @Test
    void computeBackoffDelay_alwaysReturnsAtLeast1() {
        // Even for edge cases, method guarantees >= 1
        long delay = service.computeBackoffDelay(0);
        assertTrue(delay >= 1, "delay must be at least 1 but was " + delay);
    }

    @Test
    void computeBackoffDelay_highRetryCount_cappedAtMax() {
        // retryCount=20 still capped due to Math.min(retryCount, 10) and MAX_DELAY
        long delay = service.computeBackoffDelay(20);
        assertTrue(delay >= 1350, "delay should be >= 1350 but was " + delay);
        assertTrue(delay <= 2250, "delay should be <= 2250 but was " + delay);
    }
}
