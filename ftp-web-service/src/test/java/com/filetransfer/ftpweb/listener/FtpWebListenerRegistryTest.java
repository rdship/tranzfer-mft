package com.filetransfer.ftpweb.listener;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R91: ftp-web-service ServerInstance bind-state reconciliation.
 *
 * <p>Proves the registry:
 * <ul>
 *   <li>Seeds BOUND on the primary row at boot.</li>
 *   <li>Heartbeats BOUND on every scheduled reconcile.</li>
 *   <li>Does NOT touch non-primary rows (honest: we don't claim BOUND on
 *       rows that have no real listener).</li>
 *   <li>Is a no-op when no primary is configured.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FtpWebListenerRegistryTest {

    @Mock private ServerInstanceRepository repository;

    private FtpWebListenerRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new FtpWebListenerRegistry(repository);
        setPrimary("ftpweb-primary");
    }

    @Test
    void bootstrapMarksPrimaryBound() {
        ServerInstance primary = ServerInstance.builder()
                .instanceId("ftpweb-primary").protocol(Protocol.FTP_WEB).name("primary")
                .internalHost("ftp-web-service").internalPort(8083)
                .build();
        primary.setId(UUID.randomUUID());
        when(repository.findByInstanceId("ftpweb-primary")).thenReturn(Optional.of(primary));
        when(repository.findByProtocolAndActiveTrue(Protocol.FTP_WEB)).thenReturn(List.of(primary));

        registry.bootstrap();

        ArgumentCaptor<ServerInstance> saved = ArgumentCaptor.forClass(ServerInstance.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBindState()).isEqualTo("BOUND");
        assertThat(saved.getValue().getBoundNode()).isNotNull();
        assertThat(saved.getValue().getLastBindAttemptAt()).isNotNull();
    }

    @Test
    void bootstrapDoesNotTouchNonPrimaryRows() {
        ServerInstance primary = ftpWebInstance("ftpweb-primary");
        ServerInstance other   = ftpWebInstance("ftpweb-eu");
        when(repository.findByInstanceId("ftpweb-primary")).thenReturn(Optional.of(primary));
        when(repository.findByProtocolAndActiveTrue(Protocol.FTP_WEB))
                .thenReturn(List.of(primary, other));

        registry.bootstrap();

        // Only the primary row is saved — we don't lie about non-primary BOUND.
        verify(repository).save(primary);
        verify(repository, never()).save(other);
    }

    @Test
    void reconcileHeartbeatsPrimary() {
        ServerInstance primary = ftpWebInstance("ftpweb-primary");
        when(repository.findByInstanceId("ftpweb-primary")).thenReturn(Optional.of(primary));

        registry.reconcile();

        verify(repository).save(any(ServerInstance.class));
        assertThat(primary.getBindState()).isEqualTo("BOUND");
    }

    @Test
    void noPrimaryConfiguredIsNoOp() throws Exception {
        setPrimary(null);

        registry.bootstrap();
        registry.reconcile();

        verify(repository, never()).save(any(ServerInstance.class));
    }

    @Test
    void bootstrapWhenPrimaryRowMissingLogsButDoesNotThrow() {
        when(repository.findByInstanceId("ftpweb-primary")).thenReturn(Optional.empty());
        when(repository.findByProtocolAndActiveTrue(Protocol.FTP_WEB)).thenReturn(List.of());

        // Must not throw — missing row is a config drift, logged as a warning.
        registry.bootstrap();
        verify(repository, never()).save(any(ServerInstance.class));
    }

    private static ServerInstance ftpWebInstance(String id) {
        ServerInstance si = ServerInstance.builder()
                .instanceId(id).protocol(Protocol.FTP_WEB).name(id)
                .internalHost("ftp-web-service").internalPort(8083)
                .build();
        si.setId(UUID.randomUUID());
        return si;
    }

    private void setPrimary(String id) throws Exception {
        Field f = FtpWebListenerRegistry.class.getDeclaredField("primaryInstanceId");
        f.setAccessible(true);
        f.set(registry, id);
    }
}
