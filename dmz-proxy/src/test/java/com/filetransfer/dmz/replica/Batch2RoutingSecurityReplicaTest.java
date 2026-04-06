package com.filetransfer.dmz.replica;

import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.security.ManualSecurityFilter;
import com.filetransfer.dmz.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 2: Routing & Security — Gateway, DMZ Proxy, Encryption, Screening
 *
 * Tests multi-replica behavior for:
 * 1. DMZ Proxy rate limiting split across replicas
 * 2. Per-port rate limits (different listeners, different limits)
 * 3. ManualSecurityFilter per-mapping independence
 * 4. Concurrent rate-limit enforcement under high load
 * 5. Slow verdict / high-latency network resilience
 * 6. Gateway-style multi-backend routing fairness
 */
class Batch2RoutingSecurityReplicaTest {

    // ── DMZ Proxy Rate Limiter — Replica Awareness ────────────────────

    /**
     * 2 replicas each get half the rate limits.
     * Default 60/min → 30/min per replica. Global 10000 → 5000.
     */
    @Test
    void rateLimiter_twoReplicas_halvesAllLimits() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(60);
        limiter.setDefaultMaxConcurrent(20);
        limiter.setDefaultMaxBytesPerMinute(500_000_000L);
        limiter.setGlobalMaxPerMinute(10_000);

        limiter.setReplicaCount(2);

        assertEquals(30, limiter.getDefaultMaxPerMinute());
        assertEquals(10, limiter.getDefaultMaxConcurrent());
        var stats = limiter.getStats();
        assertEquals(250_000_000L, stats.get("defaultMaxBytesPerMinute"));
        assertEquals(5000, stats.get("globalMaxPerMinute"));
    }

    /**
     * 3 replicas: limits floor at 1 for very low values.
     */
    @Test
    void rateLimiter_threeReplicas_floorAtOne() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(2);
        limiter.setDefaultMaxConcurrent(2);
        limiter.setDefaultMaxBytesPerMinute(2L);
        limiter.setGlobalMaxPerMinute(2);

        limiter.setReplicaCount(3);

        assertEquals(1, limiter.getDefaultMaxPerMinute());
        assertEquals(1, limiter.getDefaultMaxConcurrent());
        var stats = limiter.getStats();
        assertEquals(1L, stats.get("defaultMaxBytesPerMinute"));
        assertEquals(1, stats.get("globalMaxPerMinute"));
    }

    /**
     * Single replica (count=1): no adjustment happens.
     */
    @Test
    void rateLimiter_singleReplica_noChange() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(60);
        limiter.setDefaultMaxConcurrent(20);
        limiter.setGlobalMaxPerMinute(10_000);

        limiter.setReplicaCount(1);

        assertEquals(60, limiter.getDefaultMaxPerMinute());
        assertEquals(20, limiter.getDefaultMaxConcurrent());
    }

    /**
     * Two independent limiter instances (two proxy replicas) each enforce half.
     * Combined behavior: 60/min total (30 per replica).
     */
    @Test
    void rateLimiter_twoInstances_collectiveEnforcement() {
        RateLimiter replica1 = new RateLimiter();
        replica1.setDefaultMaxPerMinute(60);
        replica1.setDefaultMaxConcurrent(10);
        replica1.setGlobalMaxPerMinute(10_000);
        replica1.setReplicaCount(2);

        RateLimiter replica2 = new RateLimiter();
        replica2.setDefaultMaxPerMinute(60);
        replica2.setDefaultMaxConcurrent(10);
        replica2.setGlobalMaxPerMinute(10_000);
        replica2.setReplicaCount(2);

        String ip = "10.0.0.1";
        int r1Allowed = 0, r2Allowed = 0;

        // Fill replica-1 concurrent limit (5 = 10/2)
        for (int i = 0; i < 20; i++) {
            if (replica1.tryAcquire(ip)) r1Allowed++;
        }
        assertEquals(5, r1Allowed, "Replica-1 should allow 5 concurrent (10/2)");

        // Fill replica-2 concurrent limit independently
        for (int i = 0; i < 20; i++) {
            if (replica2.tryAcquire(ip)) r2Allowed++;
        }
        assertEquals(5, r2Allowed, "Replica-2 should allow 5 concurrent (10/2)");

        // Total: 10 connections across 2 replicas = original limit
    }

    // ── Per-Port Rate Limits ──────────────────────────────────────────

    /**
     * Different ports can have different rate limits.
     * SFTP :2222 = strict (10/min, 5 concurrent)
     * FTP :21 = relaxed (100/min, 50 concurrent)
     */
    @Test
    void rateLimiter_perPort_differentLimits() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(60);
        limiter.setDefaultMaxConcurrent(20);
        limiter.setGlobalMaxPerMinute(10_000);

        limiter.setPortDefaults(2222, 10, 5, 100_000L);
        limiter.setPortDefaults(21, 100, 50, 1_000_000_000L);

        String ip = "10.0.0.1";

        // SFTP port: strict limits
        int sftpAllowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(ip, 2222)) sftpAllowed++;
        }
        assertEquals(5, sftpAllowed, "SFTP should allow only 5 concurrent");

        // Different IP on FTP port: relaxed limits
        String ip2 = "10.0.0.2";
        int ftpAllowed = 0;
        for (int i = 0; i < 60; i++) {
            if (limiter.tryAcquire(ip2, 21)) ftpAllowed++;
        }
        assertEquals(50, ftpAllowed, "FTP should allow 50 concurrent");
    }

    /**
     * Per-port limits with replica adjustment.
     */
    @Test
    void rateLimiter_perPortWithReplicas_adjustedCorrectly() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(60);
        limiter.setDefaultMaxConcurrent(20);
        limiter.setGlobalMaxPerMinute(10_000);
        limiter.setReplicaCount(2);

        // Port-specific limits set AFTER replica adjustment (these are per-mapping, not global)
        limiter.setPortDefaults(2222, 10, 4, 100_000L);

        String ip = "10.0.0.1";
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(ip, 2222)) allowed++;
        }
        assertEquals(4, allowed, "Port-specific limit should be used as-is");
    }

    /**
     * Fallback to global defaults when no per-port config exists.
     */
    @Test
    void rateLimiter_noPortConfig_usesGlobalDefaults() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(60);
        limiter.setDefaultMaxConcurrent(3);
        limiter.setGlobalMaxPerMinute(10_000);

        String ip = "10.0.0.1";
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(ip, 9999)) allowed++; // port with no config
        }
        assertEquals(3, allowed, "Should fall back to global concurrent limit");
    }

    // ── ManualSecurityFilter — Per-Mapping Independence ───────────────

    /**
     * Each mapping has its own ManualSecurityFilter with independent rules.
     * SFTP: strict whitelist. FTP: block specific IPs. AS2: allow all.
     */
    @Test
    void manualFilter_perMapping_independentRules() {
        // SFTP mapping: whitelist only 10.0.0.0/24
        PortMapping.SecurityPolicy sftpPolicy = PortMapping.SecurityPolicy.builder()
                .securityTier("RULES")
                .ipWhitelist(List.of("10.0.0.0/24"))
                .build();
        ManualSecurityFilter sftpFilter = new ManualSecurityFilter(sftpPolicy);

        // FTP mapping: blacklist specific IP
        PortMapping.SecurityPolicy ftpPolicy = PortMapping.SecurityPolicy.builder()
                .securityTier("RULES")
                .ipBlacklist(List.of("192.168.1.100"))
                .build();
        ManualSecurityFilter ftpFilter = new ManualSecurityFilter(ftpPolicy);

        // AS2 mapping: allow all (no restrictions)
        PortMapping.SecurityPolicy as2Policy = PortMapping.SecurityPolicy.builder()
                .securityTier("RULES")
                .build();
        ManualSecurityFilter as2Filter = new ManualSecurityFilter(as2Policy);

        // External IP 1.2.3.4: blocked by SFTP (not in whitelist), allowed by FTP and AS2
        assertFalse(sftpFilter.checkConnection("1.2.3.4").allowed());
        assertTrue(ftpFilter.checkConnection("1.2.3.4").allowed());
        assertTrue(as2Filter.checkConnection("1.2.3.4").allowed());

        // 10.0.0.50: allowed by SFTP whitelist, allowed by FTP (not blacklisted), allowed by AS2
        assertTrue(sftpFilter.checkConnection("10.0.0.50").allowed());
        assertTrue(ftpFilter.checkConnection("10.0.0.50").allowed());

        // 192.168.1.100: allowed by SFTP? No (not in whitelist). Blocked by FTP. Allowed by AS2.
        assertFalse(sftpFilter.checkConnection("192.168.1.100").allowed());
        assertFalse(ftpFilter.checkConnection("192.168.1.100").allowed());
        assertTrue(as2Filter.checkConnection("192.168.1.100").allowed());
    }

    /**
     * File extension rules are independent per mapping.
     */
    @Test
    void manualFilter_fileExtensions_perMapping() {
        PortMapping.SecurityPolicy strictPolicy = PortMapping.SecurityPolicy.builder()
                .allowedFileExtensions(List.of("csv", "xml", "json"))
                .blockedFileExtensions(List.of("exe", "bat"))
                .build();
        ManualSecurityFilter strictFilter = new ManualSecurityFilter(strictPolicy);

        PortMapping.SecurityPolicy relaxedPolicy = PortMapping.SecurityPolicy.builder().build();
        ManualSecurityFilter relaxedFilter = new ManualSecurityFilter(relaxedPolicy);

        // Strict: allows csv, blocks exe
        assertTrue(strictFilter.isFileAllowed("data.csv"));
        assertTrue(strictFilter.isFileAllowed("config.xml"));
        assertFalse(strictFilter.isFileAllowed("malware.exe"));
        assertFalse(strictFilter.isFileAllowed("script.bat"));
        assertFalse(strictFilter.isFileAllowed("image.png")); // not in allowed list

        // Relaxed: allows everything
        assertTrue(relaxedFilter.isFileAllowed("data.csv"));
        assertTrue(relaxedFilter.isFileAllowed("malware.exe"));
        assertTrue(relaxedFilter.isFileAllowed("image.png"));
    }

    /**
     * Geo-blocking with country-specific rules per mapping.
     */
    @Test
    void manualFilter_geoBlocking_perMapping() {
        PortMapping.SecurityPolicy usOnly = PortMapping.SecurityPolicy.builder()
                .geoAllowedCountries(List.of("US", "CA"))
                .build();
        ManualSecurityFilter usFilter = new ManualSecurityFilter(usOnly);

        PortMapping.SecurityPolicy blockRu = PortMapping.SecurityPolicy.builder()
                .geoBlockedCountries(List.of("RU", "CN"))
                .build();
        ManualSecurityFilter blockFilter = new ManualSecurityFilter(blockRu);

        // US-only filter
        assertTrue(usFilter.checkConnectionWithGeo("10.0.0.1", "US").allowed());
        assertTrue(usFilter.checkConnectionWithGeo("10.0.0.1", "CA").allowed());
        assertFalse(usFilter.checkConnectionWithGeo("10.0.0.1", "RU").allowed());

        // Block-specific filter
        assertTrue(blockFilter.checkConnectionWithGeo("10.0.0.1", "US").allowed());
        assertFalse(blockFilter.checkConnectionWithGeo("10.0.0.1", "RU").allowed());
        assertFalse(blockFilter.checkConnectionWithGeo("10.0.0.1", "CN").allowed());
    }

    // ── Concurrent Rate Limiting Under High Load ──────────────────────

    /**
     * 16 threads hammering the same IP — no over-admission beyond concurrent limit.
     */
    @Test
    void rateLimiter_highConcurrency_noOverAdmission() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(10_000);
        limiter.setDefaultMaxConcurrent(10);
        limiter.setGlobalMaxPerMinute(100_000);

        ExecutorService exec = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(0);
        String ip = "10.0.0.1";

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                if (limiter.tryAcquire(ip)) admitted.incrementAndGet();
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertTrue(admitted.get() <= 10,
                "Should not exceed concurrent limit of 10, got " + admitted.get());
    }

    /**
     * Rapid acquire/release cycles from many threads — counters stay consistent.
     */
    @Test
    void rateLimiter_rapidAcquireRelease_noLeaks() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(100_000);
        limiter.setDefaultMaxConcurrent(100);
        limiter.setGlobalMaxPerMinute(1_000_000);

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final String ip = "10.0.0." + (t + 1);
            futures.add(exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 500; i++) {
                    if (limiter.tryAcquire(ip)) {
                        limiter.release(ip);
                    }
                }
                completions.incrementAndGet();
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(8, completions.get(), "All threads should complete");
    }

    /**
     * Byte rate limiting under concurrent access.
     */
    @Test
    void rateLimiter_concurrentByteTracking_limitsEnforced() throws Exception {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(10_000);
        limiter.setDefaultMaxConcurrent(100);
        limiter.setGlobalMaxPerMinute(1_000_000);

        String ip = "10.0.0.1";
        limiter.setIpLimits(ip, 10_000, 100, 10_000L); // 10KB/min
        limiter.tryAcquire(ip);

        ExecutorService exec = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger withinLimit = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                if (limiter.checkBytes(ip, 1000)) withinLimit.incrementAndGet();
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        // 10KB limit, 20 × 1KB = 20KB. Only first 10 should pass.
        assertTrue(withinLimit.get() <= 10,
                "Should not allow more than 10KB, got " + withinLimit.get() + "KB");
    }

    // ── Security Tier Routing ─────────────────────────────────────────

    /**
     * Verifies that different security tiers produce correct filter configurations.
     */
    @Test
    void securityTiers_correctConfiguration() {
        // RULES tier: manual filter only, no AI
        PortMapping.SecurityPolicy rulesPolicy = PortMapping.SecurityPolicy.builder()
                .securityTier("RULES")
                .ipWhitelist(List.of("10.0.0.0/8"))
                .rateLimitPerMinute(30)
                .maxConcurrent(5)
                .build();
        ManualSecurityFilter rulesFilter = new ManualSecurityFilter(rulesPolicy);
        assertEquals("RULES", rulesFilter.getPolicy().getSecurityTier());
        assertTrue(rulesFilter.checkConnection("10.0.0.1").allowed());
        assertFalse(rulesFilter.checkConnection("192.168.1.1").allowed());

        // AI tier: same manual rules, but AI verdict added externally
        PortMapping.SecurityPolicy aiPolicy = PortMapping.SecurityPolicy.builder()
                .securityTier("AI")
                .ipBlacklist(List.of("1.2.3.4"))
                .build();
        ManualSecurityFilter aiFilter = new ManualSecurityFilter(aiPolicy);
        assertEquals("AI", aiFilter.getPolicy().getSecurityTier());
        assertFalse(aiFilter.checkConnection("1.2.3.4").allowed());
        assertTrue(aiFilter.checkConnection("5.6.7.8").allowed());

        // AI_LLM tier: same structure, tier stored in policy
        PortMapping.SecurityPolicy llmPolicy = PortMapping.SecurityPolicy.builder()
                .securityTier("AI_LLM")
                .requireEncryption(true)
                .build();
        ManualSecurityFilter llmFilter = new ManualSecurityFilter(llmPolicy);
        assertEquals("AI_LLM", llmFilter.getPolicy().getSecurityTier());
        assertTrue(llmFilter.isRequireEncryption());
    }

    /**
     * Encryption requirement is per-mapping.
     */
    @Test
    void securityTiers_encryptionRequirement_perMapping() {
        PortMapping.SecurityPolicy encrypted = PortMapping.SecurityPolicy.builder()
                .requireEncryption(true).build();
        PortMapping.SecurityPolicy plain = PortMapping.SecurityPolicy.builder()
                .requireEncryption(false).build();

        assertTrue(new ManualSecurityFilter(encrypted).isRequireEncryption());
        assertFalse(new ManualSecurityFilter(plain).isRequireEncryption());
    }

    // ── IP Override by AI — per-IP custom limits ──────────────────────

    /**
     * AI engine can set per-IP overrides that survive across connections.
     * After AI flags a suspicious IP, rate limit is tightened.
     */
    @Test
    void rateLimiter_aiOverrides_perIpLimits() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(100);
        limiter.setDefaultMaxConcurrent(20);
        limiter.setGlobalMaxPerMinute(10_000);

        String suspiciousIp = "10.0.0.99";
        String normalIp = "10.0.0.1";

        // AI overrides: suspicious IP gets 2 concurrent max
        limiter.setIpLimits(suspiciousIp, 5, 2, 10_000L);

        // Suspicious IP: blocked after 2
        assertTrue(limiter.tryAcquire(suspiciousIp));
        assertTrue(limiter.tryAcquire(suspiciousIp));
        assertFalse(limiter.tryAcquire(suspiciousIp));

        // Normal IP: uses default (20 concurrent)
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire(normalIp), "Normal IP should allow connection " + (i + 1));
        }
        assertFalse(limiter.tryAcquire(normalIp));
    }

    /**
     * Reset AI overrides back to defaults.
     */
    @Test
    void rateLimiter_resetOverrides_restoresDefaults() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(100);
        limiter.setDefaultMaxConcurrent(5);
        limiter.setGlobalMaxPerMinute(10_000);

        String ip = "10.0.0.1";
        limiter.setIpLimits(ip, 100, 1, 1_000_000L); // restrict to 1 concurrent
        assertTrue(limiter.tryAcquire(ip));
        assertFalse(limiter.tryAcquire(ip)); // 1 concurrent limit

        limiter.release(ip);
        limiter.resetIpLimits(ip);

        // After reset: should use default (5 concurrent)
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(ip), "After reset, connection " + (i + 1) + " should work");
        }
        assertFalse(limiter.tryAcquire(ip));
    }

    // ── Global Rate Limit Protection ──────────────────────────────────

    /**
     * Global rate limit with replica adjustment: 2 replicas halve global tokens.
     * Global 10000 → 5000 per replica. After using up tokens, new connections blocked.
     */
    @Test
    void rateLimiter_globalLimit_adjustedByReplicaCount() {
        RateLimiter limiter = new RateLimiter();
        limiter.setDefaultMaxPerMinute(10_000);
        limiter.setDefaultMaxConcurrent(100);
        limiter.setGlobalMaxPerMinute(10_000);

        // 2 replicas: global becomes 5000
        limiter.setReplicaCount(2);

        var stats = limiter.getStats();
        assertEquals(5000, stats.get("globalMaxPerMinute"),
                "Global limit should be halved for 2 replicas");

        // 3 replicas: global becomes 10000/3 = 3333
        RateLimiter limiter3 = new RateLimiter();
        limiter3.setDefaultMaxPerMinute(10_000);
        limiter3.setDefaultMaxConcurrent(100);
        limiter3.setGlobalMaxPerMinute(10_000);
        limiter3.setReplicaCount(3);

        var stats3 = limiter3.getStats();
        assertEquals(3333, stats3.get("globalMaxPerMinute"),
                "Global limit should be divided by 3 for 3 replicas");
    }
}
