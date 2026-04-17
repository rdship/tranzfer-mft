package com.filetransfer.ftpweb.listener;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

/**
 * Maintains the {@code bind_state} / {@code bound_node} columns on the
 * FTP_WEB ServerInstance rows so UI and Sentinel see accurate runtime
 * state, matching the parity established by
 * {@code SftpListenerRegistry} and {@code FtpListenerRegistry}.
 *
 * <p>ftp-web-service is currently a single-Tomcat Spring Boot app with
 * exactly one effective listener (the one identified by
 * {@code ftpweb.instance-id}). Unlike SFTP/FTP, this service does not
 * spawn extra socket listeners per ServerInstance — multi-listener
 * routing requires an upstream HTTP reverse proxy (DMZ or api-gateway),
 * which is a separate work item. Until that lands:
 * <ul>
 *   <li>The primary listener is marked {@code BOUND} on boot and
 *       re-heartbeated every 30s so drift detection still works.</li>
 *   <li>Non-primary FTP_WEB rows are left at their default state and
 *       logged as "not yet supported" — we don't lie about BOUND.</li>
 * </ul>
 *
 * <p>When multi-listener support lands, the bind/unbind/rebind methods
 * here become the coordination point and the pattern matches SFTP/FTP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpWebListenerRegistry {

    private final ServerInstanceRepository repository;

    @Value("${ftpweb.instance-id:#{null}}")
    private String primaryInstanceId;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (primaryInstanceId == null || primaryInstanceId.isBlank()) {
            log.warn("FTP_WEB ListenerRegistry: no ftpweb.instance-id configured — skipping bind-state seed");
            return;
        }
        repository.findByInstanceId(primaryInstanceId).ifPresentOrElse(
                this::markBound,
                () -> log.warn("FTP_WEB primary instanceId '{}' has no ServerInstance row — " +
                                "create it via onboarding-api so UI/Sentinel can track its state",
                        primaryInstanceId));

        // Surface non-primary FTP_WEB rows once at boot so operators know
        // why they're not BOUND yet. Honest reporting > a green badge that lies.
        long nonPrimary = repository.findByProtocolAndActiveTrue(Protocol.FTP_WEB).stream()
                .filter(si -> !primaryInstanceId.equals(si.getInstanceId()))
                .count();
        if (nonPrimary > 0) {
            log.info("FTP_WEB: {} non-primary listener row(s) exist but multi-Tomcat routing " +
                    "is not yet wired — those rows remain UNBOUND until upstream HTTP reverse " +
                    "proxy work lands (tracked separately).", nonPrimary);
        }
    }

    /** Heartbeat BOUND state every 30s so the reconciler can detect a dead pod. */
    @Scheduled(fixedDelayString = "${ftpweb.listener.reconcile-ms:30000}",
               initialDelayString = "${ftpweb.listener.reconcile-initial-ms:30000}")
    @Transactional
    public void reconcile() {
        if (primaryInstanceId == null) return;
        repository.findByInstanceId(primaryInstanceId).ifPresent(this::markBound);
    }

    private void markBound(ServerInstance si) {
        si.setBindState("BOUND");
        si.setBindError(null);
        si.setLastBindAttemptAt(Instant.now());
        si.setBoundNode(hostname());
        repository.save(si);
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { return null; }
    }
}
