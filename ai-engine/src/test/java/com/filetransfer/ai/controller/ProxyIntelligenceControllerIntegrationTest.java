package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.proxy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProxyIntelligenceController — exercises the full REST API
 * with a real Spring context. Uses a minimal configuration that loads only the
 * proxy intelligence beans (no database, no shared module configs).
 *
 * Tests verify the complete request/response cycle through the controller,
 * service, and in-memory intelligence analyzers.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = ProxyIntelligenceControllerIntegrationTest.TestConfig.class
)
class ProxyIntelligenceControllerIntegrationTest {

    /**
     * Minimal Spring Boot config that loads only the proxy intelligence stack.
     * Explicitly imports only the 6 beans needed — avoids pulling in the full
     * application context (DataClassificationService, ShedLock, OpenAPI, etc.).
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class
    })
    @EnableScheduling
    @Import({
        ProxyIntelligenceController.class,
        ProxyIntelligenceService.class,
        IpReputationService.class,
        ProtocolThreatDetector.class,
        ConnectionPatternAnalyzer.class,
        GeoAnomalyDetector.class,
        LlmSecurityEscalation.class
    })
    static class TestConfig {}

    @Autowired
    private TestRestTemplate restTemplate;

    private HttpHeaders jsonHeaders;
    private HttpHeaders getHeaders;

    @BeforeEach
    void setUp() {
        jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        getHeaders = new HttpHeaders();
    }

    // ── 1. Verdict: new IP returns OK ─────────────────────────────────────

    @Test
    void verdict_newIp_returnsOk() {
        Map<String, Object> request = Map.of(
            "sourceIp", "192.168.10.1",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("action"));
        assertNotNull(body.get("riskScore"));
        assertNotNull(body.get("reason"));
        assertNotNull(body.get("ttlSeconds"));
        assertNotNull(body.get("signals"));

        // New IPs should not be blocked
        String action = (String) body.get("action");
        assertTrue(List.of("ALLOW", "THROTTLE").contains(action),
            "New IP should be ALLOW or THROTTLE, got: " + action);
    }

    // ── 2. Verdict: blocked IP returns BLACKHOLE ──────────────────────────

    @Test
    void verdict_blockedIp_returnsBlackhole() {
        // Block the IP first
        Map<String, String> blockRequest = Map.of("ip", "10.99.0.1", "reason", "test_block");
        restTemplate.exchange(
            "/api/v1/proxy/blocklist",
            HttpMethod.POST,
            new HttpEntity<>(blockRequest, jsonHeaders),
            new ParameterizedTypeReference<Map<String, String>>() {}
        );

        // Now request a verdict
        Map<String, Object> verdictRequest = Map.of(
            "sourceIp", "10.99.0.1",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(verdictRequest, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("BLACKHOLE", body.get("action"));
        assertEquals(100, body.get("riskScore"));
    }

    // ── 3. Verdict: allowlisted IP returns zero risk ──────────────────────

    @Test
    void verdict_allowlistedIp_returnsZeroRisk() {
        // Allowlist the IP first
        Map<String, String> allowRequest = Map.of("ip", "10.1.1.1");
        restTemplate.exchange(
            "/api/v1/proxy/allowlist",
            HttpMethod.POST,
            new HttpEntity<>(allowRequest, jsonHeaders),
            new ParameterizedTypeReference<Map<String, String>>() {}
        );

        // Request verdict
        Map<String, Object> verdictRequest = Map.of(
            "sourceIp", "10.1.1.1",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(verdictRequest, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("ALLOW", body.get("action"));
        assertEquals(0, body.get("riskScore"));
    }

    // ── 4. Verdict: missing sourceIp returns 400 ─────────────────────────

    @Test
    void verdict_missingSourceIp_returns400() {
        Map<String, Object> request = Map.of(
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("error"));
    }

    // ── 5. Event: single connection closed ────────────────────────────────

    @Test
    void event_connectionClosed_returnsAccepted() {
        Map<String, Object> event = Map.of(
            "eventType", "CONNECTION_CLOSED",
            "sourceIp", "10.0.0.5",
            "sourcePort", 54321,
            "targetPort", 22,
            "detectedProtocol", "SSH",
            "bytesIn", 5000,
            "bytesOut", 10000,
            "durationMs", 60000,
            "blocked", false
        );

        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
            "/api/v1/proxy/event",
            HttpMethod.POST,
            new HttpEntity<>(event, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("accepted", response.getBody().get("status"));
    }

    // ── 6. Events: batch returns accepted count ───────────────────────────

    @Test
    void events_batch_returnsAcceptedCount() {
        List<Map<String, Object>> events = List.of(
            Map.of("eventType", "CONNECTION_OPENED", "sourceIp", "10.0.0.6",
                "targetPort", 22, "detectedProtocol", "SSH"),
            Map.of("eventType", "CONNECTION_CLOSED", "sourceIp", "10.0.0.6",
                "sourcePort", 54321, "targetPort", 22,
                "detectedProtocol", "SSH", "bytesIn", 1024,
                "bytesOut", 2048, "durationMs", 5000, "blocked", false),
            Map.of("eventType", "BYTES_TRANSFERRED", "sourceIp", "10.0.0.6",
                "targetPort", 22, "bytesIn", 4096, "bytesOut", 8192)
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/events",
            HttpMethod.POST,
            new HttpEntity<>(events, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(3, body.get("accepted"));
        assertEquals(0, body.get("failed"));
    }

    // ── 7. Blocklist: add, verify, remove ─────────────────────────────────

    @Test
    void blocklist_addAndRemove_works() {
        // Add to blocklist
        Map<String, String> blockRequest = Map.of("ip", "10.50.50.50", "reason", "test");
        ResponseEntity<Map<String, String>> addResponse = restTemplate.exchange(
            "/api/v1/proxy/blocklist",
            HttpMethod.POST,
            new HttpEntity<>(blockRequest, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, addResponse.getStatusCode());
        assertEquals("blocked", addResponse.getBody().get("status"));

        // Verify it's in the blocklist
        ResponseEntity<Map<String, Object>> getResponse = restTemplate.exchange(
            "/api/v1/proxy/blocklist",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        List<String> blockedIps = (List<String>) getResponse.getBody().get("ips");
        assertTrue(blockedIps.contains("10.50.50.50"));

        // Remove from blocklist
        ResponseEntity<Map<String, String>> deleteResponse = restTemplate.exchange(
            "/api/v1/proxy/blocklist/{ip}",
            HttpMethod.DELETE,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {},
            "10.50.50.50"
        );
        assertEquals(HttpStatus.OK, deleteResponse.getStatusCode());
        assertEquals("unblocked", deleteResponse.getBody().get("status"));

        // Verify it's removed
        ResponseEntity<Map<String, Object>> getAfterDelete = restTemplate.exchange(
            "/api/v1/proxy/blocklist",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );
        @SuppressWarnings("unchecked")
        List<String> remainingIps = (List<String>) getAfterDelete.getBody().get("ips");
        assertFalse(remainingIps.contains("10.50.50.50"));
    }

    // ── 8. Health: returns UP ─────────────────────────────────────────────

    @Test
    void health_returnsUp() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/health",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));
        assertEquals("proxy-intelligence", body.get("service"));
        assertNotNull(body.get("features"));
    }

    // ── 9. Allowlist: add and verify via verdict ──────────────────────────

    @Test
    void allowlist_addIp_affectsVerdicts() {
        // Allowlist an IP
        Map<String, String> allowRequest = Map.of("ip", "172.16.0.100");
        restTemplate.exchange(
            "/api/v1/proxy/allowlist",
            HttpMethod.POST,
            new HttpEntity<>(allowRequest, jsonHeaders),
            new ParameterizedTypeReference<Map<String, String>>() {}
        );

        // Verify allowlist GET
        ResponseEntity<Map<String, Object>> getResponse = restTemplate.exchange(
            "/api/v1/proxy/allowlist",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) getResponse.getBody().get("ips");
        assertTrue(allowed.contains("172.16.0.100"));

        // Verdict should be ALLOW with 0 risk
        Map<String, Object> verdictRequest = Map.of(
            "sourceIp", "172.16.0.100",
            "targetPort", 443,
            "detectedProtocol", "TLS"
        );
        ResponseEntity<Map<String, Object>> verdictResponse = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(verdictRequest, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertEquals("ALLOW", verdictResponse.getBody().get("action"));
        assertEquals(0, verdictResponse.getBody().get("riskScore"));
    }

    // ── 10. Dashboard: returns comprehensive data ─────────────────────────

    @Test
    void dashboard_returnsComprehensiveData() {
        // Generate some traffic first
        restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("sourceIp", "10.200.0.1", "targetPort", 22,
                "detectedProtocol", "SSH"), jsonHeaders),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("verdicts"));
        assertNotNull(body.get("ipReputation"));
        assertNotNull(body.get("connectionPatterns"));
        assertNotNull(body.get("geoIntelligence"));
        assertNotNull(body.get("traffic"));
    }

    // ── 11. Recent verdicts audit trail ───────────────────────────────────

    @Test
    void verdicts_returnsAuditTrail() {
        // Generate traffic
        for (int i = 1; i <= 3; i++) {
            restTemplate.exchange(
                "/api/v1/proxy/verdict",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sourceIp", "10.100.0." + i, "targetPort", 22,
                    "detectedProtocol", "SSH"), jsonHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
        }

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "/api/v1/proxy/verdicts?limit=10",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> verdicts = response.getBody();
        assertNotNull(verdicts);
        assertTrue(verdicts.size() >= 3);
    }

    // ── 13. IP intelligence endpoint ──────────────────────────────────────

    @Test
    void ipIntelligence_returnsDetails() {
        // First generate a verdict for the IP so it's tracked
        restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("sourceIp", "10.77.0.1", "targetPort", 22,
                "detectedProtocol", "SSH"), jsonHeaders),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/ip/{ip}",
            HttpMethod.GET,
            new HttpEntity<>(getHeaders),
            new ParameterizedTypeReference<>() {},
            "10.77.0.1"
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("reputation"));
        assertNotNull(body.get("connectionPattern"));
        assertFalse((Boolean) body.get("blocked"));
    }

    // ── 14. Input validation: invalid IP rejected ───────────────────────

    @Test
    void verdict_invalidIpFormat_returns400() {
        Map<String, Object> request = Map.of(
            "sourceIp", "not-an-ip-address",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void verdict_logInjectionInIp_returns400() {
        Map<String, Object> request = Map.of(
            "sourceIp", "10.0.0.1\n[WARN] Fake log entry",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void event_invalidEventType_returns400() {
        Map<String, Object> event = Map.of(
            "eventType", "DROP_TABLES",
            "sourceIp", "10.0.0.5",
            "targetPort", 22
        );

        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
            "/api/v1/proxy/event",
            HttpMethod.POST,
            new HttpEntity<>(event, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void event_oversizedMetadata_returns400() {
        // Build metadata with 60 keys (exceeds limit of 50)
        Map<String, Object> bigMetadata = new java.util.HashMap<>();
        for (int i = 0; i < 60; i++) {
            bigMetadata.put("key" + i, "value" + i);
        }

        Map<String, Object> event = new java.util.HashMap<>();
        event.put("eventType", "CONNECTION_OPENED");
        event.put("sourceIp", "10.0.0.5");
        event.put("targetPort", 22);
        event.put("metadata", bigMetadata);

        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
            "/api/v1/proxy/event",
            HttpMethod.POST,
            new HttpEntity<>(event, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void verdict_validIpv6_returnsOk() {
        Map<String, Object> request = Map.of(
            "sourceIp", "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
            "targetPort", 22,
            "detectedProtocol", "SSH"
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/v1/proxy/verdict",
            HttpMethod.POST,
            new HttpEntity<>(request, jsonHeaders),
            new ParameterizedTypeReference<>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
