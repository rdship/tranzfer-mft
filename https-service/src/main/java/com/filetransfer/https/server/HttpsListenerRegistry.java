package com.filetransfer.https.server;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.listener.BindStateWriter;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic HTTPS listener registry. Mirrors the SFTP / FTP pattern:
 * {@link com.filetransfer.https.messaging.ServerInstanceEventConsumer} invokes
 * {@link #bind(ServerInstance)} / {@link #rebind(ServerInstance)} /
 * {@link #unbind(UUID)} in response to AMQP events; on service startup,
 * {@link #bootstrap()} scans existing HTTPS rows and binds each one.
 *
 * <p>Bind state (BOUND / BIND_FAILED / UNBOUND) is written back to
 * {@code server_instances} via {@link BindStateWriter} so UI + Sentinel see
 * drift from desired → actual.
 *
 * <p>Implementation detail: per-listener {@link Connector}s are added to the
 * SAME embedded Tomcat that serves the management port (8099). This keeps
 * our single-service memory footprint low and avoids spinning up N Tomcat
 * instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpsListenerRegistry {

    /** Active connectors keyed by ServerInstance.id. */
    private final Map<UUID, Connector> activeConnectors = new ConcurrentHashMap<>();

    private final HttpsConnectorFactory connectorFactory;
    private final ServerInstanceRepository serverInstanceRepository;
    private final BindStateWriter bindStateWriter;

    /**
     * Lazy lookup of the embedded Tomcat — the servlet context isn't ready
     * at constructor time; we resolve via Spring's
     * {@link ServletWebServerApplicationContext#getWebServer()} once the
     * {@link ApplicationReadyEvent} fires.
     */
    @Autowired
    private ServletWebServerApplicationContext serverContext;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        List<ServerInstance> existing = serverInstanceRepository.findByProtocolAndActiveTrue(Protocol.HTTPS);
        log.info("[HTTPS] bootstrap — found {} active HTTPS listener row(s) to bind", existing.size());
        for (ServerInstance si : existing) {
            bind(si);
        }
    }

    /** Bind a new listener. Idempotent — a second call for the same id rebinds. */
    public synchronized void bind(ServerInstance si) {
        if (si == null || si.getId() == null) return;
        if (activeConnectors.containsKey(si.getId())) {
            log.debug("[HTTPS] listener '{}' already bound — rebinding", si.getInstanceId());
            unbind(si.getId());
        }
        try {
            Tomcat tomcat = tomcat();
            Connector conn = connectorFactory.build(si);
            tomcat.getService().addConnector(conn);
            // Tomcat 10 requires explicit connector start after addConnector on a
            // running service — the auto-start only covers connectors added
            // before Lifecycle.start().
            if (!conn.getState().isAvailable()) {
                conn.start();
            }
            activeConnectors.put(si.getId(), conn);
            bindStateWriter.markBound(si.getId());
            log.info("[HTTPS] Bound '{}' on port {}", si.getInstanceId(), si.getInternalPort());
        } catch (Exception e) {
            log.error("[HTTPS] Bind failed for '{}' (port {}): {}",
                    si.getInstanceId(), si.getInternalPort(), e.getMessage(), e);
            bindStateWriter.markBindFailed(si.getId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Rebuild the connector (used on UPDATED events — port or cert may have changed). */
    public synchronized void rebind(ServerInstance si) {
        if (si == null || si.getId() == null) return;
        unbind(si.getId());
        bind(si);
    }

    /** Remove a listener if present. Idempotent. */
    public synchronized void unbind(UUID serverInstanceId) {
        Connector conn = activeConnectors.remove(serverInstanceId);
        if (conn == null) return;
        try {
            Tomcat tomcat = tomcat();
            conn.stop();
            tomcat.getService().removeConnector(conn);
            conn.destroy();
            bindStateWriter.markUnbound(serverInstanceId);
            log.info("[HTTPS] Unbound listener id={}", serverInstanceId);
        } catch (LifecycleException e) {
            log.warn("[HTTPS] Unbind for {} threw: {}", serverInstanceId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        for (UUID id : activeConnectors.keySet().toArray(new UUID[0])) {
            unbind(id);
        }
    }

    private Tomcat tomcat() {
        WebServer ws = serverContext.getWebServer();
        if (ws instanceof TomcatWebServer tws) {
            return tws.getTomcat();
        }
        throw new IllegalStateException("Embedded web server is not Tomcat (got " + ws.getClass().getName() + ")");
    }

    private static String nodeId() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
