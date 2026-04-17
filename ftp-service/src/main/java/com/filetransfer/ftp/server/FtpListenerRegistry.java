package com.filetransfer.ftp.server;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.FtpServer;
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
 * Manages dynamic FTP/FTPS listeners driven by ServerInstance CRUD.
 *
 * <p>The primary env-var-driven listener (from {@link FtpServerConfig}) is
 * NOT managed here — it always exists. This registry binds additional
 * listeners created at runtime via the UI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpListenerRegistry {

    private final Map<UUID, FtpServer> activeListeners = new ConcurrentHashMap<>();

    private final FtpServerBuilder serverBuilder;
    private final ServerInstanceRepository repository;

    @Value("${ftp.instance-id:#{null}}")
    private String primaryInstanceId;

    @Value("${ftp.host-match:#{null}}")
    private String hostMatch;

    @PostConstruct
    public void bootstrap() {
        List<ServerInstance> candidates = repository.findByProtocolAndActiveTrue(Protocol.FTP);
        for (ServerInstance si : candidates) {
            if (isPrimary(si)) continue;
            if (!ownedByThisNode(si)) continue;
            bind(si);
        }
    }

    @Transactional
    public boolean bind(ServerInstance si) {
        if (activeListeners.containsKey(si.getId())) return true;
        try {
            FtpServer ftpd = serverBuilder.build(si);
            ftpd.start();
            activeListeners.put(si.getId(), ftpd);
            markBound(si, true, null);
            log.info("FTP listener '{}' BOUND on port {}", si.getInstanceId(), si.getInternalPort());
            return true;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root != root.getCause()) root = root.getCause();
            if (root instanceof BindException) {
                markBound(si, false, "Port " + si.getInternalPort() + " already in use");
                log.error("FTP listener '{}' BIND_FAILED on port {}: port in use",
                        si.getInstanceId(), si.getInternalPort());
            } else {
                markBound(si, false, e.getMessage());
                log.error("FTP listener '{}' BIND_FAILED: {}", si.getInstanceId(), e.getMessage());
            }
            return false;
        }
    }

    @Transactional
    public void unbind(UUID serverInstanceId) {
        FtpServer ftpd = activeListeners.remove(serverInstanceId);
        if (ftpd == null) return;
        try {
            ftpd.stop();
            log.info("FTP listener {} UNBOUND", serverInstanceId);
        } catch (Exception e) {
            log.warn("Error stopping listener {}: {}", serverInstanceId, e.getMessage());
        }
        repository.findById(serverInstanceId).ifPresent(si -> markBound(si, false, null));
    }

    public void rebind(ServerInstance si) {
        unbind(si.getId());
        bind(si);
    }

    @PreDestroy
    public void shutdown() {
        activeListeners.forEach((id, ftpd) -> {
            try { ftpd.stop(); } catch (Exception ignored) {}
        });
        activeListeners.clear();
    }

    public Map<UUID, FtpServer> snapshot() { return Map.copyOf(activeListeners); }

    private boolean isPrimary(ServerInstance si) {
        return primaryInstanceId != null && primaryInstanceId.equals(si.getInstanceId());
    }

    private boolean ownedByThisNode(ServerInstance si) {
        if (hostMatch == null || hostMatch.isBlank()) return true;
        String h = si.getInternalHost();
        return h == null || h.isBlank() || h.equals("0.0.0.0") || h.equals("*") || h.equals(hostMatch);
    }

    private void markBound(ServerInstance si, boolean bound, String error) {
        si.setBindState(bound ? "BOUND" : (error != null ? "BIND_FAILED" : "UNBOUND"));
        si.setBindError(error);
        si.setLastBindAttemptAt(Instant.now());
        repository.save(si);
    }
}
