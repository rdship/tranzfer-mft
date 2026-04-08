package com.filetransfer.shared.routing;

import com.filetransfer.shared.client.AiEngineClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Client for the AI Engine's classification API.
 * Called by RoutingEngine before routing a file.
 *
 * <p>Delegates to {@link AiEngineClient} for the actual HTTP calls.
 * Kept for backward compatibility — existing code that injects
 * AiClassificationClient continues to work unchanged.
 */
@Component
@Slf4j
public class AiClassificationClient {

    @Value("${ai.classification.enabled:true}")
    private boolean enabled;

    private final AiEngineClient aiEngine;

    public AiClassificationClient(AiEngineClient aiEngine) {
        this.aiEngine = aiEngine;
    }

    /**
     * Classify a file before routing.
     * Returns a decision indicating whether the file is safe to route.
     */
    public ClassificationDecision classify(Path filePath, String trackId, boolean isEncrypted) {
        if (!enabled) {
            return new ClassificationDecision(true, "NONE", 0, null);
        }
        AiEngineClient.ClassificationResult result = aiEngine.classify(filePath, trackId, isEncrypted);
        return new ClassificationDecision(result.allowed(), result.riskLevel(),
                result.riskScore(), result.blockReason());
    }

    public record ClassificationDecision(boolean allowed, String riskLevel, int riskScore, String blockReason) {}
}
