package com.filetransfer.forwarder.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the POST /api/forward/test-connection endpoint logic.
 * Uses a real ForwarderController with null dependencies (only the
 * test-connection method is exercised — it doesn't touch repositories
 * or forwarder services).
 */
class ConnectionTestControllerTest {

    private ForwarderController controller;

    @BeforeEach
    void setUp() {
        // test-connection doesn't use any injected services — safe to pass nulls
        controller = new ForwarderController(
                null, null, null,
                null, null, null, null, null, null, null, null
        );
    }

    // --- Validation ---

    @Test
    void testConnection_missingHost_returnsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("port", 22);
        body.put("protocol", "SFTP");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(400, resp.getStatusCode().value());
        assertFalse((Boolean) resp.getBody().get("success"));
        assertTrue(resp.getBody().get("message").toString().contains("Host is required"));
    }

    @Test
    void testConnection_blankHost_returnsBadRequest() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "   ");
        body.put("port", 22);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(400, resp.getStatusCode().value());
        assertFalse((Boolean) resp.getBody().get("success"));
    }

    // --- Unreachable host ---

    @Test
    void testConnection_unreachableHost_returnsFailure() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "192.0.2.1"); // TEST-NET — guaranteed unreachable
        body.put("port", 22);
        body.put("protocol", "SFTP");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, Object> result = resp.getBody();
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("message"));
        assertNotNull(result.get("latencyMs"));
    }

    @Test
    void testConnection_invalidPort_returnsFailure() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "localhost");
        body.put("port", 1); // Almost certainly nothing listening on port 1
        body.put("protocol", "SFTP");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, Object> result = resp.getBody();
        assertNotNull(result);
        // Either connection refused or timeout — both are failures
        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("latencyMs"));
    }

    // --- Response format ---

    @Test
    void testConnection_responseContainsRequiredFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "localhost");
        body.put("port", 1);
        body.put("protocol", "SFTP");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        Map<String, Object> result = resp.getBody();

        assertNotNull(result);
        assertTrue(result.containsKey("success"));
        assertTrue(result.containsKey("message"));
        assertTrue(result.containsKey("latencyMs"));
    }

    // --- HTTP protocol path ---

    @Test
    void testConnection_httpProtocol_unreachable_returnsResultWithRequiredFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "192.0.2.1");
        body.put("port", 80);
        body.put("protocol", "HTTP");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, Object> result = resp.getBody();
        assertNotNull(result);
        // Result should always include success, message, and latencyMs regardless of outcome
        assertTrue(result.containsKey("success"));
        assertTrue(result.containsKey("message"));
        assertTrue(result.containsKey("latencyMs"));
        // Message should mention HTTP in either success or failure path
        assertTrue(result.get("message").toString().contains("HTTP"));
    }

    @Test
    void testConnection_httpsProtocol_unreachable_returnsFailure() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "192.0.2.1");
        body.put("port", 443);
        body.put("protocol", "HTTPS");

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());
        assertFalse((Boolean) resp.getBody().get("success"));
    }

    // --- DMZ proxy unreachable ---

    @Test
    void testConnection_dmzProxy_unreachable_returnsProxyError() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "sftp.partner.com");
        body.put("port", 22);
        body.put("protocol", "SFTP");
        body.put("proxyEnabled", true);
        body.put("proxyType", "DMZ");
        body.put("proxyHost", "192.0.2.1");
        body.put("proxyPort", 8088);

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, Object> result = resp.getBody();
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"));
        assertTrue(result.get("message").toString().contains("DMZ Proxy unreachable"));
    }

    // --- Non-DMZ proxy falls through to direct connection ---

    @Test
    void testConnection_nonDmzProxy_skipsProxyRegistration() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "localhost");
        body.put("port", 1);
        body.put("protocol", "SFTP");
        body.put("proxyEnabled", true);
        body.put("proxyType", "HTTP"); // Not DMZ — no mapping registration needed

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, Object> result = resp.getBody();
        assertNotNull(result);
        // Should attempt direct connection (no DMZ registration)
        assertTrue(result.containsKey("success"));
        assertEquals(true, result.get("proxyUsed"));
        assertEquals("HTTP", result.get("proxyType"));
    }

    // --- Default values ---

    @Test
    void testConnection_defaultProtocolIsSftp() {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "localhost");
        body.put("port", 1);
        // No protocol specified — should default to SFTP (socket test)

        ResponseEntity<Map<String, Object>> resp = controller.testConnection(body);
        assertEquals(200, resp.getStatusCode().value());
        // Should not fail with NPE or bad protocol — just a connection failure
        assertFalse((Boolean) resp.getBody().get("success"));
    }
}
