package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Client for the AI Engine service (port 8091).
 * Provides file classification, data leakage detection, EDI analysis,
 * intelligent routing, and proxy intelligence.
 *
 * <p>Error strategy: <b>graceful degradation</b> — AI service unavailability
 * should never block file transfers. Classification defaults to "ALLOWED"
 * when the service is unreachable.
 */
@Slf4j
@Component
public class AiEngineClient extends ResilientServiceClient {

    public AiEngineClient(RestTemplate restTemplate,
                          PlatformConfig platformConfig,
                          ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getAiEngine(), "ai-engine");
    }

    /**
     * Classify a file for sensitive data (PCI, PII, PHI).
     * Returns classification result or a safe default if unavailable.
     */
    @SuppressWarnings("unchecked")
    public ClassificationResult classify(Path filePath, String trackId, boolean isEncrypted) {
        if (!isEnabled()) {
            return ClassificationResult.ALLOWED;
        }
        try {
            return withResilience("classify", () -> {
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", new FileSystemResource(filePath.toFile()));
                HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, multipartHeaders());

                ResponseEntity<Map> response = restTemplate.postForEntity(
                        baseUrl() + "/api/v1/ai/classify", entity, Map.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> result = response.getBody();
                    String riskLevel = (String) result.getOrDefault("riskLevel", "NONE");
                    int riskScore = result.get("riskScore") instanceof Number n ? n.intValue() : 0;
                    boolean blocked = Boolean.TRUE.equals(result.get("blocked"));
                    String blockReason = (String) result.get("blockReason");

                    if (blocked && !isEncrypted) {
                        log.warn("[{}] AI BLOCKED transfer: {} (risk={}, score={})",
                                trackId, filePath.getFileName(), riskLevel, riskScore);
                        return new ClassificationResult(false, riskLevel, riskScore, blockReason);
                    }
                    log.info("[{}] AI classification: risk={} score={}", trackId, riskLevel, riskScore);
                    return new ClassificationResult(true, riskLevel, riskScore, null);
                }
                return ClassificationResult.ALLOWED;
            });
        } catch (Exception e) {
            log.debug("AI classification unavailable: {} — allowing transfer", e.getMessage());
        }
        return ClassificationResult.ALLOWED;
    }

    /**
     * Get a proxy intelligence verdict for a connection.
     *
     * @param sourceIp the source IP address
     * @param targetPort the target port
     * @param protocol the protocol (SFTP, FTP, etc.)
     * @return verdict map or empty if unavailable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProxyVerdict(String sourceIp, int targetPort, String protocol) {
        try {
            return withResilience("getProxyVerdict",
                    () -> post("/api/v1/proxy/verdict",
                            Map.of("sourceIp", sourceIp, "targetPort", targetPort, "protocol", protocol),
                            Map.class));
        } catch (Exception e) {
            log.debug("AI proxy verdict unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Analyze EDI content using AI. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeEdi(String content) {
        try {
            return withResilience("analyzeEdi",
                    () -> post("/api/edi/analyze", Map.of("content", content), Map.class));
        } catch (Exception e) {
            log.debug("AI EDI analysis unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Get routing optimization suggestion. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> optimizeRouting(Map<String, Object> context) {
        try {
            return withResilience("optimizeRouting",
                    () -> post("/api/intelligence/routing", context, Map.class));
        } catch (Exception e) {
            log.debug("AI routing optimization unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Classification result DTO. */
    public record ClassificationResult(boolean allowed, String riskLevel, int riskScore, String blockReason) {
        public static final ClassificationResult ALLOWED =
                new ClassificationResult(true, "UNKNOWN", 0, null);
    }

    @Override
    protected String healthPath() {
        return "/actuator/health";
    }
}
