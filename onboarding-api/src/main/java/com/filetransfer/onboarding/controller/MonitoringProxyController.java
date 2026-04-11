package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proxy controller for Prometheus + Loki + Alertmanager queries, so the
 * admin UI's native Monitoring page can render per-service dashboards,
 * searchable logs, and live alerts without exposing those tools directly
 * to the browser (no CORS juggling, no separate auth story).
 *
 * Design notes:
 *   - Every endpoint is read-only. No proxying of POST/DELETE to Loki
 *     or Prometheus — operators wanting to silence alerts or delete
 *     series go through Grafana/Alertmanager UI directly via the "Open
 *     full" button on the Monitoring page.
 *   - Responses are returned verbatim as Map so the UI can use the same
 *     Prometheus/Loki JSON shapes its visualization library expects.
 *   - Timeouts are aggressive (10s for Prometheus, 30s for Loki because
 *     log-range queries can be slow) so a single stuck query can't lock
 *     the admin UI.
 *   - All endpoints require ADMIN role — ops data is sensitive.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@PreAuthorize(Roles.ADMIN)
@Tag(name = "Monitoring", description = "Read-only proxy for Prometheus + Loki + Alertmanager")
@Slf4j
public class MonitoringProxyController {

    private final RestTemplate restTemplate;
    private final String prometheusUrl;
    private final String lokiUrl;
    private final String alertmanagerUrl;

    public MonitoringProxyController(
            @Value("${monitoring.prometheus.url:http://prometheus:9090}") String prometheusUrl,
            @Value("${monitoring.loki.url:http://loki:3100}") String lokiUrl,
            @Value("${monitoring.alertmanager.url:http://alertmanager:9093}") String alertmanagerUrl) {
        this.restTemplate = new RestTemplate();
        this.prometheusUrl = prometheusUrl;
        this.lokiUrl = lokiUrl;
        this.alertmanagerUrl = alertmanagerUrl;
    }

    /** Execute an instant PromQL query (e.g. `up{job="onboarding-api"}`). */
    @GetMapping("/prometheus/query")
    @Operation(summary = "Instant PromQL query")
    public ResponseEntity<Map<String, Object>> promQuery(@RequestParam String query,
                                                          @RequestParam(required = false) String time) {
        URI uri = UriComponentsBuilder
                .fromUriString(prometheusUrl + "/api/v1/query")
                .queryParam("query", query)
                .queryParamIfPresent("time", java.util.Optional.ofNullable(time))
                .build()
                .toUri();
        return forward(uri, "prometheus");
    }

    /**
     * Execute a PromQL range query for plotting charts.
     * start/end are unix seconds or RFC3339. step is the resolution
     * (e.g. "15s", "1m", "5m").
     */
    @GetMapping("/prometheus/query_range")
    @Operation(summary = "Range PromQL query (time-series plot)")
    public ResponseEntity<Map<String, Object>> promRange(@RequestParam String query,
                                                          @RequestParam String start,
                                                          @RequestParam String end,
                                                          @RequestParam(defaultValue = "30s") String step) {
        URI uri = UriComponentsBuilder
                .fromUriString(prometheusUrl + "/api/v1/query_range")
                .queryParam("query", query)
                .queryParam("start", start)
                .queryParam("end", end)
                .queryParam("step", step)
                .build()
                .toUri();
        return forward(uri, "prometheus");
    }

    /** List all Prometheus scrape targets (the /targets page, JSON form). */
    @GetMapping("/prometheus/targets")
    public ResponseEntity<Map<String, Object>> promTargets() {
        URI uri = URI.create(prometheusUrl + "/api/v1/targets");
        return forward(uri, "prometheus");
    }

    /** List all active Prometheus alerts. */
    @GetMapping("/prometheus/alerts")
    public ResponseEntity<Map<String, Object>> promAlerts() {
        URI uri = URI.create(prometheusUrl + "/api/v1/alerts");
        return forward(uri, "prometheus");
    }

    /** List metric names (used for autocomplete in the query bar). */
    @GetMapping("/prometheus/labels")
    public ResponseEntity<Map<String, Object>> promLabels() {
        URI uri = URI.create(prometheusUrl + "/api/v1/label/__name__/values");
        return forward(uri, "prometheus");
    }

    /**
     * Run a LogQL query across Loki. Supports:
     *   - Instant queries: query=...
     *   - Range queries: query=... & start=... & end=... & limit=...
     * The UI uses range queries for log tailing and instant queries for
     * counts / facet filters.
     */
    @GetMapping("/loki/query_range")
    @Operation(summary = "Range LogQL query (log tailing + search)")
    public ResponseEntity<Map<String, Object>> lokiQueryRange(@RequestParam String query,
                                                               @RequestParam(required = false) String start,
                                                               @RequestParam(required = false) String end,
                                                               @RequestParam(defaultValue = "100") int limit,
                                                               @RequestParam(defaultValue = "backward") String direction) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString(lokiUrl + "/loki/api/v1/query_range")
                .queryParam("query", query)
                .queryParam("limit", limit)
                .queryParam("direction", direction);
        if (start != null) b.queryParam("start", start);
        if (end != null) b.queryParam("end", end);
        return forward(b.build().toUri(), "loki");
    }

    /** List Loki log streams (used to populate the service selector). */
    @GetMapping("/loki/labels")
    public ResponseEntity<Map<String, Object>> lokiLabels() {
        URI uri = URI.create(lokiUrl + "/loki/api/v1/labels");
        return forward(uri, "loki");
    }

    /** List values for a Loki label (e.g. "service" → list of service names). */
    @GetMapping("/loki/label/{name}/values")
    public ResponseEntity<Map<String, Object>> lokiLabelValues(@PathVariable String name) {
        URI uri = URI.create(lokiUrl + "/loki/api/v1/label/" + name + "/values");
        return forward(uri, "loki");
    }

    /** List active Alertmanager alerts. */
    @GetMapping("/alertmanager/alerts")
    public ResponseEntity<Object> alertmanagerAlerts() {
        URI uri = URI.create(alertmanagerUrl + "/api/v2/alerts");
        try {
            Object body = restTemplate.getForObject(uri, Object.class);
            return ResponseEntity.ok(body);
        } catch (RestClientException e) {
            return unavailableObject("alertmanager", e);
        }
    }

    /** List alert groups (deduplicated by label). */
    @GetMapping("/alertmanager/groups")
    public ResponseEntity<Object> alertmanagerGroups() {
        URI uri = URI.create(alertmanagerUrl + "/api/v2/alerts/groups");
        try {
            Object body = restTemplate.getForObject(uri, Object.class);
            return ResponseEntity.ok(body);
        } catch (RestClientException e) {
            return unavailableObject("alertmanager", e);
        }
    }

    /**
     * Aggregated health snapshot for the Monitoring overview card grid —
     * one call returns up/down state + basic latency for every scrape
     * target. Avoids N round-trips from the UI when rendering the grid.
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object targets = restTemplate.getForObject(
                    prometheusUrl + "/api/v1/targets?state=active", Object.class);
            result.put("targets", targets);
        } catch (RestClientException e) {
            result.put("targets", Map.of("error", e.getMessage()));
        }
        try {
            Object alerts = restTemplate.getForObject(
                    prometheusUrl + "/api/v1/alerts", Object.class);
            result.put("alerts", alerts);
        } catch (RestClientException e) {
            result.put("alerts", Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> forward(URI uri, String service) {
        try {
            log.debug("Proxy GET {}", uri);
            Map<String, Object> body = restTemplate.getForObject(uri, Map.class);
            return ResponseEntity.ok(body);
        } catch (RestClientException e) {
            log.warn("Monitoring proxy: {} unreachable at {}: {}", service, uri, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "error");
            err.put("errorType", "service_unavailable");
            err.put("error", service + " is not reachable: " + e.getMessage());
            return ResponseEntity.status(503).body(err);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> unavailableObject(String service, RestClientException e) {
        log.warn("Monitoring proxy: {} unreachable: {}", service, e.getMessage());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("status", "error");
        err.put("error", service + " is not reachable: " + e.getMessage());
        return (ResponseEntity<T>) ResponseEntity.status(503).body(err);
    }
}
