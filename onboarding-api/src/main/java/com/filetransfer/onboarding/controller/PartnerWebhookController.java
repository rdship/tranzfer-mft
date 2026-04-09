package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.PartnerWebhook;
import com.filetransfer.shared.repository.PartnerWebhookRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for partner-configurable webhook endpoints.
 *
 * <pre>
 * GET    /api/partner-webhooks          — list all (VIEWER)
 * POST   /api/partner-webhooks          — create (OPERATOR)
 * PUT    /api/partner-webhooks/{id}     — update (OPERATOR)
 * DELETE /api/partner-webhooks/{id}     — delete (OPERATOR)
 * POST   /api/partner-webhooks/{id}/test — fire a test event (OPERATOR)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/partner-webhooks")
@RequiredArgsConstructor
public class PartnerWebhookController {

    private final PartnerWebhookRepository webhookRepository;

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<PartnerWebhook>> listAll() {
        return ResponseEntity.ok(webhookRepository.findAll());
    }

    @PostMapping
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<PartnerWebhook> create(@RequestBody PartnerWebhook body) {
        if (body.getUrl() == null || body.getUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        if (body.getPartnerName() == null || body.getPartnerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerName is required");
        }
        body.setId(null);
        body.setCreatedAt(Instant.now());
        body.setTotalCalls(0);
        body.setFailedCalls(0);
        body.setLastTriggered(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(webhookRepository.save(body));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<PartnerWebhook> update(@PathVariable UUID id, @RequestBody PartnerWebhook body) {
        PartnerWebhook existing = webhookRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found: " + id));
        if (body.getPartnerName() != null) existing.setPartnerName(body.getPartnerName());
        if (body.getUrl()         != null) existing.setUrl(body.getUrl());
        if (body.getSecret()      != null) existing.setSecret(body.getSecret().isBlank() ? null : body.getSecret());
        if (body.getEvents()      != null) existing.setEvents(body.getEvents());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        existing.setActive(body.isActive());
        return ResponseEntity.ok(webhookRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        webhookRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found: " + id));
        webhookRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fire a synthetic FLOW_COMPLETED test event to the webhook to verify connectivity.
     */
    @PostMapping("/{id}/test")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> test(@PathVariable UUID id) {
        PartnerWebhook hook = webhookRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook not found: " + id));

        try {
            // Build test payload
            String body = "{\"eventType\":\"TEST\",\"trackId\":\"TEST-00000000\"," +
                    "\"filename\":\"test-file.xml\",\"status\":\"COMPLETED\"," +
                    "\"flowName\":\"test-flow\",\"attemptNumber\":1," +
                    "\"errorMessage\":null,\"timestamp\":\"" + Instant.now() + "\"}";

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "TranzFer-MFT-Webhook/3.0");
            headers.set("X-Webhook-Event", "TEST");

            org.springframework.web.client.RestTemplate rt = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> resp = rt.postForEntity(
                    hook.getUrl(), new org.springframework.http.HttpEntity<>(body, headers), String.class);

            log.info("[WEBHOOK-TEST] {} → {} HTTP {}", hook.getPartnerName(), hook.getUrl(), resp.getStatusCode());
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "httpStatus", resp.getStatusCode().value(),
                    "url", hook.getUrl()));
        } catch (Exception e) {
            log.warn("[WEBHOOK-TEST] {} → {} FAILED: {}", hook.getPartnerName(), hook.getUrl(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage(),
                    "url", hook.getUrl()));
        }
    }
}
