package com.filetransfer.sftp.server;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.listener.BindStateWriter;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.net.BindException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R92: regression guard for the tester's REG-1/2/3 cascade on R87-R89.
 *
 * <p>REG-1 was "bind_state writeback broken on dynamic listeners". Root
 * cause: {@code SftpListenerRegistry.bind()} was @Transactional, but
 * {@code bootstrap()} and {@code reconcile()} call {@code bind()} via
 * self-invocation — Java's direct dispatch bypasses Spring's proxy, so the
 * transaction annotation was a no-op on those paths. The state write
 * landed on a detached entity and could be lost under concurrent access.
 *
 * <p>Fix: route every bind-state write through {@link BindStateWriter},
 * which lives in a separate @Component and reloads the entity by id
 * inside its own REQUIRES_NEW transaction. This test pins that wiring
 * so future refactors don't regress it again.
 */
@ExtendWith(MockitoExtension.class)
class SftpListenerRegistryBindStateTest {

    @Mock private SftpSshServerFactory serverFactory;
    @Mock private ServerInstanceRepository repository;
    @Mock private BindStateWriter bindStateWriter;
    @Mock private SshServer sshd;

    private SftpListenerRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SftpListenerRegistry(serverFactory, repository, bindStateWriter);
        // Pretend we're running on a non-primary port so isPrimary() returns false.
        setField("primaryInstanceId", "sftp-1");
        setField("primaryPort", 2222);
    }

    @Test
    void bindSuccessRoutesThroughBindStateWriterMarkBound() throws Exception {
        ServerInstance si = dynamicInstance();
        when(serverFactory.build(si)).thenReturn(sshd);

        boolean ok = registry.bind(si);

        assertThat(ok).isTrue();
        // REG-1 guard: writes go through BindStateWriter (cross-class proxy),
        // keyed by id (not entity). A passed-in detached entity can't short-
        // circuit the write anymore.
        verify(bindStateWriter).markBound(si.getId());
        verify(bindStateWriter, never()).markBindFailed(eq(si.getId()), org.mockito.ArgumentMatchers.anyString());
        verify(bindStateWriter, never()).markUnbound(si.getId());
    }

    @Test
    void bindExceptionRoutesThroughMarkBindFailed() throws Exception {
        ServerInstance si = dynamicInstance();
        when(serverFactory.build(si)).thenReturn(sshd);
        // SshServer.start() throws BindException on port collision.
        org.mockito.Mockito.doThrow(new BindException("Address already in use"))
                .when(sshd).start();

        boolean ok = registry.bind(si);

        assertThat(ok).isFalse();
        verify(bindStateWriter).markBindFailed(eq(si.getId()),
                org.mockito.ArgumentMatchers.contains("already in use"));
        verify(bindStateWriter, never()).markBound(si.getId());
    }

    @Test
    void unbindRoutesThroughMarkUnbound() throws Exception {
        // Seed a bound listener so unbind does its real work path.
        ServerInstance si = dynamicInstance();
        when(serverFactory.build(si)).thenReturn(sshd);
        registry.bind(si);

        registry.unbind(si.getId());

        verify(bindStateWriter).markUnbound(si.getId());
    }

    @Test
    void unbindOnNotBoundListenerIsSafeAndDoesNotWriteState() {
        // No prior bind — unbind must be a no-op (no stop, no state write).
        UUID ghost = UUID.randomUUID();
        registry.unbind(ghost);

        verify(bindStateWriter, never()).markUnbound(ghost);
    }

    @Test
    void rebindWritesUnboundThenBound() throws Exception {
        ServerInstance si = dynamicInstance();
        when(serverFactory.build(si)).thenReturn(sshd);
        registry.bind(si);                              // → markBound #1
        org.mockito.Mockito.reset(bindStateWriter);

        // Second bind call after rebind: need a fresh SshServer mock since the
        // first one was stopped; return the same mock (test double) for simplicity.
        registry.rebind(si);

        verify(bindStateWriter).markUnbound(si.getId());
        verify(bindStateWriter).markBound(si.getId());
    }

    @Test
    void bindIsIdempotentWhenAlreadyBound() throws Exception {
        ServerInstance si = dynamicInstance();
        when(serverFactory.build(si)).thenReturn(sshd);

        registry.bind(si);
        org.mockito.Mockito.reset(bindStateWriter);
        registry.bind(si);   // second call — early-return path

        verify(bindStateWriter, never()).markBound(si.getId());
    }

    private static ServerInstance dynamicInstance() {
        ServerInstance si = ServerInstance.builder()
                .instanceId("sanity-sftp-1")
                .protocol(Protocol.SFTP)
                .name("sanity")
                .internalHost("sftp-service")
                .internalPort(2250)
                .build();
        si.setId(UUID.randomUUID());
        return si;
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SftpListenerRegistry.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(registry, value);
    }
}
