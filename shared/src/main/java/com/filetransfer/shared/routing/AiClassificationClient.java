package com.filetransfer.shared.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Map;

/**
 * Client for the AI Engine's classification API.
 * Called by RoutingEngine before routing a file.
 * If PCI data is detected and file is not encrypted, blocks the transfer.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiClassificationClient {

    @Value("${ai.engine.url:http://ai-engine:8091}")
    private String aiEngineUrl;

    @Value("${ai.classification.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    /**
     * Classify a file before routing.
     * Returns true if the file is safe to route, false if blocked.
     */
    public ClassificationDecision classify(Path filePath, String trackId, boolean isEncrypted) {
        if (!enabled) {
            return new ClassificationDecision(true, "NONE", 0, null);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(filePath.toFile()));

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    aiEngineUrl + "/api/v1/ai/classify", request, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> result = response.getBody();
                String riskLevel = (String) result.getOrDefault("riskLevel", "NONE");
                int riskScore = result.get("riskScore") instanceof Number n ? n.intValue() : 0;
                boolean blocked = Boolean.TRUE.equals(result.get("blocked"));
                String blockReason = (String) result.get("blockReason");

                if (blocked && !isEncrypted) {
                    log.warn("[{}] AI BLOCKED transfer: {} (risk={}, score={})",
                            trackId, filePath.getFileName(), riskLevel, riskScore);
                    return new ClassificationDecision(false, riskLevel, riskScore, blockReason);
                }

                log.info("[{}] AI classification: risk={} score={}", trackId, riskLevel, riskScore);
                return new ClassificationDecision(true, riskLevel, riskScore, null);
            }
        } catch (Exception e) {
            // AI service unreachable — allow transfer (graceful degradation)
            log.debug("AI classification unavailable: {} — allowing transfer", e.getMessage());
        }
        return new ClassificationDecision(true, "UNKNOWN", 0, null);
    }

    public record ClassificationDecision(boolean allowed, String riskLevel, int riskScore, String blockReason) {}
}
