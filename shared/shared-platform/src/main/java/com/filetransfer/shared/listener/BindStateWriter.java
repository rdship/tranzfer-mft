package com.filetransfer.shared.listener;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes {@code bind_state / bind_error / last_bind_attempt_at / bound_node}
 * to a ServerInstance row in its own transaction.
 *
 * <p><b>Why this exists.</b> The per-protocol listener registries
 * ({@code SftpListenerRegistry}, {@code FtpListenerRegistry},
 * {@code FtpWebListenerRegistry}) all need to persist bind state, and all of
 * them originally had private {@code markBound(ServerInstance, boolean, String)}
 * helpers that ran on whatever entity they were given. That design had two
 * latent problems the R87-R89 sanity sweep surfaced:
 *
 * <ol>
 *   <li><b>Self-invocation bypasses Spring's @Transactional proxy.</b>
 *       {@code bootstrap()} and {@code reconcile()} are methods on the same
 *       class as {@code bind()}/{@code unbind()}. Java's direct dispatch skips
 *       the transactional proxy, so the @Transactional annotation on
 *       bind/unbind was effectively a no-op on those call paths. Any save()
 *       inside then relied on Spring Data's own per-call transaction, which
 *       is fine for simple writes but offers no enclosing scope for the
 *       detached-entity merge to run in.</li>
 *   <li><b>Detached entity merges can silently lose field updates</b> when
 *       the source object's fields were mutated after it was loaded in a
 *       different session. Hibernate's merge copies state from source →
 *       managed, but only after attaching a freshly-loaded managed copy.
 *       If the row in the DB was concurrently modified (e.g. an unbind race
 *       with a bind), the managed copy's bindState is loaded from DB before
 *       the source's "BOUND" mutation is copied over — visible to the merge
 *       only after. In pathological concurrent cases this can leave the row
 *       in an unexpected state.</li>
 * </ol>
 *
 * <p>This writer sidesteps both by:
 * <ul>
 *   <li>Living in its own @Component — registries call it via cross-class
 *       dispatch so the proxy fires every time.</li>
 *   <li>Always reloading the row by {@code UUID} inside its @Transactional
 *       boundary, mutating the managed entity, and letting Hibernate auto-
 *       flush at commit. No merge, no detached-entity surprises.</li>
 *   <li>Running at {@code Propagation.REQUIRES_NEW} so a caller's stalled or
 *       rolled-back transaction can't suppress the bind-state write — an
 *       accurate control-plane view matters even when the owning logic fails.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BindStateWriter {

    private final ServerInstanceRepository repository;

    /**
     * Mark the listener {@code BOUND} — sets bind_state=BOUND, clears error,
     * stamps bound_node with this container's hostname, and updates the
     * last-attempt timestamp. No-op if the row no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBound(UUID serverInstanceId) {
        applyUpdate(serverInstanceId, "BOUND", null, hostname());
    }

    /**
     * Mark the listener {@code BIND_FAILED} with a human-readable error —
     * sets bind_state=BIND_FAILED, stashes the error, clears bound_node.
     * Upstream UIs render this in red with a "rebind" action.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBindFailed(UUID serverInstanceId, String error) {
        applyUpdate(serverInstanceId, "BIND_FAILED", error, null);
    }

    /**
     * Mark the listener {@code UNBOUND} — used by the graceful-stop path and
     * by reconciliation's orphan cleanup. Clears bound_node and error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnbound(UUID serverInstanceId) {
        applyUpdate(serverInstanceId, "UNBOUND", null, null);
    }

    private void applyUpdate(UUID id, String state, String error, String node) {
        repository.findById(id).ifPresentOrElse(si -> {
            si.setBindState(state);
            si.setBindError(error);
            si.setLastBindAttemptAt(Instant.now());
            si.setBoundNode(node);
            repository.save(si);
        }, () -> log.debug("BindStateWriter: row {} not found — {}; skipping write",
                id, state));
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { return null; }
    }
}
