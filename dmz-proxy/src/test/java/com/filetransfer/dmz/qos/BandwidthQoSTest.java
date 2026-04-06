package com.filetransfer.dmz.qos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthQoSTest {

    // Global limit 10000 B/s, per-mapping 5000, no per-connection limit, priority 5, 20% burst
    private BandwidthQoS qos;

    @BeforeEach
    void setUp() {
        qos = new BandwidthQoS(
                new BandwidthQoS.QoSConfig(true, 10000, 5000, 0, 5, 20));
        qos.start();
    }

    @AfterEach
    void tearDown() {
        if (qos != null) qos.shutdown();
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    void disabled_alwaysAllows() {
        qos.shutdown();
        qos = new BandwidthQoS(
                new BandwidthQoS.QoSConfig(false, 0, 0, 0, 5, 0));
        qos.start();

        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 5, 999_999_999),
                "Disabled QoS should always allow");
        assertTrue(qos.tryConsume("conn-2", "sftp-gw", 5, Long.MAX_VALUE / 2),
                "Disabled QoS should always allow even huge amounts");
    }

    @Test
    void tierToPriority_mappings() {
        assertEquals(1, BandwidthQoS.tierToPriority("RULES"));
        assertEquals(3, BandwidthQoS.tierToPriority("AI"));
        assertEquals(5, BandwidthQoS.tierToPriority("AI_LLM"));
        assertEquals(5, BandwidthQoS.tierToPriority(null));
        assertEquals(5, BandwidthQoS.tierToPriority("UNKNOWN_TIER"));
    }

    @Test
    void tryConsume_withinGlobalLimit_allowed() {
        // Global capacity = 10000 * (1 + 20/100) = 12000 tokens
        // Consuming a small amount should succeed
        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 5, 100),
                "Small consumption within global limit should be allowed");
    }

    @Test
    void tryConsume_exceedsGlobalLimit_blocked() {
        // Global capacity = 10000 * (1 + 20/100) = 12000 tokens
        // With priority 5, weight = 11-5 = 6, weightedBytes = bytes * 5 / 6
        // To exhaust: we need weightedBytes > 12000
        // weightedBytes = bytes * 5 / 6, so bytes > 12000 * 6 / 5 = 14400
        // Consume 14401 bytes at priority 5 → weightedBytes = 14401 * 5 / 6 = 12000 (truncated)
        // Actually let's just consume twice: first a big chunk, then another
        // First consume: 12000 bytes → weightedBytes = 12000 * 5 / 6 = 10000
        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 5, 12000),
                "First large consume should succeed");
        // Second consume: 5000 bytes → weightedBytes = 5000 * 5 / 6 = 4166
        // Remaining global tokens ~ 12000 - 10000 = 2000, need 4166 → should fail
        assertFalse(qos.tryConsume("conn-1", "sftp-gw", 5, 5000),
                "Should be blocked after exceeding global capacity");
    }

    @Test
    void registerAndUnregister_connection() {
        qos.registerConnection("conn-abc", "sftp-gw", 3);

        BandwidthQoS.BandwidthStats stats = qos.getStats("sftp-gw");
        assertNotNull(stats, "Stats should exist after registering a connection on mapping");
        assertEquals(1, stats.activeConnections(), "Should have 1 active connection");

        qos.unregisterConnection("conn-abc");

        BandwidthQoS.BandwidthStats afterStats = qos.getStats("sftp-gw");
        assertNotNull(afterStats, "Stats should still exist for mapping after unregister");
        assertEquals(0, afterStats.activeConnections(), "Should have 0 active connections after unregister");
    }

    @Test
    void perMapping_statsTracking() {
        qos.registerConnection("conn-1", "ftp-gw", 3);

        // Consume some bytes to generate stats
        assertTrue(qos.tryConsume("conn-1", "ftp-gw", 3, 500));

        BandwidthQoS.BandwidthStats stats = qos.getStats("ftp-gw");
        assertNotNull(stats, "Stats should exist for mapping with traffic");
        assertTrue(stats.totalBytes() >= 500, "totalBytes should reflect consumed bytes");
        assertEquals(1, stats.activeConnections());
    }

    @Test
    void globalStats_hasExpectedKeys() {
        Map<String, Object> globalStats = qos.getGlobalStats();

        assertTrue(globalStats.containsKey("enabled"));
        assertTrue(globalStats.containsKey("globalCurrentBps"));
        assertTrue(globalStats.containsKey("globalLimitBps"));
        assertTrue(globalStats.containsKey("globalTotalBytes"));
        assertTrue(globalStats.containsKey("totalConnections"));
        assertTrue(globalStats.containsKey("totalMappings"));
        assertTrue(globalStats.containsKey("mappings"));

        assertEquals(true, globalStats.get("enabled"));
    }

    @Test
    void qosConfig_clampsValues() {
        // Priority below 1 should clamp to 1
        BandwidthQoS.QoSConfig lowPriority = new BandwidthQoS.QoSConfig(
                true, 1000, 500, 0, -5, 10);
        assertEquals(1, lowPriority.priority(), "Priority below 1 should clamp to 1");

        // Priority above 10 should clamp to 10
        BandwidthQoS.QoSConfig highPriority = new BandwidthQoS.QoSConfig(
                true, 1000, 500, 0, 99, 10);
        assertEquals(10, highPriority.priority(), "Priority above 10 should clamp to 10");

        // Negative burst should clamp to 0
        BandwidthQoS.QoSConfig negativeBurst = new BandwidthQoS.QoSConfig(
                true, 1000, 500, 0, 5, -20);
        assertEquals(0, negativeBurst.burstAllowancePercent(),
                "Negative burstAllowancePercent should clamp to 0");
    }

    @Test
    void startAndShutdown_lifecycle() {
        // Create a fresh instance to test the full lifecycle without interference
        BandwidthQoS lifecycle = new BandwidthQoS(
                new BandwidthQoS.QoSConfig(true, 10000, 5000, 0, 5, 20));

        assertDoesNotThrow(lifecycle::start, "start() should not throw");
        assertDoesNotThrow(lifecycle::shutdown, "shutdown() should not throw");
    }

    @Test
    void tryConsume_zeroBytes_alwaysAllowed() {
        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 5, 0),
                "Zero bytes should always be allowed");
        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 1, 0),
                "Zero bytes should always be allowed regardless of priority");
        assertTrue(qos.tryConsume("conn-1", "sftp-gw", 10, -1),
                "Negative bytes should also be allowed (treated as <= 0)");
    }
}
