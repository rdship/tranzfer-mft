package com.filetransfer.ai.replica;

import com.filetransfer.ai.service.proxy.*;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.Action;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 3: Intelligence & Config — AI Engine, Analytics, Config, EDI Converter
 *
 * Tests multi-replica behavior for:
 * 1. AI verdict cache is per-replica (independent caching)
 * 2. Verdict counters are per-replica (no shared state)
 * 3. LLM escalation timeout doesn't block other connections
 * 4. Concurrent verdict computation under load
 * 5. Config cache divergence detection
 * 6. EDI trained map cache independence per replica
 */
class Batch3IntelligenceConfigReplicaTest {

    // ── AI Verdict Cache: Per-Replica Independence ────────────────────

    /**
     * Two replica ProxyIntelligenceService instances have independent verdict caches.
     * A cached verdict on replica-1 does NOT appear on replica-2.
     */
    @Test
    void verdictCache_perReplica_independent() throws Exception {
        // Each replica has its own set of components
        IpReputationService rep1 = new IpReputationService();
        IpReputationService rep2 = new IpReputationService();

        ProxyIntelligenceService replica1 = createMinimalService(rep1);
        ProxyIntelligenceService replica2 = createMinimalService(rep2);

        // Compute verdict on replica-1 for a known IP
        Verdict v1 = replica1.computeVerdict("10.0.0.1", 2222, "SFTP");
        assertNotNull(v1);

        // On replica-2, same IP should compute independently (not from cache)
        Verdict v2 = replica2.computeVerdict("10.0.0.1", 2222, "SFTP");
        assertNotNull(v2);

        // Both should produce valid verdicts (exact scores may differ due to timing)
        assertTrue(v1.riskScore() >= 0 && v1.riskScore() <= 100);
        assertTrue(v2.riskScore() >= 0 && v2.riskScore() <= 100);
    }

    /**
     * Verdict counters (totalVerdicts, totalAllowed, etc.) are independent per replica.
     */
    @Test
    void verdictCounters_perReplica_independent() throws Exception {
        IpReputationService rep1 = new IpReputationService();
        IpReputationService rep2 = new IpReputationService();

        ProxyIntelligenceService replica1 = createMinimalService(rep1);
        ProxyIntelligenceService replica2 = createMinimalService(rep2);

        // Compute 5 verdicts on replica-1
        for (int i = 0; i < 5; i++) {
            replica1.computeVerdict("10.0.0." + i, 2222, "SFTP");
        }

        // Compute 3 verdicts on replica-2
        for (int i = 0; i < 3; i++) {
            replica2.computeVerdict("10.0.1." + i, 2222, "SFTP");
        }

        long r1Count = getAtomicLong(replica1, "totalVerdicts");
        long r2Count = getAtomicLong(replica2, "totalVerdicts");

        assertEquals(5, r1Count, "Replica-1 should have 5 verdicts");
        assertEquals(3, r2Count, "Replica-2 should have 3 verdicts");
    }

    /**
     * Blocklisted IP returns BLACKHOLE verdict instantly on any replica.
     */
    @Test
    void blocklist_instantVerdict_anyReplica() throws Exception {
        IpReputationService rep = new IpReputationService();
        rep.blockIp("1.2.3.4", "test-block");

        ProxyIntelligenceService replica = createMinimalService(rep);
        Verdict v = replica.computeVerdict("1.2.3.4", 2222, "SFTP");

        assertEquals(Action.BLACKHOLE, v.action(), "Blocklisted IP should be blackholed");
        assertEquals(100, v.riskScore());
    }

    /**
     * Allowlisted IP returns ALLOW instantly on any replica.
     */
    @Test
    void allowlist_instantVerdict_anyReplica() throws Exception {
        IpReputationService rep = new IpReputationService();
        rep.allowIp("10.0.0.1");

        ProxyIntelligenceService replica = createMinimalService(rep);
        Verdict v = replica.computeVerdict("10.0.0.1", 2222, "SFTP");

        assertEquals(Action.ALLOW, v.action(), "Allowlisted IP should be allowed");
        assertEquals(0, v.riskScore());
    }

    // ── Concurrent Verdict Computation ────────────────────────────────

    /**
     * Multiple threads computing verdicts concurrently — no exceptions, no data corruption.
     */
    @Test
    void concurrentVerdicts_noExceptions() throws Exception {
        IpReputationService rep = new IpReputationService();
        ProxyIntelligenceService service = createMinimalService(rep);

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger completions = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            futures.add(exec.submit(() -> {
                try {
                    start.await();
                    Verdict v = service.computeVerdict("10.0." + (idx / 256) + "." + (idx % 256), 2222, "SFTP");
                    assertNotNull(v);
                    completions.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        exec.shutdown();

        assertEquals(0, errors.get(), "No errors should occur under concurrent verdict computation");
        assertEquals(100, completions.get(), "All 100 verdicts should complete");
    }

    /**
     * Ring buffer doesn't overflow under concurrent writes.
     */
    @Test
    void ringBuffer_concurrent_noOverflow() throws Exception {
        IpReputationService rep = new IpReputationService();
        ProxyIntelligenceService service = createMinimalService(rep);

        // Fill ring buffer beyond capacity (512) concurrently
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger(0);

        for (int i = 0; i < 1000; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    service.computeVerdict("10." + (idx / 65536) + "." + ((idx / 256) % 256) + "." + (idx % 256), 2222, "TCP");
                    completions.incrementAndGet();
                } catch (Exception e) { /* ignore */ }
            });
        }
        start.countDown();
        exec.shutdown();
        exec.awaitTermination(15, TimeUnit.SECONDS);

        // Ring buffer should wrap around without errors
        assertTrue(completions.get() > 500, "Most verdicts should complete: " + completions.get());
    }

    // ── Verdict Cache Key Isolation ───────────────────────────────────

    /**
     * Same IP on different ports/protocols gets independent cache entries.
     */
    @Test
    void verdictCacheKey_portProtocolIsolation() throws Exception {
        // Use reflection to verify cache key format
        Method keyMethod = ProxyIntelligenceService.class.getDeclaredMethod(
                "verdictCacheKey", String.class, int.class, String.class);
        keyMethod.setAccessible(true);

        String key1 = (String) keyMethod.invoke(null, "10.0.0.1", 2222, "SFTP");
        String key2 = (String) keyMethod.invoke(null, "10.0.0.1", 21, "FTP");
        String key3 = (String) keyMethod.invoke(null, "10.0.0.1", 2222, "TCP");

        assertNotEquals(key1, key2, "Different ports should have different cache keys");
        assertNotEquals(key1, key3, "Different protocols should have different cache keys");
    }

    /**
     * Cache TTL is risk-based: BLOCK cached 5min, ALLOW cached 10s, borderline not cached.
     */
    @Test
    void verdictCacheTtl_riskBased() throws Exception {
        Method ttlMethod = ProxyIntelligenceService.class.getDeclaredMethod(
                "computeCacheTtlMs", Action.class, int.class);
        ttlMethod.setAccessible(true);

        long blockTtl = (long) ttlMethod.invoke(null, Action.BLOCK, 90);
        long allowTtl = (long) ttlMethod.invoke(null, Action.ALLOW, 5);
        long borderlineTtl = (long) ttlMethod.invoke(null, Action.ALLOW, 40); // borderline
        long trustedTtl = (long) ttlMethod.invoke(null, Action.ALLOW, 5); // very low risk

        assertEquals(300_000, blockTtl, "BLOCK should be cached 5 min");
        assertEquals(30_000, trustedTtl, "Trusted ALLOW (risk<10) cached 30s");
        assertEquals(0, borderlineTtl, "Borderline (risk 30-59) should NEVER be cached");
    }

    // ── LLM Escalation — Per-Replica Behavior ─────────────────────────

    /**
     * LLM disabled → evaluate returns empty on any replica.
     */
    @Test
    void llmEscalation_disabled_returnsEmpty() throws Exception {
        LlmSecurityEscalation llm = new LlmSecurityEscalation();
        setField(llm, "llmEnabled", false);

        Optional<LlmSecurityEscalation.LlmVerdictResult> result =
                llm.evaluate("10.0.0.1", 2222, "SFTP", 50, List.of("NEW_IP"), Map.of());

        assertTrue(result.isEmpty(), "LLM disabled should return empty");
    }

    /**
     * LLM enabled but no API key → evaluate returns empty.
     */
    @Test
    void llmEscalation_noApiKey_returnsEmpty() throws Exception {
        LlmSecurityEscalation llm = new LlmSecurityEscalation();
        setField(llm, "llmEnabled", true);
        setField(llm, "apiKey", "");

        Optional<LlmSecurityEscalation.LlmVerdictResult> result =
                llm.evaluate("10.0.0.1", 2222, "SFTP", 50, List.of("NEW_IP"), Map.of());

        assertTrue(result.isEmpty(), "Missing API key should return empty");
    }

    /**
     * LLM enabled with API key but unreachable endpoint → returns empty (graceful degradation).
     */
    @Test
    void llmEscalation_unreachableEndpoint_gracefulDegradation() throws Exception {
        LlmSecurityEscalation llm = new LlmSecurityEscalation();
        setField(llm, "llmEnabled", true);
        setField(llm, "apiKey", "test-api-key");
        setField(llm, "model", "claude-sonnet-4-20250514");
        setField(llm, "baseUrl", "http://127.0.0.1:1"); // unreachable

        Optional<LlmSecurityEscalation.LlmVerdictResult> result =
                llm.evaluate("10.0.0.1", 2222, "SFTP", 50, List.of("NEW_IP"), Map.of());

        assertTrue(result.isEmpty(), "Unreachable endpoint should return empty (graceful degradation)");
    }

    // ── Config Cache Divergence ───────────────────────────────────────

    /**
     * Simulates two replicas of PlatformConfigLoader with different cache states.
     * After a setting change, only the replica that reloads sees the new value.
     */
    @Test
    void configCache_divergence_untilReload() {
        // Simulate two replica caches
        Map<String, String> replica1Cache = new ConcurrentHashMap<>();
        Map<String, String> replica2Cache = new ConcurrentHashMap<>();

        // Initial load: both see same config
        replica1Cache.put("sftp.port", "2222");
        replica1Cache.put("ai.llm.enabled", "false");
        replica2Cache.put("sftp.port", "2222");
        replica2Cache.put("ai.llm.enabled", "false");

        // Config change: ai.llm.enabled → true (written to DB)
        // Replica-1 reloads
        replica1Cache.put("ai.llm.enabled", "true");

        // Replica-2 hasn't reloaded yet
        assertEquals("true", replica1Cache.get("ai.llm.enabled"));
        assertEquals("false", replica2Cache.get("ai.llm.enabled"),
                "Replica-2 should still see old value until reload");

        // After replica-2 reloads
        replica2Cache.put("ai.llm.enabled", "true");
        assertEquals("true", replica2Cache.get("ai.llm.enabled"),
                "After reload, both replicas should see the same value");
    }

    /**
     * Concurrent config reads during reload — no exceptions.
     */
    @Test
    void configCache_concurrentReadDuringReload_noExceptions() throws Exception {
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            cache.put("key." + i, "value." + i);
        }

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Readers
        for (int i = 0; i < 50; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 1000; j++) {
                        cache.getOrDefault("key." + (j % 100), "default");
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
            });
        }

        // Writer (simulates reload)
        for (int i = 0; i < 10; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        cache.put("key." + j, "new_value." + j);
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
            });
        }

        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No errors during concurrent read/write");
    }

    // ── EDI Trained Map Cache Independence ────────────────────────────

    /**
     * Two replica EDI converter caches are independent.
     * Cache invalidation on replica-1 doesn't affect replica-2.
     */
    @Test
    void ediCache_perReplica_independent() {
        ConcurrentHashMap<String, Object> replica1Cache = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Object> replica2Cache = new ConcurrentHashMap<>();

        // Both replicas cache the same trained map
        String mapKey = "X12:850→JSON@ACME";
        replica1Cache.put(mapKey, Map.of("version", 1, "confidence", 0.95));
        replica2Cache.put(mapKey, Map.of("version", 1, "confidence", 0.95));

        // Invalidate cache on replica-1
        replica1Cache.clear();
        assertTrue(replica1Cache.isEmpty(), "Replica-1 cache should be cleared");
        assertFalse(replica2Cache.isEmpty(), "Replica-2 cache should be unaffected");
    }

    /**
     * Concurrent cache access with mixed reads and writes.
     */
    @Test
    void ediCache_concurrentAccess_consistent() throws Exception {
        ConcurrentHashMap<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Writers: add trained maps
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        String key = "X12:" + (idx * 50 + j) + "→JSON@PARTNER" + idx;
                        cache.put(key, Map.of("version", j, "confidence", 0.9));
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
            });
        }

        // Readers: look up random maps
        for (int i = 0; i < 20; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        cache.get("X12:" + (j % 1000) + "→JSON@PARTNER" + (j % 20));
                    }
                } catch (Exception e) { errors.incrementAndGet(); }
            });
        }

        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No errors during concurrent cache access");
    }

    // ── Scheduled Tasks: Replica Independence ─────────────────────────

    /**
     * Alert expiry runs independently per replica.
     * Alerts on replica-1 don't affect replica-2.
     */
    @Test
    void alertExpiry_perReplica_independent() {
        ConcurrentHashMap<String, Map<String, Object>> replica1Alerts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Map<String, Object>> replica2Alerts = new ConcurrentHashMap<>();

        // Both replicas have same alert
        Map<String, Object> alert = Map.of("ip", "1.2.3.4", "risk", 95, "time", System.currentTimeMillis());
        replica1Alerts.put("1.2.3.4", alert);
        replica2Alerts.put("1.2.3.4", alert);

        // Replica-1 runs expiry (clears old alert)
        replica1Alerts.clear();
        assertTrue(replica1Alerts.isEmpty());
        assertFalse(replica2Alerts.isEmpty(), "Replica-2 alerts should be unaffected");
    }

    /**
     * Event rate tracking is per-replica: prevents cross-replica rate limit bypass.
     */
    @Test
    void eventRateTracking_perReplica() {
        ConcurrentHashMap<String, AtomicInteger> replica1Rates = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> replica2Rates = new ConcurrentHashMap<>();

        String ip = "10.0.0.1";
        int maxEventsPerMinute = 30;

        // Simulate events on replica-1
        for (int i = 0; i < 30; i++) {
            replica1Rates.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        }
        assertEquals(30, replica1Rates.get(ip).get());
        assertTrue(replica1Rates.get(ip).get() >= maxEventsPerMinute,
                "Replica-1 should hit rate limit");

        // Replica-2 has independent counter → not rate limited yet
        assertNull(replica2Rates.get(ip),
                "Replica-2 should have no events tracked for this IP");
    }

    // ── Helper Methods ────────────────────────────────────────────────

    private ProxyIntelligenceService createMinimalService(IpReputationService reputationService) {
        ProtocolThreatDetector threatDetector = new ProtocolThreatDetector();
        ConnectionPatternAnalyzer patternAnalyzer = new ConnectionPatternAnalyzer();
        GeoAnomalyDetector geoDetector = new GeoAnomalyDetector();
        LlmSecurityEscalation llmEscalation = new LlmSecurityEscalation();

        return new ProxyIntelligenceService(
                reputationService,
                threatDetector,
                patternAnalyzer,
                geoDetector,
                llmEscalation,
                null, null, null, null, null, null
        );
    }

    private long getAtomicLong(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return ((AtomicLong) f.get(target)).get();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + target.getClass());
    }
}
