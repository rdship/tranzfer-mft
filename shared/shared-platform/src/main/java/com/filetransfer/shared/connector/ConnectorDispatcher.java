package com.filetransfer.shared.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.entity.integration.WebhookConnector;
import com.filetransfer.shared.repository.integration.WebhookConnectorRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches events to all configured external connectors.
 * Each connector type has its own payload format.
 *
 * Supported:
 * - SERVICENOW: Creates incidents via REST API
 * - PAGERDUTY:  Triggers events via Events API v2
 * - SLACK:      Posts to webhook URL
 * - TEAMS:      Posts to incoming webhook
 * - OPSGENIE:   Creates alerts via API
 * - EMAIL:      Sends via configured SMTP (future)
 * - WEBHOOK:    Generic POST with JSON payload
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "platform.connectors.enabled", havingValue = "true", matchIfMissing = false)
public class ConnectorDispatcher {

    private final WebhookConnectorRepository connectorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 30-second in-process cache for the active connector list — avoids a DB round-trip per event. */
    private final AtomicReference<List<WebhookConnector>> connectorCache = new AtomicReference<>(null);
    private volatile Instant cacheExpiry = Instant.EPOCH;

    private List<WebhookConnector> activeConnectors() {
        Instant now = Instant.now();
        if (connectorCache.get() == null || now.isAfter(cacheExpiry)) {
            synchronized (this) {
                if (connectorCache.get() == null || now.isAfter(cacheExpiry)) {
                    connectorCache.set(connectorRepository.findByActiveTrue());
                    cacheExpiry = now.plusSeconds(30);
                }
            }
        }
        return connectorCache.get();
    }

    /** Invalidate the connector list cache (call after create/update/delete connector). */
    public void invalidateCache() {
        connectorCache.set(null);
        cacheExpiry = Instant.EPOCH;
    }

    /**
     * Fire an event to all matching connectors.
     * Uses a 30-second in-process cache for active connectors and atomic counter
     * increments to avoid full entity saves on every dispatch.
     */
    @Async
    public void dispatch(MftEvent event) {
        List<WebhookConnector> connectors = activeConnectors();

        for (WebhookConnector connector : connectors) {
            if (!shouldTrigger(connector, event)) continue;

            try {
                switch (connector.getType().toUpperCase()) {
                    case "SERVICENOW" -> sendServiceNow(connector, event);
                    case "PAGERDUTY" -> sendPagerDuty(connector, event);
                    case "SLACK" -> sendSlack(connector, event);
                    case "TEAMS" -> sendTeams(connector, event);
                    case "OPSGENIE" -> sendOpsGenie(connector, event);
                    default -> sendGenericWebhook(connector, event);
                }

                // Atomic UPDATE — 2 columns only, no SELECT, no dirty-tracking overhead
                connectorRepository.incrementNotificationCount(connector.getId(), Instant.now());
                log.info("Connector '{}' ({}): dispatched {} for [{}]",
                        connector.getName(), connector.getType(), event.eventType, event.trackId);

            } catch (Exception e) {
                log.error("Connector '{}' failed: {}", connector.getName(), e.getMessage());
            }
        }
    }

    private boolean shouldTrigger(WebhookConnector connector, MftEvent event) {
        // Check event type
        if (connector.getTriggerEvents() != null &&
                !connector.getTriggerEvents().contains(event.eventType)) return false;

        // Check severity
        int eventSev = severityLevel(event.severity);
        int minSev = severityLevel(connector.getMinSeverity());
        return eventSev >= minSev;
    }

    // === ServiceNow Incident ===
    private void sendServiceNow(WebhookConnector c, MftEvent event) throws Exception {
        Map<String, Object> incident = Map.of(
                "short_description", "[TranzFer MFT] " + event.eventType + ": " + event.summary,
                "description", buildDescription(event),
                "urgency", event.severity.equals("CRITICAL") ? "1" : "2",
                "impact", event.severity.equals("CRITICAL") ? "1" : "2",
                "category", c.getSnowCategory() != null ? c.getSnowCategory() : "File Transfer",
                "assignment_group", c.getSnowAssignmentGroup() != null ? c.getSnowAssignmentGroup() : "MFT Support",
                "caller_id", "mft-platform",
                "correlation_id", event.trackId
        );
        postJson(c.getUrl() + "/api/now/table/incident",
                incident, basicAuth(c.getUsername(), c.getPassword()), c.getCustomHeaders());
    }

    // === PagerDuty Events API v2 ===
    private void sendPagerDuty(WebhookConnector c, MftEvent event) throws Exception {
        Map<String, Object> payload = Map.of(
                "routing_key", c.getAuthToken(),
                "event_action", event.severity.equals("CRITICAL") ? "trigger" : "trigger",
                "dedup_key", "mft-" + event.trackId,
                "payload", Map.of(
                        "summary", "[TranzFer] " + event.summary,
                        "severity", event.severity.toLowerCase().equals("critical") ? "critical" : "error",
                        "source", "tranzfer-mft",
                        "component", event.service,
                        "custom_details", Map.of("trackId", event.trackId, "filename", n(event.filename))
                ));
        postJson("https://events.pagerduty.com/v2/enqueue", payload, null, null);
    }

    // === Slack Webhook ===
    private void sendSlack(WebhookConnector c, MftEvent event) throws Exception {
        String color = switch (event.severity) {
            case "CRITICAL" -> "#dc2626";
            case "HIGH" -> "#f59e0b";
            default -> "#3b82f6";
        };
        Map<String, Object> msg = Map.of(
                "attachments", List.of(Map.of(
                        "color", color,
                        "title", ":rotating_light: TranzFer MFT Alert",
                        "text", event.summary,
                        "fields", List.of(
                                Map.of("title", "Track ID", "value", n(event.trackId), "short", true),
                                Map.of("title", "Severity", "value", event.severity, "short", true),
                                Map.of("title", "Event", "value", event.eventType, "short", true),
                                Map.of("title", "File", "value", n(event.filename), "short", true)
                        ),
                        "footer", "TranzFer MFT Platform",
                        "ts", Instant.now().getEpochSecond()
                )));
        postJson(c.getUrl(), msg, null, null);
    }

    // === Microsoft Teams ===
    private void sendTeams(WebhookConnector c, MftEvent event) throws Exception {
        Map<String, Object> card = Map.of(
                "@type", "MessageCard",
                "themeColor", event.severity.equals("CRITICAL") ? "dc2626" : "f59e0b",
                "summary", "TranzFer MFT Alert: " + event.eventType,
                "sections", List.of(Map.of(
                        "activityTitle", "TranzFer MFT Alert",
                        "activitySubtitle", event.eventType,
                        "facts", List.of(
                                Map.of("name", "Track ID", "value", n(event.trackId)),
                                Map.of("name", "Severity", "value", event.severity),
                                Map.of("name", "File", "value", n(event.filename)),
                                Map.of("name", "Details", "value", event.summary)
                        )
                )));
        postJson(c.getUrl(), card, null, null);
    }

    // === OpsGenie ===
    private void sendOpsGenie(WebhookConnector c, MftEvent event) throws Exception {
        Map<String, Object> alert = Map.of(
                "message", "[TranzFer] " + event.summary,
                "alias", "mft-" + event.trackId,
                "description", buildDescription(event),
                "priority", event.severity.equals("CRITICAL") ? "P1" : "P2",
                "tags", List.of("mft", event.eventType, event.severity)
        );
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "GenieKey " + c.getAuthToken());
        postJson("https://api.opsgenie.com/v2/alerts", alert, null, headers);
    }

    // === Generic Webhook ===
    private void sendGenericWebhook(WebhookConnector c, MftEvent event) throws Exception {
        Map<String, Object> payload = Map.of(
                "event", event.eventType, "severity", event.severity,
                "trackId", n(event.trackId), "filename", n(event.filename),
                "summary", event.summary, "details", n(event.details),
                "service", n(event.service), "timestamp", Instant.now().toString(),
                "source", "tranzfer-mft"
        );
        String auth = c.getAuthToken() != null ? "Bearer " + c.getAuthToken() : null;
        postJson(c.getUrl(), payload, auth, c.getCustomHeaders());
    }

    // === HTTP helpers ===

    private void postJson(String url, Object body, String auth, Map<String, String> extraHeaders) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        if (extraHeaders != null) extraHeaders.forEach(conn::setRequestProperty);
        conn.setDoOutput(true);
        conn.getOutputStream().write(objectMapper.writeValueAsBytes(body));
        int code = conn.getResponseCode();
        if (code >= 400) {
            log.warn("Connector HTTP {}: {}", code, url);
        }
    }

    private String basicAuth(String user, String pass) {
        if (user == null || pass == null) return null;
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private String buildDescription(MftEvent e) {
        return String.format("Event: %s\nTrack ID: %s\nFile: %s\nSeverity: %s\nService: %s\n\n%s",
                e.eventType, n(e.trackId), n(e.filename), e.severity, n(e.service), n(e.details));
    }

    private String n(String s) { return s != null ? s : "—"; }

    private int severityLevel(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    /**
     * Standard event structure dispatched to all connectors.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MftEvent {
        private String eventType;     // TRANSFER_FAILED, AI_BLOCKED, INTEGRITY_FAIL, etc.
        private String severity;      // LOW, MEDIUM, HIGH, CRITICAL
        private String trackId;
        private String filename;
        private String summary;
        private String details;
        private String service;       // Which microservice generated the event
        private String account;       // Affected account username
    }
}
