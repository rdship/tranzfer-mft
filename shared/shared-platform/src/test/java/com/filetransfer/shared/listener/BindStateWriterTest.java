package com.filetransfer.shared.listener;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R92: BindStateWriter always reloads the entity by id and saves inside its
 * own transaction. This is the fix for the tester's REG-1/2/3 cascade on
 * R87-R89 where self-invocation of @Transactional + detached-entity merges
 * left bind_state stuck at UNBOUND.
 *
 * <p>Tests assert three invariants:
 * <ol>
 *   <li>The writer reads by id → mutates → saves the <b>freshly-loaded</b>
 *       managed entity. Never trusts a passed-in detached instance.</li>
 *   <li>BOUND / BIND_FAILED / UNBOUND each produce the right field shape.</li>
 *   <li>Missing row is a no-op (log + swallow) — calls must never throw.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BindStateWriterTest {

    @Mock private ServerInstanceRepository repository;

    private BindStateWriter writer;

    @BeforeEach
    void setUp() {
        writer = new BindStateWriter(repository);
    }

    @Test
    void markBoundReloadsAndSavesWithHostnameAndClearedError() {
        UUID id = UUID.randomUUID();
        ServerInstance fresh = newRow(id);
        fresh.setBindState("SOMETHING_STALE");
        fresh.setBindError("leftover");
        when(repository.findById(id)).thenReturn(Optional.of(fresh));

        writer.markBound(id);

        ArgumentCaptor<ServerInstance> captor = ArgumentCaptor.forClass(ServerInstance.class);
        verify(repository).save(captor.capture());
        ServerInstance saved = captor.getValue();
        assertThat(saved.getBindState()).isEqualTo("BOUND");
        assertThat(saved.getBindError()).isNull();
        assertThat(saved.getBoundNode()).isNotNull();
        assertThat(saved.getLastBindAttemptAt()).isNotNull();
    }

    @Test
    void markBindFailedStoresErrorAndClearsNode() {
        UUID id = UUID.randomUUID();
        ServerInstance fresh = newRow(id);
        // Simulate a prior BOUND → verify we clear bound_node on failure.
        fresh.setBoundNode("ghost-host");
        when(repository.findById(id)).thenReturn(Optional.of(fresh));

        writer.markBindFailed(id, "Port 2250 already in use");

        ArgumentCaptor<ServerInstance> captor = ArgumentCaptor.forClass(ServerInstance.class);
        verify(repository).save(captor.capture());
        ServerInstance saved = captor.getValue();
        assertThat(saved.getBindState()).isEqualTo("BIND_FAILED");
        assertThat(saved.getBindError()).isEqualTo("Port 2250 already in use");
        assertThat(saved.getBoundNode()).isNull();
        assertThat(saved.getLastBindAttemptAt()).isNotNull();
    }

    @Test
    void markUnboundClearsErrorAndNode() {
        UUID id = UUID.randomUUID();
        ServerInstance fresh = newRow(id);
        fresh.setBindState("BOUND");
        fresh.setBoundNode("somehost");
        fresh.setBindError(null);
        when(repository.findById(id)).thenReturn(Optional.of(fresh));

        writer.markUnbound(id);

        ArgumentCaptor<ServerInstance> captor = ArgumentCaptor.forClass(ServerInstance.class);
        verify(repository).save(captor.capture());
        ServerInstance saved = captor.getValue();
        assertThat(saved.getBindState()).isEqualTo("UNBOUND");
        assertThat(saved.getBindError()).isNull();
        assertThat(saved.getBoundNode()).isNull();
    }

    @Test
    void missingRowIsSafeNoOp() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        // Each of the three writes must not throw; none should save.
        writer.markBound(id);
        writer.markBindFailed(id, "whatever");
        writer.markUnbound(id);

        verify(repository, never()).save(any(ServerInstance.class));
    }

    @Test
    void alwaysReloadsByIdRatherThanUsingPassedEntity() {
        // The whole point of the R92 refactor: writers accept UUID (not entity),
        // so a caller holding a detached entity with stale state can't pollute
        // the write. Verify findById(id) is invoked for every operation.
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(newRow(id)));

        writer.markBound(id);
        writer.markBindFailed(id, "x");
        writer.markUnbound(id);

        verify(repository, org.mockito.Mockito.times(3)).findById(id);
    }

    private static ServerInstance newRow(UUID id) {
        ServerInstance si = ServerInstance.builder()
                .instanceId("x-" + id.toString().substring(0, 8))
                .protocol(Protocol.SFTP)
                .name("x")
                .internalHost("host")
                .internalPort(2222)
                .build();
        si.setId(id);
        return si;
    }
}
