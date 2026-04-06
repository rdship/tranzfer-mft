package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.proxy.LlmSecurityEscalation.LlmVerdictResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LlmSecurityEscalationTest {

    private LlmSecurityEscalation service;

    @BeforeEach
    void setUp() throws Exception {
        service = new LlmSecurityEscalation();
    }

    // ---- isAvailable tests ----

    @Test
    void isAvailableReturnsFalseWhenLlmDisabled() throws Exception {
        setField("llmEnabled", false);
        setField("apiKey", "sk-test-key");

        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailableReturnsFalseWhenApiKeyBlank() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "");

        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailableReturnsFalseWhenApiKeyNull() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", null);

        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailableReturnsTrueWhenEnabledAndApiKeySet() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "sk-test-key");

        assertTrue(service.isAvailable());
    }

    // ---- evaluate tests ----

    @Test
    void evaluateReturnsEmptyWhenDisabled() throws Exception {
        setField("llmEnabled", false);
        setField("apiKey", "sk-test-key");

        Optional<LlmVerdictResult> result = service.evaluate(
                "10.0.0.1", 22, "SSH", 50,
                List.of("NEW_IP"), Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void evaluateReturnsEmptyWhenApiKeyBlank() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "   ");

        Optional<LlmVerdictResult> result = service.evaluate(
                "10.0.0.1", 22, "SSH", 50,
                List.of("NEW_IP"), Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void evaluateHandlesConnectionTimeoutGracefully() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "sk-test-key");
        setField("model", "claude-sonnet-4-20250514");
        // Point to a non-routable IP to force a connection timeout
        setField("baseUrl", "http://192.0.2.1");

        Optional<LlmVerdictResult> result = service.evaluate(
                "10.0.0.1", 22, "SSH", 50,
                List.of("NEW_IP", "RAPID_RECONNECT"),
                Map.of("country", "US"));

        assertTrue(result.isEmpty(), "Should return empty on connection timeout");
    }

    // ---- escapeJson tests ----

    @Test
    void escapeJsonHandlesBackslash() throws Exception {
        String input = "path\\to\\file";
        String escaped = invokeEscapeJson(input);
        assertEquals("path\\\\to\\\\file", escaped);
    }

    @Test
    void escapeJsonHandlesDoubleQuotes() throws Exception {
        String input = "say \"hello\"";
        String escaped = invokeEscapeJson(input);
        assertEquals("say \\\"hello\\\"", escaped);
    }

    @Test
    void escapeJsonHandlesNewlines() throws Exception {
        String input = "line1\nline2\rline3";
        String escaped = invokeEscapeJson(input);
        assertEquals("line1\\nline2\\rline3", escaped);
    }

    @Test
    void escapeJsonHandlesPlainText() throws Exception {
        String input = "simple text";
        String escaped = invokeEscapeJson(input);
        assertEquals("simple text", escaped);
    }

    @Test
    void escapeJsonHandlesCombinedSpecialChars() throws Exception {
        String input = "key: \"val\\ue\"\nnext";
        String escaped = invokeEscapeJson(input);
        assertEquals("key: \\\"val\\\\ue\\\"\\nnext", escaped);
    }

    // ---- additional evaluate tests ----

    @Test
    void evaluateNullSourceIpReturnsEmpty() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "sk-test-key");
        setField("model", "claude-sonnet-4-20250514");
        // Use an invalid URL so we never reach a real API
        setField("baseUrl", "http://localhost:1");

        Optional<LlmVerdictResult> result = service.evaluate(
                null, 22, "SFTP", 55,
                List.of("UNUSUAL_TIME"), Map.of());

        assertTrue(result.isEmpty(), "Null sourceIp should be handled gracefully and return empty");
    }

    @Test
    void evaluateInvalidUrlReturnsEmpty() throws Exception {
        setField("llmEnabled", true);
        setField("apiKey", "sk-test-key");
        setField("model", "claude-sonnet-4-20250514");
        setField("baseUrl", "http://localhost:99999");

        Optional<LlmVerdictResult> result = service.evaluate(
                "10.0.0.1", 22, "SSH", 45,
                List.of("GEO_MISMATCH"),
                Map.of("country", "XX"));

        assertTrue(result.isEmpty(), "Invalid port in URL should cause connection failure and return empty");
    }

    // ---- helpers ----

    private void setField(String fieldName, Object value) throws Exception {
        Field f = LlmSecurityEscalation.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(service, value);
    }

    private String invokeEscapeJson(String input) throws Exception {
        Method m = LlmSecurityEscalation.class.getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, input);
    }
}
