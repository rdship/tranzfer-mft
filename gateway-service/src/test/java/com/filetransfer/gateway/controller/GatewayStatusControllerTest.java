package com.filetransfer.gateway.controller;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.entity.core.ServerInstance;
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

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayStatusController.
 * Auth is now delegated to Spring Security (@PreAuthorize("hasRole('INTERNAL')"))
 * and is not exercised at this unit-test level.
 */
@ExtendWith(MockitoExtension.class)
class GatewayStatusControllerTest {

    // Real SshServer (not started) — SshServer can't be mocked on JDK 25
    private final SshServer sftpGatewayServer = SshServer.setUpDefaultServer();
    @Mock private LegacyServerConfigRepository legacyRepo;
    @Mock private ServerInstanceRepository serverInstanceRepo;
    @Mock private TransferAccountRepository accountRepo;

    private GatewayStatusController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new GatewayStatusController(sftpGatewayServer, legacyRepo, serverInstanceRepo, accountRepo);
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

    // ── Status endpoint tests ───────────────────────────────────────────

    @Test
    void status_returnsExpectedFields_whenGatewayRunning() {
        Map<String, Object> result = controller.status();

        assertEquals(2220, result.get("sftpGatewayPort"));
        assertEquals(2121, result.get("ftpGatewayPort"));
        assertEquals(false, result.get("sftpGatewayRunning"));
    }

    @Test
    void status_returnsExpectedFields_whenGatewayStopped() {
        Map<String, Object> result = controller.status();

        assertEquals(false, result.get("sftpGatewayRunning"));
        assertEquals(2220, result.get("sftpGatewayPort"));
        assertEquals(2121, result.get("ftpGatewayPort"));
    }

    // ── Routes endpoint tests ───────────────────────────────────────────

    @Test
    void routes_returnsDefaultRoutes_whenNoInstancesOrLegacy() {
        when(serverInstanceRepo.findByActiveTrue()).thenReturn(Collections.emptyList());
        when(legacyRepo.findAll()).thenReturn(Collections.emptyList());

        Map<String, Object> result = controller.routes();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> defaultRoutes = (List<Map<String, Object>>) result.get("defaultRoutes");
        assertNotNull(defaultRoutes);
        assertEquals(3, defaultRoutes.size());

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

        Map<String, Object> result = controller.routes();

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

        Map<String, Object> result = controller.routes();

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

        Map<String, Object> result = controller.routes();

        // 3 default + 1 instance + 1 legacy = 5
        assertEquals(5, result.get("totalRoutes"));
    }

    // ── Stats endpoint tests ────────────────────────────────────────────

    @Test
    void stats_returnsCorrectCounts() {
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

        Map<String, Object> result = controller.stats();

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

        List<LegacyServerConfig> result = controller.legacyServers(null);

        assertEquals(1, result.size());
        verify(legacyRepo).findAll();
        verify(legacyRepo, never()).findByProtocolAndActiveTrue(any());
    }

    @Test
    void legacyServers_withProtocolFilter_delegatesToFilteredQuery() {
        when(legacyRepo.findByProtocolAndActiveTrue(Protocol.SFTP)).thenReturn(Collections.emptyList());

        List<LegacyServerConfig> result = controller.legacyServers(Protocol.SFTP);

        assertEquals(0, result.size());
        verify(legacyRepo).findByProtocolAndActiveTrue(Protocol.SFTP);
        verify(legacyRepo, never()).findAll();
    }
}
