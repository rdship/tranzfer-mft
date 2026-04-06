package com.filetransfer.ai.service.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * LLM Security Escalation — calls Claude LLM for borderline security verdicts.
 *
 * Only invoked when:
 *   1. Security tier is AI_LLM
 *   2. Risk score is in the borderline range (30-70)
 *   3. LLM is enabled AND API key is configured
 *
 * Hard 2-second timeout on all LLM calls — proxy cannot wait longer.
 * On any failure (timeout, API error, parse error), returns empty and
 * the caller falls through to the existing rule-based verdict.
 */
@Slf4j
@Service
public class LlmSecurityEscalation {

    @Value("${ai.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${ai.claude.api-key:}")
    private String apiKey;

    @Value("${ai.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${ai.claude.base-url:https://api.anthropic.com}")
    private String baseUrl;

    public record LlmVerdictResult(String action, int confidence, String reasoning, long latencyMs) {}

    /**
     * Evaluate a borderline connection using Claude LLM.
     * Only called when security tier is AI_LLM and risk score is 30-70.
     * Returns empty if LLM is disabled, API key missing, or call fails/times out.
     */
    public Optional<LlmVerdictResult> evaluate(
            String sourceIp, int targetPort, String protocol,
            int currentRiskScore, List<String> signals,
            Map<String, Object> metadata) {

        if (!llmEnabled || apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        long startMs = System.currentTimeMillis();
        try {
            String systemPrompt = """
                You are an MFT (Managed File Transfer) security analyst evaluating a network connection.
                Analyze the provided connection metadata and risk signals to determine the appropriate action.

                Actions:
                - ALLOW: Connection appears legitimate, low risk
                - THROTTLE: Suspicious but not clearly malicious, apply rate limits
                - BLOCK: High confidence of malicious intent

                Consider:
                - False positives are costly (blocking legitimate partners disrupts business)
                - False negatives are dangerous (allowing attackers compromises data)
                - Borderline cases (risk 30-70) need careful signal correlation

                Respond ONLY with JSON: {"action": "ALLOW|THROTTLE|BLOCK", "confidence": 0-100, "reasoning": "brief explanation"}
                """;

            String userMessage = String.format(
                "Connection: sourceIp=%s, port=%d, protocol=%s\n" +
                "Current risk score: %d/100\n" +
                "Signals: %s\n" +
                "Metadata: %s\n\n" +
                "What is your verdict?",
                sourceIp, targetPort, protocol != null ? protocol : "TCP",
                currentRiskScore, signals, metadata);

            String requestBody = String.format("""
                {"model": "%s", "max_tokens": 200, "messages": [
                    {"role": "user", "content": "%s"}
                ], "system": "%s"}""",
                model,
                escapeJson(userMessage),
                escapeJson(systemPrompt));

            HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/v1/messages").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                long latencyMs = System.currentTimeMillis() - startMs;
                return parseResponse(response, latencyMs);
            } else {
                log.warn("LLM security escalation failed: HTTP {}", conn.getResponseCode());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("LLM security escalation error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isAvailable() {
        return llmEnabled && apiKey != null && !apiKey.isBlank();
    }

    private Optional<LlmVerdictResult> parseResponse(String response, long latencyMs) {
        try {
            // Extract text content from Claude response
            int textStart = response.indexOf("\"text\"");
            if (textStart < 0) return Optional.empty();
            int contentStart = response.indexOf("\"", textStart + 7) + 1;
            int contentEnd = response.indexOf("\"", contentStart);
            // Handle escaped quotes within the content
            while (contentEnd > 0 && response.charAt(contentEnd - 1) == '\\') {
                contentEnd = response.indexOf("\"", contentEnd + 1);
            }
            String text = response.substring(contentStart, contentEnd)
                .replace("\\n", "\n").replace("\\\"", "\"");

            // Extract JSON from text
            int jsonStart = text.indexOf("{");
            int jsonEnd = text.lastIndexOf("}");
            if (jsonStart < 0 || jsonEnd < 0) return Optional.empty();
            String json = text.substring(jsonStart, jsonEnd + 1);

            // Simple JSON parsing for action, confidence, reasoning
            String action = extractJsonString(json, "action");
            int confidence = extractJsonInt(json, "confidence", 50);
            String reasoning = extractJsonString(json, "reasoning");

            if (action == null || (!action.equals("ALLOW") && !action.equals("THROTTLE") && !action.equals("BLOCK"))) {
                return Optional.empty();
            }

            log.info("LLM security verdict: action={}, confidence={}, latency={}ms, reasoning={}",
                action, confidence, latencyMs, reasoning);
            return Optional.of(new LlmVerdictResult(action, confidence, reasoning, latencyMs));
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractJsonString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int valStart = json.indexOf("\"", idx + key.length() + 3) + 1;
        int valEnd = json.indexOf("\"", valStart);
        while (valEnd > 0 && json.charAt(valEnd - 1) == '\\') {
            valEnd = json.indexOf("\"", valEnd + 1);
        }
        return valEnd > valStart ? json.substring(valStart, valEnd) : null;
    }

    private int extractJsonInt(String json, String key, int defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int colonIdx = json.indexOf(":", idx);
        if (colonIdx < 0) return defaultVal;
        StringBuilder num = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (!num.isEmpty()) break;
        }
        return num.isEmpty() ? defaultVal : Integer.parseInt(num.toString());
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
