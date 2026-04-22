package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.shared.client.DmzProxyClient;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.FolderTemplateRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * R87: FTP per-listener advanced config.
 *
 * <p>Covers:
 * <ul>
 *   <li>FTP fields round-trip through create → entity → response.</li>
 *   <li>Validation rejects malformed passive-port ranges and bad PROT values.</li>
 *   <li>Protocol-aware port suggestions suppress well-known ports of OTHER
 *       protocols (FTP must not get 22/2222; SFTP must not get 21/990).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ServerInstanceServiceFtpTest {

    @Mock private ServerInstanceRepository repository;
    @Mock private FolderTemplateRepository folderTemplateRepository;
    @Mock private DmzProxyClient dmzProxyClient;

    private ServerInstanceService service;

    @BeforeEach
    void setUp() {
        // R134X Sprint 7 Phase B — legacy OutboxWriter removed; UnifiedOutboxWriter
        // is @Autowired(required=false) so tests that don't need event publishing
        // work without mocking it (the null-check in publishChange handles it).
        service = new ServerInstanceService(repository, folderTemplateRepository, dmzProxyClient);
    }

    @Test
    void createRoundtripsFtpFields() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(repository.save(any(ServerInstance.class)))
                .thenAnswer(inv -> {
                    ServerInstance saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftp-test-1");
        req.setProtocol(Protocol.FTP);
        req.setName("FTP Test");
        req.setInternalHost("ftp-service");
        req.setInternalPort(21);
        req.setFtpPassivePortFrom(21000);
        req.setFtpPassivePortTo(21010);
        req.setFtpTlsCertAlias("ftps-eu-west");
        req.setFtpProtRequired("p"); // lower-case → must be normalized
        req.setFtpBannerMessage("220 Welcome");
        req.setFtpImplicitTls(true);

        ServerInstanceResponse resp = service.create(req);

        assertThat(resp.getFtpPassivePortFrom()).isEqualTo(21000);
        assertThat(resp.getFtpPassivePortTo()).isEqualTo(21010);
        assertThat(resp.getFtpTlsCertAlias()).isEqualTo("ftps-eu-west");
        assertThat(resp.getFtpProtRequired()).isEqualTo("P"); // normalized uppercase
        assertThat(resp.getFtpBannerMessage()).isEqualTo("220 Welcome");
        assertThat(resp.getFtpImplicitTls()).isTrue();
    }

    @Test
    void rejectsPassiveRangeWithOnlyOneBound() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        CreateServerInstanceRequest req = baseFtpRequest();
        req.setFtpPassivePortFrom(21000);
        req.setFtpPassivePortTo(null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both be set or both null");
    }

    @Test
    void rejectsInvertedPassiveRange() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        CreateServerInstanceRequest req = baseFtpRequest();
        req.setFtpPassivePortFrom(21100);
        req.setFtpPassivePortTo(21000);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from <= to");
    }

    @Test
    void rejectsInvalidProtValue() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        CreateServerInstanceRequest req = baseFtpRequest();
        req.setFtpProtRequired("MAX"); // not one of NONE/C/P

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE, C, P");
    }

    @Test
    void portSuggestionsForFtpAvoidSshWellKnownPorts() {
        // Range scan [1024, requested+20] over host "ftp-service" returns empty used set.
        when(repository.findUsedPortsInRange(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        // When asking for suggestions around port 20 for FTP, neither 21 nor any
        // port in the block set should surface; SFTP 22/2222 should never appear.
        List<Integer> ftp = service.suggestAlternativePorts("ftp-service", 20, 5, Protocol.FTP);
        assertThat(ftp).doesNotContain(22, 2222);
    }

    @Test
    void portSuggestionsForSftpAvoidFtpWellKnownPorts() {
        when(repository.findUsedPortsInRange(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        List<Integer> sftp = service.suggestAlternativePorts("sftp-service", 20, 10, Protocol.SFTP);
        assertThat(sftp).doesNotContain(21, 990);
    }

    @Test
    void portSuggestionsWithoutProtocolDoesNotFilter() {
        when(repository.findUsedPortsInRange(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        // Without a protocol hint, 22 is a valid neighbor of 20 and should be
        // available (proving the filter only kicks in when protocol is given).
        List<Integer> any = service.suggestAlternativePorts("h", 20, 5, null);
        assertThat(any).contains(21, 22);
    }

    @Test
    void updatePathAppliesFtpFieldsAndValidates() {
        UUID id = UUID.randomUUID();
        ServerInstance existing = ServerInstance.builder()
                .instanceId("ftp-u1").protocol(Protocol.FTP).name("ftp-u1")
                .internalHost("ftp-service").internalPort(21)
                .build();
        existing.setId(id);
        lenient().when(repository.findById(id)).thenReturn(Optional.of(existing));
        lenient().when(repository.save(any(ServerInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        UpdateServerInstanceRequest upd = new UpdateServerInstanceRequest();
        upd.setFtpPassivePortFrom(22000);
        upd.setFtpPassivePortTo(22010);
        upd.setFtpProtRequired("c");
        upd.setFtpTlsCertAlias("blue");

        ServerInstanceResponse resp = service.update(id, upd);
        assertThat(resp.getFtpPassivePortFrom()).isEqualTo(22000);
        assertThat(resp.getFtpPassivePortTo()).isEqualTo(22010);
        assertThat(resp.getFtpProtRequired()).isEqualTo("C"); // normalized
        assertThat(resp.getFtpTlsCertAlias()).isEqualTo("blue");
    }

    @Test
    void updateRejectsBadPassiveRange() {
        UUID id = UUID.randomUUID();
        ServerInstance existing = ServerInstance.builder()
                .instanceId("ftp-u2").protocol(Protocol.FTP).name("ftp-u2")
                .internalHost("ftp-service").internalPort(21)
                .build();
        existing.setId(id);
        lenient().when(repository.findById(id)).thenReturn(Optional.of(existing));

        UpdateServerInstanceRequest upd = new UpdateServerInstanceRequest();
        upd.setFtpPassivePortFrom(500); // below the 1024 floor
        upd.setFtpPassivePortTo(800);

        assertThatThrownBy(() -> service.update(id, upd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syncProxyMappingIncludesFtpDataChannelPolicyOnFtp() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(repository.save(any(ServerInstance.class)))
                .thenAnswer(inv -> {
                    ServerInstance saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftp-proxy-1");
        req.setProtocol(Protocol.FTP);
        req.setName("FTP with proxy");
        req.setInternalHost("ftp-service");
        req.setInternalPort(21);
        req.setExternalHost("203.0.113.7");
        req.setUseProxy(true);
        req.setProxyHost("dmz-proxy");
        req.setProxyPort(32121);
        req.setFtpPassivePortFrom(31000);
        req.setFtpPassivePortTo(31010);

        service.create(req);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(dmzProxyClient).createMapping(captor.capture());
        java.util.Map<String, Object> mapping = captor.getValue();

        assertThat(mapping).containsKey("ftpDataChannelPolicy");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> policy = (java.util.Map<String, Object>) mapping.get("ftpDataChannelPolicy");
        assertThat(policy).containsEntry("passivePortFrom", 31000);
        assertThat(policy).containsEntry("passivePortTo", 31010);
        assertThat(policy).containsEntry("externalHost", "203.0.113.7");
        assertThat(policy).containsEntry("rewritePasvResponse", true);
    }

    @Test
    void syncProxyMappingOmitsFtpPolicyOnSftp() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(repository.save(any(ServerInstance.class)))
                .thenAnswer(inv -> {
                    ServerInstance saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("sftp-proxy-1");
        req.setProtocol(Protocol.SFTP);
        req.setName("SFTP with proxy");
        req.setInternalHost("sftp-service");
        req.setInternalPort(2222);
        req.setUseProxy(true);
        req.setProxyHost("dmz-proxy");
        req.setProxyPort(32222);

        service.create(req);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(dmzProxyClient).createMapping(captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("ftpDataChannelPolicy");
    }

    @Test
    void getByIdReturnsFtpFields() {
        UUID id = UUID.randomUUID();
        ServerInstance si = ServerInstance.builder()
                .instanceId("ftp-r1").protocol(Protocol.FTP).name("ftp-r1")
                .internalHost("ftp-service").internalPort(21)
                .ftpPassivePortFrom(23000).ftpPassivePortTo(23010)
                .ftpTlsCertAlias("rt-alias")
                .ftpProtRequired("P")
                .ftpBannerMessage("220 hi")
                .ftpImplicitTls(true)
                .build();
        si.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(si));

        ServerInstanceResponse resp = service.getById(id);
        assertThat(resp.getFtpPassivePortFrom()).isEqualTo(23000);
        assertThat(resp.getFtpPassivePortTo()).isEqualTo(23010);
        assertThat(resp.getFtpTlsCertAlias()).isEqualTo("rt-alias");
        assertThat(resp.getFtpProtRequired()).isEqualTo("P");
        assertThat(resp.getFtpBannerMessage()).isEqualTo("220 hi");
        assertThat(resp.getFtpImplicitTls()).isTrue();
    }

    @Test
    void createRoundtripsFtpWebFields() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(repository.save(any(ServerInstance.class)))
                .thenAnswer(inv -> {
                    ServerInstance saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftpweb-eu-1");
        req.setProtocol(Protocol.FTP_WEB);
        req.setName("FTP_WEB EU");
        req.setInternalHost("ftp-web-service");
        req.setInternalPort(8083);
        req.setFtpWebSessionTimeoutSeconds(1800);
        req.setFtpWebMaxUploadBytes(2_147_483_648L);
        req.setFtpWebTlsCertAlias("ftpweb-eu-cert");
        req.setFtpWebPortalTitle("EU Partner Portal");

        ServerInstanceResponse resp = service.create(req);

        assertThat(resp.getFtpWebSessionTimeoutSeconds()).isEqualTo(1800);
        assertThat(resp.getFtpWebMaxUploadBytes()).isEqualTo(2_147_483_648L);
        assertThat(resp.getFtpWebTlsCertAlias()).isEqualTo("ftpweb-eu-cert");
        assertThat(resp.getFtpWebPortalTitle()).isEqualTo("EU Partner Portal");
    }

    @Test
    void rejectsNegativeFtpWebSessionTimeout() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftpweb-bad-1");
        req.setProtocol(Protocol.FTP_WEB);
        req.setName("bad");
        req.setInternalHost("ftp-web-service");
        req.setInternalPort(8083);
        req.setFtpWebSessionTimeoutSeconds(-1);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ftpWebSessionTimeoutSeconds");
    }

    @Test
    void rejectsNegativeFtpWebMaxUpload() {
        lenient().when(repository.existsByInstanceId(anyString())).thenReturn(false);
        lenient().when(repository.findByInternalHostAndInternalPortAndActiveTrue(anyString(), anyInt()))
                .thenReturn(Optional.empty());

        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftpweb-bad-2");
        req.setProtocol(Protocol.FTP_WEB);
        req.setName("bad");
        req.setInternalHost("ftp-web-service");
        req.setInternalPort(8083);
        req.setFtpWebMaxUploadBytes(-100L);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ftpWebMaxUploadBytes");
    }

    @Test
    void getByIdThrowsNoSuchElementWhenMissing() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        // Service throws NoSuchElementException; controller's @ExceptionHandler
        // maps that to 404 (tester R86 sanity sweep regression — was 500).
        assertThatThrownBy(() -> service.getById(missing))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining(missing.toString());
    }

    private CreateServerInstanceRequest baseFtpRequest() {
        CreateServerInstanceRequest req = new CreateServerInstanceRequest();
        req.setInstanceId("ftp-valid-test");
        req.setProtocol(Protocol.FTP);
        req.setName("FTP Valid");
        req.setInternalHost("ftp-service");
        req.setInternalPort(21);
        return req;
    }
}
