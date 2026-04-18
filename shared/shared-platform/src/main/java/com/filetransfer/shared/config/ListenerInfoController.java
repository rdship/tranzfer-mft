package com.filetransfer.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Exposes listener information for this service — what ports are active,
 * which are HTTP vs HTTPS, and allows admin to control them at runtime.
 *
 * <p>Every service gets this endpoint automatically. The UI aggregates
 * across all services to show a unified server/listener dashboard.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/internal/listeners — list all active connectors (HTTP + HTTPS)
 *   <li>POST /api/internal/listeners/{port}/pause — pause a connector (stop accepting new connections)
 *   <li>POST /api/internal/listeners/{port}/resume — resume a paused connector
 * </ul>
 *
 * <p>R110: {@code ServletWebServerApplicationContext} is injected as an
 * optional field (rather than a {@code final} constructor param) so that the
 * central {@code db-migrate} container — a Spring Boot non-web app built
 * from onboarding-api under AOT — does not fail context refresh when this
 * controller is eagerly pre-registered. {@code @ConditionalOnWebApplication}
 * remains as the primary guard for non-AOT deployments; the nullable
 * injection is the belt-and-suspenders fallback for AOT's frozen bean graph.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/listeners")
@ConditionalOnWebApplication
public class ListenerInfoController {

    @Autowired(required = false)
    @Nullable
    private ServletWebServerApplicationContext webServerContext;

    @Value("${cluster.service-type:UNKNOWN}")
    private String serviceType;

    @Value("${cluster.host:localhost}")
    private String host;

    @Value("${platform.tls.enabled:false}")
    private boolean tlsEnabled;

    /**
     * Returns all active Tomcat connectors for this service.
     */
    @GetMapping
    public List<Map<String, Object>> getListeners() {
        List<Map<String, Object>> listeners = new ArrayList<>();

        if (webServerContext == null) return listeners;
        if (webServerContext.getWebServer() instanceof TomcatWebServer tomcat) {
            for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("service", serviceType);
                info.put("host", host);
                info.put("port", connector.getPort());
                info.put("scheme", connector.getScheme());
                info.put("secure", connector.getSecure());
                info.put("protocol", connector.getProtocolHandlerClassName());
                info.put("state", connector.getStateName());
                info.put("maxConnections", connector.getProperty("maxConnections"));
                info.put("acceptCount", connector.getProperty("acceptCount"));
                info.put("tlsEnabled", connector.getSecure());

                if (connector.getSecure()) {
                    var sslConfigs = connector.findSslHostConfigs();
                    if (sslConfigs != null && sslConfigs.length > 0) {
                        info.put("tlsProtocols", sslConfigs[0].getProtocols());
                        info.put("tlsCiphers", sslConfigs[0].getCiphers());
                    }
                }
                listeners.add(info);
            }
        }
        return listeners;
    }

    /**
     * Pause a connector — stops accepting new connections on the specified port.
     * Existing connections drain gracefully.
     */
    @PostMapping("/{port}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable int port) {
        return controlConnector(port, true);
    }

    /**
     * Resume a paused connector — starts accepting connections again.
     */
    @PostMapping("/{port}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable int port) {
        return controlConnector(port, false);
    }

    private ResponseEntity<Map<String, String>> controlConnector(int port, boolean pause) {
        if (webServerContext == null
                || !(webServerContext.getWebServer() instanceof TomcatWebServer tomcat)) {
            return ResponseEntity.status(501).body(Map.of("error", "Not a Tomcat server"));
        }

        for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
            if (connector.getPort() == port) {
                try {
                    if (pause) {
                        connector.pause();
                        log.info("Paused listener on port {} ({})", port, connector.getScheme());
                    } else {
                        connector.resume();
                        log.info("Resumed listener on port {} ({})", port, connector.getScheme());
                    }
                    return ResponseEntity.ok(Map.of(
                            "status", pause ? "PAUSED" : "RUNNING",
                            "port", String.valueOf(port),
                            "scheme", connector.getScheme()));
                } catch (Exception e) {
                    return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
                }
            }
        }
        return ResponseEntity.status(404).body(Map.of("error", "No connector on port " + port));
    }
}
