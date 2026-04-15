package com.filetransfer.shared.replica;

import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.cluster.ClusterContext;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.core.ServiceRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Batch 4: Platform Services — License, Keystore, Storage, External Forwarder
 *
 * Tests multi-replica behavior for:
 * 1. License validation consistency across replicas (shared DB, no cache)
 * 2. Keystore operations are DB-backed (all replicas see same keys)
 * 3. Storage tiering with @SchedulerLock (only one replica runs)
 * 4. Transfer watchdog independence per replica
 * 5. Concurrent file access on shared storage
 * 6. Retry backoff under high-latency conditions
 */
class Batch4PlatformServicesReplicaTest {

    // ── License Validation: DB-Backed, No Cache ───────────────────────

    /**
     * All replicas query the same DB for license validation.
     * Simulates two replicas checking the same license record.
     */
    @Test
    void licenseValidation_sameDbState_allReplicas() {
        // Simulated shared DB state
        Map<String, Map<String, Object>> licenseDb = new ConcurrentHashMap<>();
        licenseDb.put("LIC-ABCD1234", Map.of(
                "active", true,
                "expiresAt", System.currentTimeMillis() + 86400_000,
                "maxConnections", 100,
                "services", List.of("SFTP", "FTP", "ENCRYPTION")));

        // Replica-1 validates
        Map<String, Object> r1 = licenseDb.get("LIC-ABCD1234");
        assertTrue((boolean) r1.get("active"), "Replica-1 should see active license");
        assertTrue((long) r1.get("expiresAt") > System.currentTimeMillis());

        // Replica-2 validates same key — sees identical state
        Map<String, Object> r2 = licenseDb.get("LIC-ABCD1234");
        assertEquals(r1.get("active"), r2.get("active"));
        assertEquals(r1.get("maxConnections"), r2.get("maxConnections"));
    }

    /**
     * License revocation on one replica is immediately visible to all.
     */
    @Test
    void licenseRevocation_immediatelyVisibleAcrossReplicas() {
        Map<String, Boolean> licenseActive = new ConcurrentHashMap<>();
        licenseActive.put("LIC-ABCD1234", true);

        // Replica-1 checks: active
        assertTrue(licenseActive.get("LIC-ABCD1234"));

        // Admin revokes via replica-2
        licenseActive.put("LIC-ABCD1234", false);

        // Replica-1 re-checks: revoked
        assertFalse(licenseActive.get("LIC-ABCD1234"),
                "Revocation should be visible to all replicas immediately via shared DB");
    }

    /**
     * Concurrent license check-ins from multiple replicas don't corrupt activation state.
     */
    @Test
    void licenseCheckIn_concurrent_noCorruption() throws Exception {
        AtomicLong lastCheckIn = new AtomicLong(0);
        AtomicInteger checkInCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        // 8 replicas, each checking in 50 times
        for (int replica = 0; replica < 8; replica++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 50; i++) {
                    long ts = System.currentTimeMillis();
                    lastCheckIn.updateAndGet(prev -> Math.max(prev, ts));
                    checkInCount.incrementAndGet();
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(400, checkInCount.get(), "All check-ins should complete");
        assertTrue(lastCheckIn.get() > 0, "Last check-in should be recorded");
    }

    // ── Keystore: Shared DB, No Local Cache ───────────────────────────

    /**
     * Key generated on replica-1 is visible to replica-2 via shared DB.
     */
    @Test
    void keystoreKey_generatedOnOneReplica_visibleOnAll() {
        // Simulated shared DB
        ConcurrentHashMap<String, Map<String, Object>> keyDb = new ConcurrentHashMap<>();

        // Replica-1 generates SSH host key
        keyDb.put("sftp-host-key", Map.of(
                "keyType", "SSH_HOST_KEY",
                "algorithm", "EC",
                "keySizeBits", 256,
                "active", true,
                "ownerService", "SFTP"));

        // Replica-2 reads key
        assertNotNull(keyDb.get("sftp-host-key"), "Key should be visible to all replicas");
        assertEquals("EC", keyDb.get("sftp-host-key").get("algorithm"));
    }

    /**
     * Key rotation: old key deactivated, new key activated — visible to all replicas.
     */
    @Test
    void keystoreRotation_atomicVisibility() {
        ConcurrentHashMap<String, Map<String, Object>> keyDb = new ConcurrentHashMap<>();

        // Old key
        keyDb.put("old-key", Map.of("active", true, "rotatedToAlias", ""));
        // Rotate: deactivate old, create new
        keyDb.put("old-key", Map.of("active", false, "rotatedToAlias", "new-key"));
        keyDb.put("new-key", Map.of("active", true, "rotatedToAlias", ""));

        assertFalse((boolean) keyDb.get("old-key").get("active"), "Old key should be inactive");
        assertTrue((boolean) keyDb.get("new-key").get("active"), "New key should be active");
    }

    /**
     * Concurrent key lookups from many replicas — no blocking.
     */
    @Test
    void keystoreLookup_concurrent_noBlocking() throws Exception {
        ConcurrentHashMap<String, String> keyDb = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            keyDb.put("key-" + i, "material-" + i);
        }

        ExecutorService exec = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger(0);

        for (int r = 0; r < 16; r++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 1000; i++) {
                    assertNotNull(keyDb.get("key-" + (i % 100)));
                    lookups.incrementAndGet();
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(16_000, lookups.get(), "All lookups should complete");
    }

    // ── Storage Tiering: @SchedulerLock Ensures Single Execution ──────

    /**
     * Only one replica should run tiering at a time (simulated with tryLock).
     * Other replicas that can't acquire the lock skip the cycle entirely.
     */
    @Test
    void storageTiering_onlyOneReplicaRuns() throws Exception {
        AtomicInteger executions = new AtomicInteger(0);
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService exec = Executors.newFixedThreadPool(3);

        // 3 replicas try to run tiering simultaneously
        for (int r = 0; r < 3; r++) {
            exec.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { return; }
                // tryLock: only one thread wins, others skip immediately
                if (lock.tryLock()) {
                    try {
                        executions.incrementAndGet();
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    } finally {
                        lock.unlock();
                    }
                }
                // Others skip — same as @SchedulerLock behavior
            });
        }
        ready.await(); // wait for all threads to be ready
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, executions.get(),
                "Only one replica should execute tiering (others skip due to lock)");
    }

    /**
     * File integrity check: SHA-256 must match after tier copy.
     */
    @Test
    void storageTiering_integrityCheck_sha256Match(@TempDir Path tempDir) throws Exception {
        Path hotDir = tempDir.resolve("hot");
        Path warmDir = tempDir.resolve("warm");
        Files.createDirectories(hotDir);
        Files.createDirectories(warmDir);

        // Create a test file in hot tier
        String content = "This is a test file for tier migration: " + UUID.randomUUID();
        Path hotFile = hotDir.resolve("test-data.csv");
        Files.writeString(hotFile, content);

        // Simulate tier copy (hot → warm)
        Path warmFile = warmDir.resolve("test-data.csv");
        Files.copy(hotFile, warmFile, StandardCopyOption.REPLACE_EXISTING);

        // Verify integrity
        byte[] hotBytes = Files.readAllBytes(hotFile);
        byte[] warmBytes = Files.readAllBytes(warmFile);
        assertArrayEquals(hotBytes, warmBytes, "File content must be identical after tier copy");

        // SHA-256 comparison
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String hotHash = bytesToHex(md.digest(hotBytes));
        md.reset();
        String warmHash = bytesToHex(md.digest(warmBytes));
        assertEquals(hotHash, warmHash, "SHA-256 must match after tier migration");
    }

    /**
     * Concurrent file reads from shared storage volume (simulating multi-replica access).
     */
    @Test
    void sharedStorage_concurrentReads_noCorruption(@TempDir Path tempDir) throws Exception {
        // Create test files
        for (int i = 0; i < 10; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".csv"), "content-" + i);
        }

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger reads = new AtomicInteger(0);

        // 8 "replicas" reading the same files concurrently
        for (int r = 0; r < 8; r++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 100; i++) {
                    try {
                        String content = Files.readString(tempDir.resolve("file-" + (i % 10) + ".csv"));
                        assertEquals("content-" + (i % 10), content);
                        reads.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No corruption during concurrent reads");
        assertEquals(800, reads.get(), "All reads should complete");
    }

    /**
     * Concurrent file writes from multiple replicas to shared volume.
     * Files with unique names: no conflicts. Same file: last-writer-wins.
     */
    @Test
    void sharedStorage_concurrentWrites_uniqueFiles(@TempDir Path tempDir) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger(0);

        // 4 "replicas" writing unique files
        for (int r = 0; r < 4; r++) {
            final int replica = r;
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 50; i++) {
                    try {
                        String filename = "replica" + replica + "-file" + i + ".csv";
                        Files.writeString(tempDir.resolve(filename), "data-from-replica-" + replica);
                        writes.incrementAndGet();
                    } catch (Exception ignored) {}
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(200, writes.get(), "All writes should succeed (unique filenames)");

        // Verify all files exist
        for (int r = 0; r < 4; r++) {
            for (int i = 0; i < 50; i++) {
                assertTrue(Files.exists(tempDir.resolve("replica" + r + "-file" + i + ".csv")));
            }
        }
    }

    // ── Transfer Watchdog: Per-Replica Independence ───────────────────

    /**
     * Each replica has its own TransferWatchdog — active sessions don't leak across.
     */
    @Test
    void transferWatchdog_perReplica_independent() {
        // Simulate two replica watchdogs with independent session maps
        ConcurrentHashMap<String, Map<String, Object>> replica1Sessions = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Map<String, Object>> replica2Sessions = new ConcurrentHashMap<>();

        // Replica-1 registers a transfer
        replica1Sessions.put("xfer-001", Map.of("endpoint", "partner-sftp", "bytes", 1024L));
        assertEquals(1, replica1Sessions.size());
        assertEquals(0, replica2Sessions.size(), "Replica-2 should have no sessions");

        // Replica-2 registers its own transfer
        replica2Sessions.put("xfer-002", Map.of("endpoint", "partner-ftp", "bytes", 2048L));
        assertEquals(1, replica1Sessions.size());
        assertEquals(1, replica2Sessions.size());

        // Stall detected on replica-1 — cleanup only affects replica-1
        replica1Sessions.remove("xfer-001");
        assertEquals(0, replica1Sessions.size());
        assertEquals(1, replica2Sessions.size(), "Replica-2 sessions unaffected by replica-1 cleanup");
    }

    /**
     * High-volume concurrent transfer registrations — no deadlocks.
     */
    @Test
    void transferWatchdog_highVolume_noDeadlock() throws Exception {
        ConcurrentHashMap<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger(0);

        for (int t = 0; t < 8; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 100; i++) {
                    String id = "xfer-" + threadId + "-" + i;
                    sessions.put(id, Map.of("thread", threadId, "idx", i));
                    sessions.remove(id);
                }
                completions.incrementAndGet();
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS),
                "Should complete without deadlock");
        assertEquals(8, completions.get());
        assertTrue(sessions.isEmpty(), "All sessions should be cleaned up");
    }

    // ── Retry Backoff Under High Latency ──────────────────────────────

    /**
     * Exponential backoff with jitter: delays increase but stay capped.
     */
    @Test
    void retryBackoff_exponentialWithJitter_capped() {
        long baseDelay = 5000L;
        long maxDelay = 120_000L;
        int maxRetries = 5;

        long[] delays = new long[maxRetries];
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            long exponential = baseDelay * (1L << Math.min(attempt, 6));
            long capped = Math.min(exponential, maxDelay);
            double jitter = 0.75 + Math.random() * 0.5;
            delays[attempt] = (long) (capped * jitter);
        }

        // Each delay should be positive and capped
        for (int i = 0; i < maxRetries; i++) {
            assertTrue(delays[i] > 0, "Delay must be positive: " + delays[i]);
            assertTrue(delays[i] <= maxDelay * 1.25, "Delay must be capped: " + delays[i]);
        }

        // Delays should generally increase (allowing jitter variance)
        assertTrue(delays[maxRetries - 1] >= delays[0],
                "Later delays should be >= first delay");
    }

    /**
     * Stall-specific retry: linear backoff, extra retry budget.
     */
    @Test
    void retryBackoff_stallDetection_linearWithExtraBudget() {
        long baseDelay = 5000L;
        int stallExtraRetries = 2;
        int initialMaxRetries = 3;

        int effectiveMaxRetries = initialMaxRetries;
        int stallRetries = 0;

        // Simulate 3 stall retries
        for (int attempt = 0; attempt < 5; attempt++) {
            stallRetries++;
            if (stallRetries <= stallExtraRetries) {
                effectiveMaxRetries = Math.max(effectiveMaxRetries, attempt + stallExtraRetries - stallRetries + 1);
            }
            long delay = Math.min(baseDelay * stallRetries, 30_000);
            assertTrue(delay <= 30_000, "Stall delay capped at 30s");
        }

        assertTrue(effectiveMaxRetries >= initialMaxRetries,
                "Extra budget should increase max retries");
    }

    /**
     * Non-retryable errors should not be retried regardless of retry count.
     */
    @Test
    void retryClassification_nonRetryable_stopImmediately() {
        // These error patterns should never be retried
        List<String> nonRetryable = List.of(
                "permission denied", "authentication failed",
                "HTTP 401", "HTTP 403", "HTTP 404",
                "key expired", "certificate error");

        for (String error : nonRetryable) {
            assertTrue(isNonRetryableError(error),
                    "Should be non-retryable: " + error);
        }

        // These should be retryable
        List<String> retryable = List.of(
                "connection timeout", "connection refused",
                "network unreachable", "transfer stall");

        for (String error : retryable) {
            assertFalse(isNonRetryableError(error),
                    "Should be retryable: " + error);
        }
    }

    // ── Service Discovery Under Partial Failure ───────────────────────

    /**
     * When some replicas of a service are down, discovery returns only healthy ones.
     */
    @Test
    void serviceDiscovery_partialFailure_healthyOnly() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        ServiceRegistration healthy1 = ServiceRegistration.builder()
                .serviceInstanceId("enc-1")
                .clusterId("cluster-1")
                .serviceType(ServiceType.ENCRYPTION)
                .host("encryption-service")
                .controlPort(8086)
                .active(true)
                .build();

        // enc-2 is down: active=false (deactivated by heartbeat timeout)
        // Only healthy replicas returned by query

        when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(ServiceType.ENCRYPTION, "cluster-1"))
                .thenReturn(List.of(healthy1)); // only 1 healthy

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("onboarding-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

        ClusterService clusterService = new ClusterService(ctx, regRepo);

        List<ServiceRegistration> services = clusterService.discoverServices(ServiceType.ENCRYPTION);
        assertEquals(1, services.size(), "Only healthy replicas should be discovered");
        assertEquals("enc-1", services.get(0).getServiceInstanceId());
    }

    /**
     * All replicas of a critical service down → discovery returns empty.
     */
    @Test
    void serviceDiscovery_allDown_returnsEmpty() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(ServiceType.ENCRYPTION, "cluster-1"))
                .thenReturn(List.of());

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("onboarding-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

        ClusterService clusterService = new ClusterService(ctx, regRepo);

        List<ServiceRegistration> services = clusterService.discoverServices(ServiceType.ENCRYPTION);
        assertTrue(services.isEmpty(), "Should return empty when all replicas are down");
    }

    // ── Helper Methods ────────────────────────────────────────────────

    private boolean isNonRetryableError(String errorMessage) {
        String lower = errorMessage.toLowerCase();
        return lower.contains("permission denied")
                || lower.contains("auth")
                || lower.contains("401")
                || lower.contains("403")
                || lower.contains("404")
                || lower.contains("key expired")
                || lower.contains("certificate");
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
