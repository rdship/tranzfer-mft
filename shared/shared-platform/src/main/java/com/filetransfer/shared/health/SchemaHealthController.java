package com.filetransfer.shared.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for schema health sensor.
 *
 * <ul>
 *   <li>GET  /api/internal/schema-health — current status + last check results
 *   <li>POST /api/internal/schema-health/check — trigger immediate validation
 *   <li>PUT  /api/internal/schema-health/interval — update check interval (seconds)
 * </ul>
 */
@RestController
@RequestMapping("/api/internal/schema-health")
public class SchemaHealthController {

    private final SchemaHealthIndicator indicator;

    public SchemaHealthController(@Autowired(required = false) SchemaHealthIndicator indicator) {
        this.indicator = indicator;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        if (indicator == null) {
            return ResponseEntity.ok(Map.of("status", "UNAVAILABLE", "reason", "SchemaHealthIndicator not loaded"));
        }
        return ResponseEntity.ok(Map.of(
                "intervalSeconds", indicator.getIntervalSeconds(),
                "health", indicator.health().getDetails()
        ));
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> triggerCheck() {
        if (indicator == null) {
            return ResponseEntity.ok(Map.of("status", "UNAVAILABLE"));
        }
        return ResponseEntity.ok(indicator.runValidation());
    }

    @PutMapping("/interval")
    public ResponseEntity<Map<String, Object>> updateInterval(@RequestParam int seconds) {
        if (indicator == null) {
            return ResponseEntity.ok(Map.of("status", "UNAVAILABLE"));
        }
        indicator.setIntervalSeconds(seconds);
        return ResponseEntity.ok(Map.of(
                "intervalSeconds", indicator.getIntervalSeconds(),
                "message", "Schema check interval updated"
        ));
    }
}
