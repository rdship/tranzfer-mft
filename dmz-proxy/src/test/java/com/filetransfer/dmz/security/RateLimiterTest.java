package com.filetransfer.dmz.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(10);
        limiter.setDefaultMaxConcurrent(3);
        limiter.setGlobalMaxPerMinute(100);
    }

    @Test
    void allowsConnectionsUnderLimit() {
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
    }

    @Test
    void blocksConcurrentOverLimit() {
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        // 4th concurrent should be blocked
        assertFalse(limiter.tryAcquire("10.0.0.1"));
    }

    @Test
    void releaseAllowsNewConnection() {
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertFalse(limiter.tryAcquire("10.0.0.1"));

        limiter.release("10.0.0.1");
        assertTrue(limiter.tryAcquire("10.0.0.1"));
    }

    @Test
    void differentIpsHaveSeparateLimits() {
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertFalse(limiter.tryAcquire("10.0.0.1"));

        // Different IP should still work
        assertTrue(limiter.tryAcquire("10.0.0.2"));
    }

    @Test
    void customIpLimitsOverrideDefaults() {
        limiter.setIpLimits("10.0.0.1", 100, 50, 1_000_000_000L);
        // Should be able to acquire more than default
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("10.0.0.1"));
        }
    }

    @Test
    void resetIpLimitsRestoresDefaults() {
        limiter.setIpLimits("10.0.0.1", 100, 50, 1_000_000_000L);
        limiter.resetIpLimits("10.0.0.1");

        // After reset, should use default limits again
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        // 4th should be blocked (default concurrent = 3)
        assertFalse(limiter.tryAcquire("10.0.0.1"));
    }

    @Test
    void byteLimitEnforced() {
        limiter.setIpLimits("10.0.0.1", 100, 50, 1000L); // 1KB/min
        limiter.tryAcquire("10.0.0.1");

        assertTrue(limiter.checkBytes("10.0.0.1", 500));
        assertTrue(limiter.checkBytes("10.0.0.1", 500));
        assertFalse(limiter.checkBytes("10.0.0.1", 500)); // exceeds 1000
    }

    @Test
    void statsReturnConfiguration() {
        var stats = limiter.getStats();
        assertEquals(10, stats.get("defaultMaxPerMinute"));
        assertEquals(3, stats.get("defaultMaxConcurrent"));
    }
}
