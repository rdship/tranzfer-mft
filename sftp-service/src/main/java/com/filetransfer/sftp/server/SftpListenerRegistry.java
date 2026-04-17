package com.filetransfer.sftp.server;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.SshServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.BindException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic SFTP listeners driven by ServerInstance CRUD.
 *
 * <p>Each entry is an independent {@link SshServer} bound to its configured port.
 * The primary env-var-driven listener (from {@link SftpServerConfig}) is NOT
 * managed here — it always exists and serves the default/fallback port. This
 * registry only handles listeners created/updated/deleted at runtime via UI.
 *
 * <p>Bind state (BOUND / UNBOUND / BIND_FAILED) is written back to
 * server_instances so UI and Sentinel can see drift from desired → actual.
 *
 * <p>HA coordination (which node owns which listener in a cluster) is deferred
 * to Phase 4 — for now, every SFTP service node tries to bind every active
 * SFTP ServerInstance whose internalHost matches its own (or is a wildcard).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpListenerRegistry {

    /** Instances keyed by ServerInstance.id — holds the live SshServer. */
    private final Map<UUID, SshServer> activeListeners = new ConcurrentHashMap<>();

    private final SftpSshServerFactory serverFactory;
    private final ServerInstanceRepository serverInstanceRepository;

    @Value("${sftp.instance-id:#{null}}")
    private String primaryInstanceId;

    @Value("${sftp.host-match:#{null}}")
    private String hostMatch;

    /**
     * On boot, bind every active SFTP ServerInstance that this node owns,
     * EXCEPT the one representing the primary env-var listener (already up).
     */
    @PostConstruct
    public void bootstrap() {
        List<ServerInstance> candidates = serverInstanceRepository.findByProtocolAndActiveTrue(Protocol.SFTP);
        for (ServerInstance si : candidates) {
            if (isPrimary(si)) {
                log.info("Skipping primary listener '{}' — already bound by env-var bean", si.getInstanceId());
                continue;
            }
            if (!ownedByThisNode(si)) continue;
            bind(si);
        }
    }

    /**
     * Attempt to bind a new listener. Idempotent — if already bound, noop.
     * On BindException, marks state BIND_FAILED and returns false.
     */
    @Transactional
    public boolean bind(ServerInstance si) {
        if (activeListeners.containsKey(si.getId())) {
            log.debug("Listener '{}' already bound — skipping", si.getInstanceId());
            return true;
        }
        try {
            SshServer sshd = serverFactory.build(si);
            sshd.start();
            activeListeners.put(si.getId(), sshd);
            markBound(si, true, null);
            log.info("SFTP listener '{}' BOUND on port {}", si.getInstanceId(), si.getInternalPort());
            return true;
        } catch (BindException e) {
            markBound(si, false, "Port " + si.getInternalPort() + " already in use");
            log.error("SFTP listener '{}' BIND_FAILED on port {}: port in use",
                    si.getInstanceId(), si.getInternalPort());
            return false;
        } catch (Exception e) {
            markBound(si, false, e.getMessage());
            log.error("SFTP listener '{}' BIND_FAILED: {}", si.getInstanceId(), e.getMessage());
            return false;
        }
    }

    /** Unbind and remove. Safe to call even if not bound. */
    @Transactional
    public void unbind(UUID serverInstanceId) {
        SshServer sshd = activeListeners.remove(serverInstanceId);
        if (sshd == null) return;
        try {
            sshd.stop(true);
            log.info("SFTP listener {} UNBOUND", serverInstanceId);
        } catch (Exception e) {
            log.warn("Error stopping listener {}: {}", serverInstanceId, e.getMessage());
        }
        serverInstanceRepository.findById(serverInstanceId).ifPresent(si -> markBound(si, false, null));
    }

    /** Unbind then bind — used when port/keys/algorithms change. */
    public void rebind(ServerInstance si) {
        unbind(si.getId());
        bind(si);
    }

    @PreDestroy
    public void shutdown() {
        activeListeners.forEach((id, sshd) -> {
            try { sshd.stop(true); } catch (Exception ignored) {}
        });
        activeListeners.clear();
    }

    public Map<UUID, SshServer> snapshot() {
        return Map.copyOf(activeListeners);
    }

    /** True if the ServerInstance is the one already bound by the env-var bean. */
    private boolean isPrimary(ServerInstance si) {
        return primaryInstanceId != null && primaryInstanceId.equals(si.getInstanceId());
    }

    /**
     * True if this SFTP node should own the listener. Match by internalHost:
     * if hostMatch is set, bind only when ServerInstance.internalHost equals it
     * or is "0.0.0.0"/"*". Otherwise (no hostMatch), bind everything — single
     * node deployment.
     */
    private boolean ownedByThisNode(ServerInstance si) {
        if (hostMatch == null || hostMatch.isBlank()) return true;
        String h = si.getInternalHost();
        return h == null || h.isBlank() || h.equals("0.0.0.0") || h.equals("*") || h.equals(hostMatch);
    }

    private void markBound(ServerInstance si, boolean bound, String error) {
        si.setBindState(bound ? "BOUND" : (error != null ? "BIND_FAILED" : "UNBOUND"));
        si.setBindError(error);
        si.setLastBindAttemptAt(Instant.now());
        serverInstanceRepository.save(si);
    }
}
