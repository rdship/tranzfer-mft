package com.filetransfer.forwarder.controller;

import com.filetransfer.forwarder.service.*;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import com.filetransfer.shared.repository.DeliveryEndpointRepository;
import com.filetransfer.shared.repository.ExternalDestinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwarderControllerRetryTest {

    private ForwarderController controller;
    private Method isRetryableMethod;

    @BeforeEach
    void setUp() throws Exception {
        ExternalDestinationRepository destinationRepository = mock(ExternalDestinationRepository.class);
        DeliveryEndpointRepository deliveryEndpointRepository = mock(DeliveryEndpointRepository.class);
        As2PartnershipRepository as2PartnershipRepository = mock(As2PartnershipRepository.class);
        SftpForwarderService sftpForwarder = mock(SftpForwarderService.class);
        FtpForwarderService ftpForwarder = mock(FtpForwarderService.class);
        FtpsForwarderService ftpsForwarder = mock(FtpsForwarderService.class);
        HttpForwarderService httpForwarder = mock(HttpForwarderService.class);
        KafkaForwarderService kafkaForwarder = mock(KafkaForwarderService.class);
        As2ForwarderService as2Forwarder = mock(As2ForwarderService.class);
        As4ForwarderService as4Forwarder = mock(As4ForwarderService.class);

        controller = new ForwarderController(
                destinationRepository,
                deliveryEndpointRepository,
                as2PartnershipRepository,
                sftpForwarder,
                ftpForwarder,
                ftpsForwarder,
                httpForwarder,
                kafkaForwarder,
                as2Forwarder,
                as4Forwarder
        );

        isRetryableMethod = ForwarderController.class.getDeclaredMethod(
                "isRetryableDeliveryError", Exception.class);
        isRetryableMethod.setAccessible(true);
    }

    private boolean invokeIsRetryable(Exception e) throws Exception {
        return (boolean) isRetryableMethod.invoke(controller, e);
    }

    // --- Non-retryable errors (should return false) ---

    @Test
    void isRetryable_permissionDenied_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("permission denied")));
    }

    @Test
    void isRetryable_authFailed_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("auth failed")));
    }

    @Test
    void isRetryable_http401_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("401 Unauthorized")));
    }

    @Test
    void isRetryable_http403_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("403 Forbidden")));
    }

    @Test
    void isRetryable_noSuchFile_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("no such file or directory")));
    }

    @Test
    void isRetryable_notFound_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("resource not found")));
    }

    @Test
    void isRetryable_http404_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("HTTP 404 response")));
    }

    @Test
    void isRetryable_keyExpired_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("key expired")));
    }

    @Test
    void isRetryable_certificateError_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("certificate error: self-signed")));
    }

    // --- Retryable errors (should return true) ---

    @Test
    void isRetryable_connectionTimeout_returnsTrue() throws Exception {
        assertTrue(invokeIsRetryable(new RuntimeException("connection timeout")));
    }

    @Test
    void isRetryable_networkError_returnsTrue() throws Exception {
        assertTrue(invokeIsRetryable(new RuntimeException("network error: host unreachable")));
    }

    @Test
    void isRetryable_genericError_returnsTrue() throws Exception {
        assertTrue(invokeIsRetryable(new RuntimeException("something unexpected happened")));
    }

    // --- Edge cases ---

    @Test
    void isRetryable_nullMessage_returnsTrue() throws Exception {
        // Exception with null message: getMessage() returns null, lowercased to "",
        // which does not match any non-retryable pattern, so returns true
        assertTrue(invokeIsRetryable(new RuntimeException((String) null)));
    }

    @Test
    void isRetryable_emptyMessage_returnsTrue() throws Exception {
        assertTrue(invokeIsRetryable(new RuntimeException("")));
    }

    @Test
    void isRetryable_caseInsensitiveAuthCheck_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("Authentication failed")));
    }

    @Test
    void isRetryable_caseInsensitivePermission_returnsFalse() throws Exception {
        assertFalse(invokeIsRetryable(new RuntimeException("Permission Denied by server")));
    }
}
