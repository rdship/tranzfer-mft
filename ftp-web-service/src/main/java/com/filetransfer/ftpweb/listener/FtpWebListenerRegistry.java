package com.filetransfer.ftpweb.listener;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.listener.BindStateWriter;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
    private final BindStateWriter bindStateWriter;

    @Value("${ftpweb.instance-id:#{null}}")
    private String primaryInstanceId;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        log.info("[FTP_WEB][bootstrap] entered — primaryInstanceId={}", primaryInstanceId);
        if (primaryInstanceId == null || primaryInstanceId.isBlank()) {
            log.warn("[FTP_WEB][bootstrap] no ftpweb.instance-id configured — skipping bind-state seed");
            return;
        }
        repository.findByInstanceId(primaryInstanceId).ifPresentOrElse(
                si -> {
                    bindStateWriter.markBound(si.getId());
                    log.info("[FTP_WEB][bootstrap] BOUND primary '{}' id={}", primaryInstanceId, si.getId());
                },
                () -> log.warn("[FTP_WEB][bootstrap] primary instanceId '{}' has no ServerInstance row — " +
                                "create it via onboarding-api so UI/Sentinel can track its state",
                        primaryInstanceId));

        // R134A — previously these secondary rows were only log-surfaced; their
        // bind_state stayed at UNKNOWN (the schema default) because nothing ever
        // called any markXxx on them. Tester R134p flagged this as "4/10
        // listeners UNKNOWN". Honest reporting: mark non-primary FTP_WEB rows
        // explicitly UNBOUND at boot — admin UI then shows the correct intent.
        // Multi-Tomcat secondary-binding is tracked separately; this doesn't
        // claim to bind them, only to stop mis-labelling them.
        List<ServerInstance> all = repository.findByProtocolAndActiveTrue(Protocol.FTP_WEB);
        int secondaries = 0;
        for (ServerInstance si : all) {
            if (primaryInstanceId.equals(si.getInstanceId())) continue;
            bindStateWriter.markUnbound(si.getId());
            log.info("[FTP_WEB][bootstrap] UNBOUND secondary '{}' id={} port={} — " +
                    "multi-Tomcat routing not yet wired (see doc)",
                    si.getInstanceId(), si.getId(), si.getInternalPort());
            secondaries++;
        }
        log.info("[FTP_WEB][bootstrap] done — 1 primary bound, {} secondary(ies) explicitly UNBOUND",
                secondaries);
    }

    /** Heartbeat BOUND state every 30s so the reconciler can detect a dead pod. */
    @Scheduled(fixedDelayString = "${ftpweb.listener.reconcile-ms:30000}",
               initialDelayString = "${ftpweb.listener.reconcile-initial-ms:30000}")
    public void reconcile() {
        if (primaryInstanceId == null) return;
        repository.findByInstanceId(primaryInstanceId)
                .ifPresent(si -> bindStateWriter.markBound(si.getId()));
    }
}
