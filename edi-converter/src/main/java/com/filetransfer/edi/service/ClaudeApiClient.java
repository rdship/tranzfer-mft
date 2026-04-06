package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared Claude API client for EDI converter AI features.
 *
 * Uses HttpURLConnection (same pattern as ai-engine's NlpService) to avoid
 * adding new dependencies. When no API key is configured, {@link #isAvailable()}
 * returns false and callers should fall back to regex/pattern-matching logic.
 */
@Service
@Slf4j
public class ClaudeApiClient {

    @Value("${ai.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${ai.claude.api-key:}")
    private String apiKey;

    @Value("${ai.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${ai.claude.base-url:https://api.anthropic.com}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    // Pattern to extract JSON from markdown code blocks
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    /**
     * Returns true when an API key is configured and the client can make calls.
     */
    public boolean isAvailable() {
        return llmEnabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Call Claude with a system prompt and user message, returning the raw text response.
     *
     * @param systemPrompt the system-level instruction
     * @param userMessage  the user-level content
     * @return Claude's text response
     * @throws Exception on network or API errors
     */
    public String call(String systemPrompt, String userMessage) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Claude API key not configured");
        }

        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/v1/messages").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 2048,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(body));
        }

        if (conn.getResponseCode() != 200) {
            String error = conn.getErrorStream() != null
                    ? new String(conn.getErrorStream().readAllBytes())
                    : "HTTP " + conn.getResponseCode();
            throw new RuntimeException("Claude API error " + conn.getResponseCode() + ": " + error);
        }

        JsonNode resp = mapper.readTree(conn.getInputStream());
        return resp.get("content").get(0).get("text").asText();
    }

    /**
     * Call Claude and parse the response as JSON into the given type.
     * Handles markdown code-block wrapping automatically.
     *
     * @param systemPrompt the system-level instruction
     * @param userMessage  the user-level content
     * @param responseType the target class for deserialization
     * @return deserialized response object
     * @throws Exception on network, API, or parse errors
     */
    public <T> T callJson(String systemPrompt, String userMessage, Class<T> responseType) throws Exception {
        String text = call(systemPrompt, userMessage);
        String json = extractJson(text);
        return mapper.readValue(json, responseType);
    }

    /**
     * Extract JSON content from a Claude response that may include markdown code blocks
     * or prose surrounding the JSON. Looks for code-block first, then raw JSON delimiters.
     */
    static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Try markdown code block first
        Matcher m = JSON_BLOCK_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }

        // Fall back to finding first { or [ to last } or ]
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');

        if (objStart < 0 && arrStart < 0) {
            return text.trim();
        }

        // Choose whichever delimiter comes first
        boolean isArray;
        int start;
        if (objStart < 0) {
            isArray = true;
            start = arrStart;
        } else if (arrStart < 0) {
            isArray = false;
            start = objStart;
        } else {
            isArray = arrStart < objStart;
            start = Math.min(objStart, arrStart);
        }

        char open = isArray ? '[' : '{';
        char close = isArray ? ']' : '}';
        int end = text.lastIndexOf(close);

        if (end > start) {
            return text.substring(start, end + 1);
        }

        return text.trim();
    }
}
