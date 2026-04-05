package com.filetransfer.ai.service.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IpReputationServiceTest {

    private IpReputationService service;

    @BeforeEach
    void setUp() {
        service = new IpReputationService();
    }

    @Test
    void newIpStartsAtNeutralScore() {
        assertEquals(50.0, service.getScore("10.0.0.1"));
        assertTrue(service.isNewIp("10.0.0.1"));
    }

    @Test
    void recordConnectionCreatesReputation() {
        service.recordConnection("10.0.0.1", "SSH");
        // After 1 connection, still considered "new" (threshold is <= 1)
        assertTrue(service.isNewIp("10.0.0.1"));

        // Second connection makes it established
        service.recordConnection("10.0.0.1", "SSH");
        assertFalse(service.isNewIp("10.0.0.1"));

        IpReputationService.IpReputation rep = service.getOrCreate("10.0.0.1");
        assertEquals(2, rep.getTotalConnections());
        assertTrue(rep.getProtocols().contains("SSH"));
    }

    @Test
    void successIncreasesScore() {
        service.recordConnection("10.0.0.1", "SSH");
        double before = service.getScore("10.0.0.1");
        service.recordSuccess("10.0.0.1");
        assertTrue(service.getScore("10.0.0.1") > before);
    }

    @Test
    void failureDecreasesScore() {
        service.recordConnection("10.0.0.1", "SSH");
        double before = service.getScore("10.0.0.1");
        service.recordFailure("10.0.0.1", "auth_failure");
        assertTrue(service.getScore("10.0.0.1") < before);
    }

    @Test
    void severeFailureDecreasesScoreMore() {
        service.recordConnection("10.0.0.1", "SSH");
        service.recordConnection("10.0.0.2", "SSH");

        service.recordFailure("10.0.0.1", "auth_failure");
        service.recordFailure("10.0.0.2", "exploit_attempt");

        // Exploit should decrease more than auth failure
        assertTrue(service.getScore("10.0.0.2") < service.getScore("10.0.0.1"));
    }

    @Test
    void autoBlocklistOnZeroScore() {
        service.recordConnection("10.0.0.1", "SSH");
        // Drive score to zero with repeated exploit failures
        for (int i = 0; i < 10; i++) {
            service.recordFailure("10.0.0.1", "exploit_attack");
        }
        assertTrue(service.isBlocked("10.0.0.1"));
    }

    @Test
    void manualBlockAndUnblock() {
        service.blockIp("10.0.0.1", "manual");
        assertTrue(service.isBlocked("10.0.0.1"));
        assertEquals(0.0, service.getScore("10.0.0.1"));

        service.unblockIp("10.0.0.1");
        assertFalse(service.isBlocked("10.0.0.1"));
        assertEquals(25.0, service.getScore("10.0.0.1")); // restored to low-trust
    }

    @Test
    void allowlistSetsTrustedScore() {
        service.allowIp("10.0.0.1");
        assertTrue(service.isAllowed("10.0.0.1"));
        assertEquals(100.0, service.getScore("10.0.0.1"));
    }

    @Test
    void scoreCappedAt0And100() {
        IpReputationService.IpReputation rep = service.getOrCreate("10.0.0.1");
        rep.adjustScore(-200);
        assertEquals(0.0, rep.getScore());
        rep.adjustScore(500);
        assertEquals(100.0, rep.getScore());
    }

    @Test
    void countryTracking() {
        service.recordConnection("10.0.0.1", "SSH");
        service.recordCountry("10.0.0.1", "US");
        service.recordCountry("10.0.0.1", "DE");

        IpReputationService.IpReputation rep = service.getOrCreate("10.0.0.1");
        assertTrue(rep.getCountries().contains("US"));
        assertTrue(rep.getCountries().contains("DE"));
    }

    @Test
    void bytesTracking() {
        service.recordConnection("10.0.0.1", "SFTP");
        service.recordBytes("10.0.0.1", 1024);
        service.recordBytes("10.0.0.1", 2048);

        assertEquals(3072, service.getOrCreate("10.0.0.1").getBytesTransferred());
    }

    @Test
    void topThreatsReturnsLowScoreIps() {
        service.blockIp("10.0.0.1", "test");
        service.recordConnection("10.0.0.2", "SSH");

        var threats = service.getTopThreats(10);
        assertFalse(threats.isEmpty());
        // Blocked IP should be in threats
        assertTrue(threats.stream().anyMatch(m -> "10.0.0.1".equals(m.get("ip"))));
    }

    @Test
    void statsReturnsSummary() {
        service.recordConnection("10.0.0.1", "SSH");
        service.blockIp("10.0.0.2", "test");
        service.allowIp("10.0.0.3");

        Map<String, Object> stats = service.getStats();
        assertTrue((int) stats.get("trackedIps") >= 2);
        assertEquals(1, stats.get("blockedIps"));
        assertEquals(1, stats.get("allowedIps"));
    }
}
