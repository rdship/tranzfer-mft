package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyIntelligenceServiceTest {

    private ProxyIntelligenceService service;
    private IpReputationService reputationService;
    private ProtocolThreatDetector threatDetector;
    private ConnectionPatternAnalyzer patternAnalyzer;
    private GeoAnomalyDetector geoDetector;

    @BeforeEach
    void setUp() {
        reputationService = new IpReputationService();
        threatDetector = new ProtocolThreatDetector();
        patternAnalyzer = new ConnectionPatternAnalyzer();
        geoDetector = new GeoAnomalyDetector();
        service = new ProxyIntelligenceService(
            reputationService, threatDetector, patternAnalyzer, geoDetector,
            null, null, null, null, null, null);
    }

    @Test
    void newIpGetsAllowedWithModerateRisk() {
        Verdict verdict = service.computeVerdict("10.0.0.1", 22, "SSH");
        assertEquals(Action.ALLOW, verdict.action());
        assertTrue(verdict.riskScore() < 85); // not blocked
        assertTrue(verdict.signals().contains("NEW_IP"));
    }

    @Test
    void blockedIpGetsBlackholed() {
        reputationService.blockIp("10.0.0.1", "manual");
        Verdict verdict = service.computeVerdict("10.0.0.1", 22, "SSH");
        assertEquals(Action.BLACKHOLE, verdict.action());
        assertEquals(100, verdict.riskScore());
    }

    @Test
    void allowlistedIpGetsAllowed() {
        reputationService.allowIp("10.0.0.1");
        Verdict verdict = service.computeVerdict("10.0.0.1", 22, "SSH");
        assertEquals(Action.ALLOW, verdict.action());
        assertEquals(0, verdict.riskScore());
    }

    @Test
    void repeatedGoodBehaviorImprovesVerdict() {
        // First connection: new IP
        service.computeVerdict("10.0.0.1", 22, "SSH");
        int firstRisk = service.computeVerdict("10.0.0.1", 22, "SSH").riskScore();

        // Simulate good behavior
        for (int i = 0; i < 20; i++) {
            reputationService.recordSuccess("10.0.0.1");
        }

        int afterGoodBehavior = service.computeVerdict("10.0.0.1", 22, "SSH").riskScore();
        assertTrue(afterGoodBehavior <= firstRisk);
    }

    @Test
    void processEventConnectionClosed() {
        ThreatEvent event = new ThreatEvent(
            "CONNECTION_CLOSED", "10.0.0.1", 54321, 22,
            "SSH", 5000, 10000, 60000,
            false, null, null, "US", Map.of());

        assertDoesNotThrow(() -> service.processEvent(event));
        // Should have recorded success (clean connection)
        assertTrue(reputationService.getScore("10.0.0.1") >= 50.0);
    }

    @Test
    void processEventAuthFailureReducesReputation() {
        service.computeVerdict("10.0.0.1", 22, "SSH");
        double before = reputationService.getScore("10.0.0.1");

        ThreatEvent event = new ThreatEvent(
            "AUTH_FAILURE", "10.0.0.1", 54321, 22,
            "SSH", 0, 0, 0,
            false, null, "admin", null, Map.of());

        service.processEvent(event);
        assertTrue(reputationService.getScore("10.0.0.1") < before);
    }

    @Test
    void processEventBruteForceAutoBlocks() {
        service.computeVerdict("10.0.0.1", 22, "SSH");

        // Simulate many auth failures
        for (int i = 0; i < 20; i++) {
            ThreatEvent event = new ThreatEvent(
                "AUTH_FAILURE", "10.0.0.1", 54321, 22,
                "SSH", 0, 0, 0,
                false, null, null, null, Map.of());
            service.processEvent(event);
        }

        // Should be auto-blocked
        assertTrue(reputationService.isBlocked("10.0.0.1"));
    }

    @Test
    void blockAndUnblockViaService() {
        service.blockIp("10.0.0.1", "test");
        assertTrue(service.getBlocklist().contains("10.0.0.1"));

        service.unblockIp("10.0.0.1");
        assertFalse(service.getBlocklist().contains("10.0.0.1"));
    }

    @Test
    void dashboardReturnsComprehensiveData() {
        service.computeVerdict("10.0.0.1", 22, "SSH");
        Map<String, Object> dashboard = service.getFullDashboard();

        assertNotNull(dashboard.get("verdicts"));
        assertNotNull(dashboard.get("ipReputation"));
        assertNotNull(dashboard.get("connectionPatterns"));
        assertNotNull(dashboard.get("geoIntelligence"));
        assertNotNull(dashboard.get("traffic"));
    }

    @Test
    void recentVerdictsRecorded() {
        service.computeVerdict("10.0.0.1", 22, "SSH");
        service.computeVerdict("10.0.0.2", 21, "FTP");

        var verdicts = service.getRecentVerdicts(10);
        assertEquals(2, verdicts.size());
    }

    @Test
    void ipIntelligenceReturnsDetails() {
        service.computeVerdict("10.0.0.1", 22, "SSH");
        Map<String, Object> intel = service.getIpIntelligence("10.0.0.1");

        assertNotNull(intel.get("reputation"));
        assertNotNull(intel.get("connectionPattern"));
        assertFalse((Boolean) intel.get("blocked"));
    }

    @Test
    void geoCountryInfoUsedInVerdict() {
        geoDetector.cacheIpCountry("10.0.0.1", "US");
        Verdict verdict = service.computeVerdict("10.0.0.1", 22, "SSH");
        assertEquals("US", verdict.metadata().get("country"));
    }

    @Test
    void highRiskGeoIncreasesRisk() {
        geoDetector.addHighRiskCountry("XX");
        geoDetector.cacheIpCountry("10.0.0.1", "XX");

        Verdict verdict = service.computeVerdict("10.0.0.1", 22, "SSH");
        assertTrue(verdict.riskScore() > 0);
        assertTrue(verdict.signals().contains("HIGH_RISK_REGION"));
    }
}
