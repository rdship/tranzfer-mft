package com.filetransfer.edi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeApiClient.
 * All tests run without an API key, exercising availability checks and JSON extraction.
 * The actual Claude HTTP calls are integration tests requiring a real key.
 */
class ClaudeApiClientTest {

    private ClaudeApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new ClaudeApiClient();
        // Default state: LLM disabled, no API key configured (mirrors @Value defaults)
        setField(client, "llmEnabled", false);
        setField(client, "apiKey", "");
        setField(client, "model", "claude-sonnet-4-20250514");
        setField(client, "baseUrl", "https://api.anthropic.com");
    }

    // ========================================================================
    // isAvailable()
    // ========================================================================

    @Test
    void isAvailable_returnsFalse_whenApiKeyEmpty() {
        assertFalse(client.isAvailable());
    }

    @Test
    void isAvailable_returnsFalse_whenApiKeyNull() throws Exception {
        setField(client, "apiKey", null);
        assertFalse(client.isAvailable());
    }

    @Test
    void isAvailable_returnsFalse_whenApiKeyBlank() throws Exception {
        setField(client, "apiKey", "   ");
        assertFalse(client.isAvailable());
    }

    @Test
    void isAvailable_returnsFalse_whenApiKeySetButLlmDisabled() throws Exception {
        setField(client, "apiKey", "sk-ant-test-key");
        // llmEnabled defaults to false in setUp — should still be unavailable
        assertFalse(client.isAvailable());
    }

    @Test
    void isAvailable_returnsTrue_whenApiKeySetAndLlmEnabled() throws Exception {
        setField(client, "llmEnabled", true);
        setField(client, "apiKey", "sk-ant-test-key");
        assertTrue(client.isAvailable());
    }

    // ========================================================================
    // call() without API key
    // ========================================================================

    @Test
    void call_throwsIllegalState_whenNoApiKey() {
        assertThrows(IllegalStateException.class,
                () -> client.call("system", "user message"));
    }

    @Test
    void call_throwsIllegalState_whenApiKeyBlank() throws Exception {
        setField(client, "apiKey", "  ");
        assertThrows(IllegalStateException.class,
                () -> client.call("system", "user message"));
    }

    // ========================================================================
    // extractJson() — static utility
    // ========================================================================

    @Test
    void extractJson_fromMarkdownCodeBlock() {
        String input = """
                Here is the result:
                ```json
                [{"field": "value"}]
                ```
                Hope that helps!""";
        assertEquals("[{\"field\": \"value\"}]", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_fromCodeBlockWithoutLanguage() {
        String input = """
                ```
                {"key": "val"}
                ```""";
        assertEquals("{\"key\": \"val\"}", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_fromRawJsonObject() {
        String input = "Some text before {\"a\": 1, \"b\": 2} and after";
        assertEquals("{\"a\": 1, \"b\": 2}", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_fromRawJsonArray() {
        String input = "Here: [{\"x\": 1}, {\"x\": 2}] done";
        assertEquals("[{\"x\": 1}, {\"x\": 2}]", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_prefersCodeBlock_overRawBraces() {
        String input = """
                This {junk} is not the answer.
                ```json
                {"real": "data"}
                ```
                More {junk}.
                """;
        assertEquals("{\"real\": \"data\"}", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_handlesNullInput() {
        assertNull(ClaudeApiClient.extractJson(null));
    }

    @Test
    void extractJson_handlesBlankInput() {
        // Blank input returns trimmed result (empty string)
        String result = ClaudeApiClient.extractJson("   ");
        assertNotNull(result);
        assertTrue(result.isBlank(), "Blank input should return blank/empty result");
    }

    @Test
    void extractJson_handlesNoJsonContent() {
        String input = "This is just plain text with no JSON";
        assertEquals("This is just plain text with no JSON", ClaudeApiClient.extractJson(input));
    }

    @Test
    void extractJson_handlesNestedBraces() {
        String input = "Result: {\"outer\": {\"inner\": [1,2,3]}} end";
        String extracted = ClaudeApiClient.extractJson(input);
        assertEquals("{\"outer\": {\"inner\": [1,2,3]}}", extracted);
    }

    @Test
    void extractJson_choosesArrayWhenItComesFirst() {
        String input = "[{\"a\":1}] then {\"b\":2}";
        String extracted = ClaudeApiClient.extractJson(input);
        // Array bracket comes first, so we get from [ to last ]
        assertTrue(extracted.startsWith("["));
    }

    @Test
    void extractJson_multilineCodeBlock() {
        String input = """
                ```json
                [
                  {"sourceField": "ISA*06", "targetField": "sender", "confidence": 95},
                  {"sourceField": "BEG*03", "targetField": "poNumber", "confidence": 90}
                ]
                ```""";
        String extracted = ClaudeApiClient.extractJson(input);
        assertTrue(extracted.contains("ISA*06"));
        assertTrue(extracted.contains("BEG*03"));
        assertTrue(extracted.startsWith("["));
        assertTrue(extracted.endsWith("]"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
