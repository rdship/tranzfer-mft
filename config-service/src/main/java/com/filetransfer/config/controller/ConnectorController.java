package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.WebhookConnector;
import com.filetransfer.shared.repository.WebhookConnectorRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/connectors")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ConnectorController {

    private final WebhookConnectorRepository connectorRepository;

    @GetMapping
    public List<WebhookConnector> getAll() { return connectorRepository.findByActiveTrue(); }

    @PostMapping
    public ResponseEntity<WebhookConnector> create(@RequestBody WebhookConnector connector) {
        connector.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(connectorRepository.save(connector));
    }

    @PutMapping("/{id}")
    public WebhookConnector update(@PathVariable UUID id, @RequestBody WebhookConnector connector) {
        if (!connectorRepository.existsById(id)) throw new EntityNotFoundException("Not found: " + id);
        connector.setId(id);
        return connectorRepository.save(connector);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        WebhookConnector c = connectorRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Not found: " + id));
        c.setActive(false);
        connectorRepository.save(c);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, String>> test(@PathVariable UUID id) {
        WebhookConnector c = connectorRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Not found: " + id));
        // Send test event
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(c.getUrl()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write("{\"test\":true,\"source\":\"tranzfer-mft\"}".getBytes());
            int code = conn.getResponseCode();
            return ResponseEntity.ok(Map.of("status", code < 400 ? "OK" : "FAILED", "httpCode", String.valueOf(code)));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "FAILED", "error", e.getMessage()));
        }
    }

    @GetMapping("/types")
    public Map<String, Object> getTypes() {
        return Map.of("types", List.of(
                Map.of("id", "SERVICENOW", "name", "ServiceNow", "description", "Create incidents automatically"),
                Map.of("id", "PAGERDUTY", "name", "PagerDuty", "description", "Trigger PagerDuty events"),
                Map.of("id", "SLACK", "name", "Slack", "description", "Post to Slack channel"),
                Map.of("id", "TEAMS", "name", "Microsoft Teams", "description", "Post to Teams channel"),
                Map.of("id", "OPSGENIE", "name", "OpsGenie", "description", "Create OpsGenie alerts"),
                Map.of("id", "WEBHOOK", "name", "Generic Webhook", "description", "POST JSON to any URL")
        ));
    }
}
