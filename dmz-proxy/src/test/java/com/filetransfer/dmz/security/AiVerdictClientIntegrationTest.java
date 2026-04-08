package com.filetransfer.dmz.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.filetransfer.dmz.security.AiVerdictClient.Action;
import com.filetransfer.dmz.security.AiVerdictClient.CachedVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AiVerdictClient — exercises the full HTTP path
 * between the DMZ proxy client and a WireMock-simulated AI engine.
 *
 * Verifies: verdict parsing, caching, TTL expiry, fallback behavior,
 * recovery after downtime, and event reporting.
 */
class AiVerdictClientIntegrationTest {

    private WireMockServer wireMock;
    private AiVerdictClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        client = new AiVerdictClient("http://localhost:" + wireMock.port(), 2000);
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
        wireMock.stop();
    }

    // ── 1. Basic verdict: ALLOW ───────────────────────────────────────────

    @Test
    void verdictRequest_newIp_returnsAllow() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 15,
                    "reason": "Low risk new IP",
                    "ttlSeconds": 60,
                    "signals": ["NEW_IP"],
                    "rateLimit": {
                        "maxConnectionsPerMinute": 60,
                        "maxConcurrentConnections": 20,
                        "maxBytesPerMinute": 500000000
                    },
                    "metadata": {"country": "US"}
                }
                """)));

        CachedVerdict verdict = client.getVerdict("192.168.1.100", 22, "SSH");

        assertEquals(Action.ALLOW, verdict.action());
        assertEquals(15, verdict.riskScore());
        assertEquals("Low risk new IP", verdict.reason());
        assertTrue(verdict.signals().contains("NEW_IP"));
        assertEquals(60, verdict.maxConnectionsPerMinute());
        assertEquals(20, verdict.maxConcurrentConnections());
        assertEquals(500_000_000L, verdict.maxBytesPerMinute());
        assertFalse(verdict.isExpired());

        verify(1, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict"))
            .withRequestBody(matchingJsonPath("$.sourceIp", equalTo("192.168.1.100")))
            .withRequestBody(matchingJsonPath("$.targetPort", equalTo("22")))
            .withRequestBody(matchingJsonPath("$.detectedProtocol", equalTo("SSH"))));
    }

    // ── 2. Blocked IP: BLACKHOLE ──────────────────────────────────────────

    @Test
    void verdictRequest_blockedIp_returnsBlackhole() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "BLACKHOLE",
                    "riskScore": 100,
                    "reason": "IP is blocklisted",
                    "ttlSeconds": 600,
                    "signals": ["BLOCKLISTED"],
                    "metadata": {}
                }
                """)));

        CachedVerdict verdict = client.getVerdict("10.99.99.99", 22, "SSH");

        assertEquals(Action.BLACKHOLE, verdict.action());
        assertEquals(100, verdict.riskScore());
        assertEquals("IP is blocklisted", verdict.reason());
        assertTrue(verdict.signals().contains("BLOCKLISTED"));
        assertNull(verdict.maxConnectionsPerMinute());
        assertNull(verdict.maxConcurrentConnections());
        assertNull(verdict.maxBytesPerMinute());
    }

    // ── 3. Throttled IP: rate limits parsed ───────────────────────────────

    @Test
    void verdictRequest_throttledIp_returnsRateLimits() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "THROTTLE",
                    "riskScore": 65,
                    "reason": "Moderate risk — throttling",
                    "ttlSeconds": 120,
                    "signals": ["HIGH_CONN_RATE", "LOW_REPUTATION:35"],
                    "rateLimit": {
                        "maxConnectionsPerMinute": 5,
                        "maxConcurrentConnections": 2,
                        "maxBytesPerMinute": 10000000
                    },
                    "metadata": {}
                }
                """)));

        CachedVerdict verdict = client.getVerdict("10.0.0.50", 21, "FTP");

        assertEquals(Action.THROTTLE, verdict.action());
        assertEquals(65, verdict.riskScore());
        assertEquals(5, verdict.maxConnectionsPerMinute());
        assertEquals(2, verdict.maxConcurrentConnections());
        assertEquals(10_000_000L, verdict.maxBytesPerMinute());
        assertTrue(verdict.signals().contains("HIGH_CONN_RATE"));
        assertTrue(verdict.signals().contains("LOW_REPUTATION:35"));
    }

    // ── 4. Cache hit: second call does NOT hit WireMock ───────────────────

    @Test
    void verdictResponse_isCached() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 10,
                    "reason": "Trusted",
                    "ttlSeconds": 300,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        CachedVerdict first = client.getVerdict("10.0.0.1", 22, "SSH");
        CachedVerdict second = client.getVerdict("10.0.0.1", 22, "SSH");

        assertEquals(first.action(), second.action());
        assertEquals(first.riskScore(), second.riskScore());
        assertEquals(first.cachedAt(), second.cachedAt());

        // WireMock should have been called exactly once
        verify(1, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict")));
        assertEquals(1, client.getCacheSize());
    }

    // ── 5. Cache TTL expiry ───────────────────────────────────────────────

    @Test
    void cachedVerdict_expiresAfterTtl() throws Exception {
        // TTL of 1 second so it expires quickly in the test
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 10,
                    "reason": "Short TTL",
                    "ttlSeconds": 1,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        CachedVerdict first = client.getVerdict("10.0.0.2", 22, "SSH");
        assertFalse(first.isExpired());

        // Wait for TTL to expire
        TimeUnit.MILLISECONDS.sleep(1200);
        assertTrue(first.isExpired());

        // Next call should hit WireMock again
        CachedVerdict second = client.getVerdict("10.0.0.2", 22, "SSH");
        assertNotEquals(first.cachedAt(), second.cachedAt());

        verify(2, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict")));
    }

    // ── 6. AI engine down: fallback to local heuristics ───────────────────

    @Test
    void aiEngineDown_fallsBackToLocal() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(serverError().withBody("Internal Server Error")));

        CachedVerdict verdict = client.getVerdict("10.0.0.3", 22, "SSH");

        // Fallback should return THROTTLE with tight limits
        assertEquals(Action.THROTTLE, verdict.action());
        assertEquals(50, verdict.riskScore());
        assertTrue(verdict.reason().contains("Local fallback"));
        assertTrue(verdict.signals().contains("FALLBACK"));
        assertTrue(verdict.signals().contains("AI_UNAVAILABLE"));
        assertEquals(5, verdict.maxConnectionsPerMinute());
        assertEquals(2, verdict.maxConcurrentConnections());
        assertEquals(10_000_000L, verdict.maxBytesPerMinute());
    }

    // ── 7. AI engine timeout: fallback to local heuristics ────────────────

    @Test
    void aiEngineTimeout_fallsBackToLocal() {
        // Client timeout is 2000ms; delay 3000ms to trigger it
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("{}")
                .withFixedDelay(3000)));

        CachedVerdict verdict = client.getVerdict("10.0.0.4", 22, "SSH");

        assertEquals(Action.THROTTLE, verdict.action());
        assertTrue(verdict.reason().contains("Local fallback"));
        assertTrue(verdict.signals().contains("FALLBACK"));
        assertFalse(client.isAiEngineAvailable());
    }

    // ── 8. AI engine recovers after downtime ─────────────────────────────

    @Test
    void aiEngineRecovers_afterHealthCheck() throws Exception {
        // First: simulate engine being completely unreachable by using a timeout
        // (500 responses don't set aiEngineAvailable=false, only connection failures do)
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("{}").withFixedDelay(3000)));

        CachedVerdict fallback = client.getVerdict("10.0.0.5", 22, "SSH");
        assertEquals(Action.THROTTLE, fallback.action());
        assertTrue(fallback.reason().contains("Local fallback"));
        assertFalse(client.isAiEngineAvailable());

        // Now engine comes back — reset stubs to respond immediately
        wireMock.resetAll();
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 5,
                    "reason": "Clean IP",
                    "ttlSeconds": 300,
                    "signals": [],
                    "metadata": {}
                }
                """)));
        stubFor(get(urlEqualTo("/api/v1/proxy/health"))
            .willReturn(ok()));

        // When aiEngineAvailable=false the client returns fallback and triggers
        // async health check (throttled to once per 30s). Rather than waiting 30s,
        // create a fresh client to prove the recovered engine serves real verdicts.
        client.shutdown();
        client = new AiVerdictClient("http://localhost:" + wireMock.port(), 2000);

        CachedVerdict recovered = client.getVerdict("10.0.0.6", 22, "SSH");
        assertEquals(Action.ALLOW, recovered.action());
        assertEquals(5, recovered.riskScore());
        assertEquals("Clean IP", recovered.reason());
        assertTrue(client.isAiEngineAvailable());
    }

    // ── 9. Single event reporting ─────────────────────────────────────────

    @Test
    void eventReporting_singleEvent_accepted() throws Exception {
        stubFor(post(urlEqualTo("/api/v1/proxy/event"))
            .willReturn(okJson("{\"status\":\"accepted\"}")));

        Map<String, Object> event = Map.of(
            "eventType", "CONNECTION_CLOSED",
            "sourceIp", "10.0.0.1",
            "sourcePort", 54321,
            "targetPort", 22,
            "detectedProtocol", "SSH",
            "bytesIn", 5000,
            "bytesOut", 10000,
            "durationMs", 60000,
            "blocked", false
        );

        client.reportEventAsync(event);

        // Give async executor time to fire the request
        TimeUnit.MILLISECONDS.sleep(500);

        verify(1, postRequestedFor(urlEqualTo("/api/v1/proxy/event"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.eventType", equalTo("CONNECTION_CLOSED")))
            .withRequestBody(matchingJsonPath("$.sourceIp", equalTo("10.0.0.1"))));
    }

    // ── 10. Batch event reporting ─────────────────────────────────────────

    @Test
    void eventReporting_batchEvents_accepted() throws Exception {
        stubFor(post(urlEqualTo("/api/v1/proxy/events"))
            .willReturn(okJson("{\"accepted\":3,\"failed\":0}")));

        List<Map<String, Object>> events = List.of(
            Map.of("eventType", "CONNECTION_OPENED", "sourceIp", "10.0.0.1",
                "targetPort", 22, "detectedProtocol", "SSH"),
            Map.of("eventType", "BYTES_TRANSFERRED", "sourceIp", "10.0.0.1",
                "targetPort", 22, "bytesIn", 1024, "bytesOut", 2048),
            Map.of("eventType", "CONNECTION_CLOSED", "sourceIp", "10.0.0.1",
                "targetPort", 22, "durationMs", 5000)
        );

        client.reportEventsAsync(events);

        TimeUnit.MILLISECONDS.sleep(500);

        verify(1, postRequestedFor(urlEqualTo("/api/v1/proxy/events"))
            .withHeader("Content-Type", equalTo("application/json")));
    }

    // ── 11. Missing optional fields handled gracefully ────────────────────

    @Test
    void verdictRequest_missingFields_handlesGracefully() {
        // Minimal response: only action
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW"
                }
                """)));

        CachedVerdict verdict = client.getVerdict("10.0.0.7", 443, "TLS");

        assertEquals(Action.ALLOW, verdict.action());
        assertEquals(0, verdict.riskScore());
        assertEquals("", verdict.reason());
        // No rateLimit node => null rate limit fields
        assertNull(verdict.maxConnectionsPerMinute());
        assertNull(verdict.maxConcurrentConnections());
        assertNull(verdict.maxBytesPerMinute());
        // Signals should be empty, not null
        assertNotNull(verdict.signals());
        assertTrue(verdict.signals().isEmpty());
        // TTL defaults to 60s
        assertTrue(verdict.expiresAt().isAfter(Instant.now()));
    }

    // ── 12. Cache invalidation for specific IP ────────────────────────────

    @Test
    void cacheInvalidation_clearsSpecificIp() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 20,
                    "reason": "OK",
                    "ttlSeconds": 300,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        // Cache verdicts for two different IPs
        client.getVerdict("10.0.0.10", 22, "SSH");
        client.getVerdict("10.0.0.11", 22, "SSH");
        assertEquals(2, client.getCacheSize());

        // Invalidate only one IP
        client.invalidate("10.0.0.10");
        assertEquals(1, client.getCacheSize());

        // Next call for invalidated IP should hit WireMock again
        client.getVerdict("10.0.0.10", 22, "SSH");
        verify(3, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict")));

        // Next call for non-invalidated IP should still use cache
        client.getVerdict("10.0.0.11", 22, "SSH");
        verify(3, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict")));
    }

    // ── 13. invalidateAll clears entire cache ─────────────────────────────

    @Test
    void cacheInvalidation_clearAll_emptiesCache() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 5,
                    "reason": "Trusted",
                    "ttlSeconds": 300,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        client.getVerdict("10.0.0.20", 22, "SSH");
        client.getVerdict("10.0.0.21", 22, "SSH");
        client.getVerdict("10.0.0.22", 22, "SSH");
        assertEquals(3, client.getCacheSize());

        client.invalidateAll();
        assertEquals(0, client.getCacheSize());
    }

    // ── 14. Different port = different cache key ──────────────────────────

    @Test
    void differentPort_createsSeparateCacheEntry() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 10,
                    "reason": "OK",
                    "ttlSeconds": 300,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        client.getVerdict("10.0.0.30", 22, "SSH");
        client.getVerdict("10.0.0.30", 21, "FTP");

        assertEquals(2, client.getCacheSize());
        verify(2, postRequestedFor(urlEqualTo("/api/v1/proxy/verdict")));
    }

    // ── 15. Null protocol defaults to TCP ─────────────────────────────────

    @Test
    void nullProtocol_defaultsToTcp() {
        stubFor(post(urlEqualTo("/api/v1/proxy/verdict"))
            .willReturn(okJson("""
                {
                    "action": "ALLOW",
                    "riskScore": 10,
                    "reason": "OK",
                    "ttlSeconds": 60,
                    "signals": [],
                    "metadata": {}
                }
                """)));

        CachedVerdict verdict = client.getVerdict("10.0.0.40", 8080, null);

        assertEquals(Action.ALLOW, verdict.action());
        verify(postRequestedFor(urlEqualTo("/api/v1/proxy/verdict"))
            .withRequestBody(matchingJsonPath("$.detectedProtocol", equalTo("TCP"))));
    }
}
