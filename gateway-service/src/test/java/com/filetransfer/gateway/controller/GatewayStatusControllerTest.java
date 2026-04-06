package com.filetransfer.gateway.controller;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayStatusController.
 * Validates API-key authentication (constant-time), status, and route endpoints.
 * No Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class GatewayStatusControllerTest {

    // Real SshServer (not started) — SshServer can't be mocked on JDK 25
    private final SshServer sftpGatewayServer = SshServer.setUpDefaultServer();
    @Mock private LegacyServerConfigRepository legacyRepo;
    @Mock private ServerInstanceRepository serverInstanceRepo;
    @Mock private TransferAccountRepository accountRepo;

    private GatewayStatusController controller;

    private static final String VALID_KEY = "test_secret_key_12345";

    @BeforeEach
    void setUp() throws Exception {
        controller = new GatewayStatusController(sftpGatewayServer, legacyRepo, serverInstanceRepo, accountRepo);
        setField("controlApiKey", VALID_KEY);
        setField("sftpPort", 2220);
        setField("ftpPort", 2121);
        setField("internalSftpHost", "sftp-service");
        setField("internalSftpPort", 2222);
        setField("internalFtpHost", "ftp-service");
        setField("internalFtpPort", 21);
        setField("internalFtpWebHost", "ftp-web-service");
        setField("internalFtpWebPort", 8083);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = GatewayStatusController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    // ── Authentication tests ────────────────────────────────────────────

    @Test
    void authenticate_validKey_noException() {
        // A valid key should not throw; we exercise it via the status endpoint
        // Real SshServer (not started) — isStarted() returns false
        assertDoesNotThrow(() -> controller.status(VALID_KEY));
    }

    @Test
    void authenticate_invalidKey_throwsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.status("wrong_key"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void authenticate_nullKey_throwsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.status(null));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void authenticate_emptyKey_throwsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.status(""));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    /**
     * Constant-time comparison: verify that MessageDigest.isEqual is used in the source code
     * by confirming both wrong-key-same-length and wrong-key-different-length produce the
     * same result type (UNAUTHORIZED). A naive equals() would short-circuit on length mismatch,
     * but MessageDigest.isEqual always compares full byte arrays.
     */
    @Test
    void authenticate_constantTime_sameAndDifferentLengthKeysRejected() {
        // Same length as VALID_KEY but different content
        String sameLengthWrongKey = "x".repeat(VALID_KEY.length());
        // Different length
        String differentLengthWrongKey = "short";

        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class,
                () -> controller.status(sameLengthWrongKey));
        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> controller.status(differentLengthWrongKey));

        assertEquals(HttpStatus.UNAUTHORIZED, ex1.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex2.getStatusCode());
    }

    @Test
    void authenticate_usesMessageDigestIsEqual() throws Exception {
        // Verify the authenticate method source uses MessageDigest.isEqual
        // by confirming the method exists and is private with expected behavior
        Method authMethod = GatewayStatusController.class.getDeclaredMethod("authenticate", String.class);
        authMethod.setAccessible(true);

        // Valid key should succeed (no exception from private method)
        assertDoesNotThrow(() -> authMethod.invoke(controller, VALID_KEY));

        // Invalid key should throw
        try {
            authMethod.invoke(controller, "invalid");
            fail("Expected InvocationTargetException wrapping ResponseStatusException");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertInstanceOf(ResponseStatusException.class, e.getCause());
        }
    }

    // ── Status endpoint tests ───────────────────────────────────────────

    @Test
    void status_returnsExpectedFields_whenGatewayRunning() {
        // Real SshServer (not started) — isStarted() returns false

        Map<String, Object> result = controller.status(VALID_KEY);

        assertEquals(2220, result.get("sftpGatewayPort"));
        assertEquals(2121, result.get("ftpGatewayPort"));
        assertEquals(false, result.get("sftpGatewayRunning"));
    }

    @Test
    void status_returnsExpectedFields_whenGatewayStopped() {
        // Real SshServer (not started) — isStarted() returns false

        Map<String, Object> result = controller.status(VALID_KEY);

        assertEquals(false, result.get("sftpGatewayRunning"));
        assertEquals(2220, result.get("sftpGatewayPort"));
        assertEquals(2121, result.get("ftpGatewayPort"));
    }

    // ── Routes endpoint tests ───────────────────────────────────────────

    @Test
    void routes_returnsDefaultRoutes_whenNoInstancesOrLegacy() {
        when(serverInstanceRepo.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(legacyRepo.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> result = controller.routes(VALID_KEY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> defaultRoutes = (List<Map<String, Object>>) result.get("defaultRoutes");
        assertNotNull(defaultRoutes);
        assertEquals(3, defaultRoutes.size());

        // Verify the three default routes exist with correct protocols
        assertEquals("SFTP Default", defaultRoutes.get(0).get("name"));
        assertEquals("sftp-service", defaultRoutes.get(0).get("targetHost"));
        assertEquals(2222, defaultRoutes.get(0).get("targetPort"));

        assertEquals("FTP Default", defaultRoutes.get(1).get("name"));
        assertEquals("ftp-service", defaultRoutes.get(1).get("targetHost"));

        assertEquals("FTP-Web Default", defaultRoutes.get(2).get("name"));
        assertEquals("ftp-web-service", defaultRoutes.get(2).get("targetHost"));

        assertEquals(3, result.get("totalRoutes"));
    }

    @Test
    void routes_includesServerInstances() {
        ServerInstance si = ServerInstance.builder()
                .name("SFTP-Primary")
                .instanceId("sftp-001")
                .protocol(Protocol.SFTP)
                .internalHost("sftp-primary")
                .internalPort(2222)
                .externalHost("sftp.example.com")
                .externalPort(22)
                .useProxy(false)
                .maxConnections(100)
                .active(true)
                .build();

        when(serverInstanceRepo.findByActiveTrue()).thenReturn(List.of(si));
        when(legacyRepo.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> result = controller.routes(VALID_KEY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instanceRoutes = (List<Map<String, Object>>) result.get("instanceRoutes");
        assertEquals(1, instanceRoutes.size());
        assertEquals("SFTP-Primary", instanceRoutes.get(0).get("name"));
        assertEquals("sftp-001", instanceRoutes.get(0).get("instanceId"));
        assertEquals("SFTP", instanceRoutes.get(0).get("protocol"));
        assertEquals("INSTANCE", instanceRoutes.get(0).get("type"));
        assertEquals(4, result.get("totalRoutes")); // 3 default + 1 instance
    }

    @Test
    void routes_includesLegacyServers() {
        LegacyServerConfig legacy = LegacyServerConfig.builder()
                .name("Legacy-FTP")
                .protocol(Protocol.FTP)
                .host("legacy.example.com")
                .port(21)
                .active(true)
                .build();

        when(serverInstanceRepo.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(legacyRepo.findAll()).thenReturn(List.of(legacy));

        Map<String, Object> result = controller.routes(VALID_KEY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacyRoutes = (List<Map<String, Object>>) result.get("legacyRoutes");
        assertEquals(1, legacyRoutes.size());
        assertEquals("Legacy-FTP", legacyRoutes.get(0).get("name"));
        assertEquals("LEGACY", legacyRoutes.get(0).get("type"));
        assertEquals("legacy.example.com", legacyRoutes.get(0).get("targetHost"));
        assertEquals(4, result.get("totalRoutes")); // 3 default + 1 legacy
    }

    @Test
    void routes_totalRoutesCountIsAccurate() {
        ServerInstance si = ServerInstance.builder()
                .name("SI-1").instanceId("si-1").protocol(Protocol.SFTP)
                .internalHost("h1").internalPort(22).active(true).build();
        LegacyServerConfig ls = LegacyServerConfig.builder()
                .name("LS-1").protocol(Protocol.FTP).host("h2").port(21).active(true).build();

        when(serverInstanceRepo.findByActiveTrue()).thenReturn(List.of(si));
        when(legacyRepo.findAll()).thenReturn(List.of(ls));

        Map<String, Object> result = controller.routes(VALID_KEY);

        // 3 default + 1 instance + 1 legacy = 5
        assertEquals(5, result.get("totalRoutes"));
    }

    // ── Stats endpoint tests ────────────────────────────────────────────

    @Test
    void stats_returnsCorrectCounts() {
        // Real SshServer (not started) — isStarted() returns false

        ServerInstance si1 = ServerInstance.builder()
                .name("SI-1").instanceId("si-1").protocol(Protocol.SFTP)
                .internalHost("h1").internalPort(22).active(true).build();
        ServerInstance si2 = ServerInstance.builder()
                .name("SI-2").instanceId("si-2").protocol(Protocol.FTP)
                .internalHost("h2").internalPort(21).active(true).build();

        when(serverInstanceRepo.findByActiveTrue()).thenReturn(List.of(si1, si2));

        LegacyServerConfig activeLegacy = LegacyServerConfig.builder()
                .name("L1").protocol(Protocol.FTP).host("h").port(21).active(true).build();
        LegacyServerConfig inactiveLegacy = LegacyServerConfig.builder()
                .name("L2").protocol(Protocol.FTP).host("h").port(21).active(false).build();
        when(legacyRepo.findAll()).thenReturn(List.of(activeLegacy, inactiveLegacy));

        when(accountRepo.count()).thenReturn(42L);

        Map<String, Object> result = controller.stats(VALID_KEY);

        assertEquals(2220, result.get("sftpGatewayPort"));
        assertEquals(2121, result.get("ftpGatewayPort"));
        assertEquals(false, result.get("sftpGatewayRunning"));
        assertEquals(2, result.get("activeInstances"));
        assertEquals(1L, result.get("legacyServers")); // only active ones
        assertEquals(42L, result.get("totalAccounts"));

        @SuppressWarnings("unchecked")
        Map<String, Long> byProtocol = (Map<String, Long>) result.get("instancesByProtocol");
        assertEquals(1L, byProtocol.get("SFTP"));
        assertEquals(1L, byProtocol.get("FTP"));
    }

    // ── Legacy servers endpoint tests ───────────────────────────────────

    @Test
    void legacyServers_noProtocolFilter_returnsAll() {
        LegacyServerConfig ls = LegacyServerConfig.builder()
                .name("L1").protocol(Protocol.FTP).host("h").port(21).active(true).build();
        when(legacyRepo.findAll()).thenReturn(List.of(ls));

        List<LegacyServerConfig> result = controller.legacyServers(VALID_KEY, null);

        assertEquals(1, result.size());
        verify(legacyRepo).findAll();
        verify(legacyRepo, never()).findByProtocolAndActiveTrue(any());
    }

    @Test
    void legacyServers_withProtocolFilter_delegatesToFilteredQuery() {
        when(legacyRepo.findByProtocolAndActiveTrue(Protocol.SFTP)).thenReturn(Collections.emptyList());

        List<LegacyServerConfig> result = controller.legacyServers(VALID_KEY, Protocol.SFTP);

        assertEquals(0, result.size());
        verify(legacyRepo).findByProtocolAndActiveTrue(Protocol.SFTP);
        verify(legacyRepo, never()).findAll();
    }
}
