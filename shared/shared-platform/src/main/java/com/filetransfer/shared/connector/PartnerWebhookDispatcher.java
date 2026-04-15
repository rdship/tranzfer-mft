package com.filetransfer.shared.connector;

import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.entity.integration.PartnerWebhook;
import com.filetransfer.shared.repository.integration.PartnerWebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Dispatches HMAC-SHA256-signed HTTP POST notifications to all active
 * {@link PartnerWebhook} endpoints whose event list includes the triggered event type.
 *
 * <p>Payload (JSON):
 * <pre>
 * {
 *   "eventType": "FLOW_COMPLETED",
 *   "trackId": "TRZ-A1B2C3D4",
 *   "filename": "invoice-2026.xml",
 *   "status": "COMPLETED",
 *   "flowName": "inbound-pgp",
 *   "attemptNumber": 1,
 *   "errorMessage": null,
 *   "completedAt": "2026-04-09T02:00:01Z",
 *   "timestamp": "2026-04-09T02:00:01Z"
 * }
 * </pre>
 *
 * <p>If a {@link PartnerWebhook#getSecret()} is set, adds:
 * {@code X-Webhook-Signature: sha256=<hex-hmac-sha256-of-body>}
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "platform.connectors.enabled", havingValue = "true", matchIfMissing = false)
public class PartnerWebhookDispatcher {

    private final PartnerWebhookRepository webhookRepository;
    private final RestTemplate restTemplate;

    /**
     * Fire webhooks for the given event type asynchronously.
     * Each failing delivery is logged but does not affect the others.
     *
     * @param eventType e.g. "FLOW_COMPLETED" or "FLOW_FAILED"
     * @param exec      the completed/failed execution (read-only)
     * @param flowName  the name of the matched flow (from FileFlow entity, already resolved)
     */
    @Async
    public void dispatch(String eventType, FlowExecution exec, String flowName) {
        List<PartnerWebhook> hooks = webhookRepository.findByActiveTrue();
        if (hooks.isEmpty()) return;

        String body = buildPayload(eventType, exec, flowName);

        for (PartnerWebhook hook : hooks) {
            if (!hook.getEvents().contains(eventType)) continue;
            try {
                deliver(hook, body);
                webhookRepository.incrementTotalCalls(hook.getId(), Instant.now());
                log.debug("[{}] Webhook delivered to {} for event {}", exec.getTrackId(), hook.getUrl(), eventType);
            } catch (Exception e) {
                webhookRepository.incrementTotalCalls(hook.getId(), Instant.now());
                webhookRepository.incrementFailedCalls(hook.getId());
                log.warn("[{}] Webhook delivery failed to {}: {}", exec.getTrackId(), hook.getUrl(), e.getMessage());
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void deliver(PartnerWebhook hook, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "TranzFer-MFT-Webhook/3.0");

        if (hook.getSecret() != null && !hook.getSecret().isBlank()) {
            headers.set("X-Webhook-Signature", "sha256=" + hmacSha256(body, hook.getSecret()));
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                hook.getUrl(), HttpMethod.POST, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }
    }

    private String buildPayload(String eventType, FlowExecution exec, String flowName) {
        // Hand-built JSON — avoids ObjectMapper dependency in shared-platform
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendStr(sb, "eventType", eventType); sb.append(',');
        appendStr(sb, "trackId", exec.getTrackId()); sb.append(',');
        appendStr(sb, "filename", exec.getOriginalFilename()); sb.append(',');
        appendStr(sb, "status", exec.getStatus().name()); sb.append(',');
        appendStr(sb, "flowName", flowName); sb.append(',');
        appendNum(sb, "attemptNumber", exec.getAttemptNumber()); sb.append(',');
        appendStr(sb, "errorMessage", exec.getErrorMessage()); sb.append(',');
        appendStr(sb, "completedAt", exec.getCompletedAt() != null ? exec.getCompletedAt().toString() : null); sb.append(',');
        appendStr(sb, "timestamp", Instant.now().toString());
        sb.append('}');
        return sb.toString();
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append('"').append(':');
        if (value == null) sb.append("null");
        else sb.append('"').append(value.replace("\"", "\\\"")).append('"');
    }

    private static void appendNum(StringBuilder sb, String key, long value) {
        sb.append('"').append(key).append('"').append(':').append(value);
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }
}
