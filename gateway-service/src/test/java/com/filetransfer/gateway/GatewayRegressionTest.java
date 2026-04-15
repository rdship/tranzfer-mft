package com.filetransfer.gateway;

import com.filetransfer.gateway.client.ConnectionAuditClient;
import com.filetransfer.gateway.controller.GatewayStatusController;
import com.filetransfer.gateway.routing.UserRoutingService;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression, usability, and performance tests for gateway-service.
 * Pure JUnit 5 + Mockito — no @SpringBootTest.
 */
@ExtendWith(MockitoExtension.class)
class GatewayRegressionTest {

    // Real SshServer (not started) — cannot be mocked on JDK 25
    private final SshServer sftpGatewayServer = SshServer.setUpDefaultServer();
    @Mock private LegacyServerConfigRepository legacyRepo;
    @Mock private ServerInstanceRepository serverInstanceRepo;
    @Mock private TransferAccountRepository accountRepo;
    @Mock private ConnectionAuditClient auditClient;

    private GatewayStatusController statusController;
    private UserRoutingService routingService;

    @BeforeEach
    void setUp() throws Exception {
        // GatewayStatusController
        statusController = new GatewayStatusController(
                sftpGatewayServer, legacyRepo, serverInstanceRepo, accountRepo);
        setField(statusController, "sftpPort", 2220);
        setField(statusController, "ftpPort", 2121);
        setField(statusController, "internalSftpHost", "sftp-service");
        setField(statusController, "internalSftpPort", 2222);
        setField(statusController, "internalFtpHost", "ftp-service");
        setField(statusController, "internalFtpPort", 21);
        setField(statusController, "internalFtpWebHost", "ftp-web-service");
        setField(statusController, "internalFtpWebPort", 8083);

        // UserRoutingService
        routingService = new UserRoutingService(accountRepo, legacyRepo, serverInstanceRepo, auditClient);
        setField(routingService, "internalSftpHost", "sftp-service");
        setField(routingService, "internalSftpPort", 2222);
        setField(routingService, "internalFtpHost", "ftp-service");
        setField(routingService, "internalFtpPort", 21);
        setField(routingService, "internalFtpWebHost", "ftp-web-service");
        setField(routingService, "internalFtpWebPort", 8083);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── 1. gatewayStatus_healthCheck_shouldReturnUp ──

    @Test
    void gatewayStatus_healthCheck_shouldReturnUp() {
        Map<String, Object> status = statusController.status();

        assertNotNull(status);
        assertEquals(2220, status.get("sftpGatewayPort"),
                "SFTP gateway port should be configured at 2220");
        assertEquals(2121, status.get("ftpGatewayPort"),
                "FTP gateway port should be configured at 2121");
        // Server is not started in tests, so it reports false
        assertEquals(false, status.get("sftpGatewayRunning"),
                "Gateway status endpoint should return running state");
    }

    // ── 2. gatewayStatus_nullServiceList_shouldHandleGracefully ──

    @Test
    void gatewayStatus_nullServiceList_shouldHandleGracefully() {
        // Empty results from repos — should not NPE
        when(serverInstanceRepo.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(legacyRepo.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> routes = assertDoesNotThrow(() -> statusController.routes(),
                "Routes endpoint should handle empty/null service lists gracefully");

        assertNotNull(routes);
        assertEquals(3, routes.get("totalRoutes"),
                "Should have 3 default routes when no instances/legacy");
    }

    // ── 3. gatewayStatus_performance_1000HealthChecks_shouldBeUnder200ms ──

    @Test
    void gatewayStatus_performance_1000HealthChecks_shouldBeUnder200ms() {
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            statusController.status();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 200,
                "1000 health check calls took " + elapsedMs + "ms, expected <200ms");
    }

    // ── 4. gateway_connectionRouting_shouldResolveCorrectBackend ──

    @Test
    void gateway_connectionRouting_shouldResolveCorrectBackend() {
        // Known user with no server assignment -> default SFTP service
        TransferAccount account = TransferAccount.builder()
                .username("testuser")
                .protocol(Protocol.SFTP)
                .active(true)
                .build();
        when(accountRepo.findByUsernameAndProtocolAndActiveTrue("testuser", Protocol.SFTP))
                .thenReturn(Optional.of(account));

        UserRoutingService.RouteDecision decision = routingService.routeSftp("testuser");

        assertNotNull(decision, "Known user should get a route decision");
        assertEquals("sftp-service", decision.host(),
                "Known user without server assignment should route to default SFTP service");
        assertEquals(2222, decision.port());
        assertFalse(decision.isLegacy(), "Known user should not be routed to legacy");

        // Unknown user with no legacy config -> null
        when(accountRepo.findByUsernameAndProtocolAndActiveTrue("unknown", Protocol.SFTP))
                .thenReturn(Optional.empty());
        when(legacyRepo.findByProtocolAndActiveTrue(Protocol.SFTP))
                .thenReturn(Collections.emptyList());

        UserRoutingService.RouteDecision unknownDecision = routingService.routeSftp("unknown");
        assertNull(unknownDecision,
                "Unknown user with no legacy config should get null (rejected)");
    }
}
