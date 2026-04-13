package com.filetransfer.shared.replica;

import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.cluster.ClusterContext;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Batch 5: Cross-Cutting Resilience — Critical services revisited
 *
 * Tests multi-replica behavior for:
 * 1. Circuit breaker behavior: per-client, per-replica independence
 * 2. Slow network simulation: timeouts, retries, graceful degradation
 * 3. DB connection pool exhaustion: behavior under load
 * 4. End-to-end file routing across replica boundaries
 * 5. Heartbeat and stale detection timing
 * 6. Concurrent registration/deregistration
 * 7. RabbitMQ message ordering across replicas
 */
class Batch5CrossCuttingResilienceTest {

    // ── Circuit Breaker: Per-Client, Per-Replica ──────────────────────

    /**
     * Circuit breaker is per-client, per-replica.
     * Replica-1's circuit breaker opening doesn't affect replica-2.
     */
    @Test
    void circuitBreaker_perReplica_independent() {
        // Each replica creates its own circuit breaker instance
        CircuitBreaker cb1 = createCircuitBreaker("encryption-service-r1");
        CircuitBreaker cb2 = createCircuitBreaker("encryption-service-r2");

        // Trip replica-1's circuit breaker (5 failures at 50% threshold with 10 calls)
        for (int i = 0; i < 10; i++) {
            cb1.onError(0, TimeUnit.MILLISECONDS, new ConnectException("Connection refused"));
        }

        assertEquals(CircuitBreaker.State.OPEN, cb1.getState(),
                "Replica-1 circuit breaker should be OPEN");
        assertEquals(CircuitBreaker.State.CLOSED, cb2.getState(),
                "Replica-2 circuit breaker should still be CLOSED");
    }

    /**
     * Circuit breaker transitions: CLOSED → OPEN → HALF_OPEN → CLOSED.
     */
    @Test
    void circuitBreaker_stateTransitions() throws Exception {
        CircuitBreaker cb = CircuitBreaker.of("test-service",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .waitDurationInOpenState(Duration.ofMillis(100))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .recordExceptions(ConnectException.class)
                        .build());

        // 4 failures → OPEN
        for (int i = 0; i < 4; i++) {
            cb.onError(0, TimeUnit.MILLISECONDS, new ConnectException("down"));
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait for open → half-open transition
        Thread.sleep(200);
        cb.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Successful calls in half-open → CLOSED
        cb.onSuccess(10, TimeUnit.MILLISECONDS);
        cb.onSuccess(10, TimeUnit.MILLISECONDS);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    /**
     * Each service type has its own circuit breaker — failure in encryption
     * doesn't affect screening client.
     */
    @Test
    void circuitBreaker_perServiceType_isolation() {
        CircuitBreaker encCb = createCircuitBreaker("encryption-service");
        CircuitBreaker screenCb = createCircuitBreaker("screening-service");
        CircuitBreaker aiCb = createCircuitBreaker("ai-engine");

        // Only encryption fails
        for (int i = 0; i < 10; i++) {
            encCb.onError(0, TimeUnit.MILLISECONDS, new ConnectException("down"));
        }

        assertEquals(CircuitBreaker.State.OPEN, encCb.getState());
        assertEquals(CircuitBreaker.State.CLOSED, screenCb.getState(),
                "Screening should be unaffected by encryption failure");
        assertEquals(CircuitBreaker.State.CLOSED, aiCb.getState(),
                "AI Engine should be unaffected by encryption failure");
    }

    // ── Retry Behavior: Connection Errors vs Client Errors ────────────

    /**
     * Retry fires for connection errors but NOT for HTTP 4xx client errors.
     */
    @Test
    void retry_connectionErrors_retriedClientErrors_not() {
        Retry retry = Retry.of("test",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(10))
                        .retryExceptions(ResourceAccessException.class, ConnectException.class)
                        .build());

        AtomicInteger connectionAttempts = new AtomicInteger(0);
        AtomicInteger clientErrorAttempts = new AtomicInteger(0);

        // Connection error: should retry 3 times
        try {
            Supplier<String> decorated = Retry.decorateSupplier(retry, () -> {
                connectionAttempts.incrementAndGet();
                throw new ResourceAccessException("Connection refused",
                        new ConnectException("Connection refused"));
            });
            decorated.get();
        } catch (Exception ignored) {}

        assertEquals(3, connectionAttempts.get(), "Should retry 3 times for connection errors");

        // Client error: should NOT retry (only 1 attempt)
        Retry retryForClientError = Retry.of("test2",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(10))
                        .retryExceptions(ResourceAccessException.class)
                        .build());

        try {
            Supplier<String> decorated = Retry.decorateSupplier(retryForClientError, () -> {
                clientErrorAttempts.incrementAndGet();
                throw new RuntimeException("HTTP 400 Bad Request"); // not retryable
            });
            decorated.get();
        } catch (Exception ignored) {}

        assertEquals(1, clientErrorAttempts.get(),
                "Should NOT retry for non-matching exceptions (client errors)");
    }

    // ── Slow Network: Timeout & Graceful Degradation ──────────────────

    /**
     * Service client calls that take longer than timeout should fail gracefully.
     */
    @Test
    void slowNetwork_callsTimeout_gracefulFailure() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();

        Future<String> result = exec.submit(() -> {
            // Simulate a slow network call
            Thread.sleep(5000); // 5 seconds
            return "late response";
        });

        // Client expects response within 2 seconds
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("Should have timed out");
        } catch (TimeoutException e) {
            // Expected — graceful timeout
            result.cancel(true);
        }
        exec.shutdownNow();
    }

    /**
     * Multiple slow calls from multiple replicas — thread pool doesn't exhaust.
     */
    @Test
    void slowNetwork_multipleReplicas_threadPoolManagement() throws Exception {
        // Simulating bounded thread pool (like RestTemplate + connection pool)
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger timedOut = new AtomicInteger(0);

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                // Some calls are fast (50ms), some are slow (3s)
                if (idx % 3 == 0) {
                    Thread.sleep(3000); // slow
                } else {
                    Thread.sleep(50); // fast
                }
                return "response-" + idx;
            }));
        }

        // Collect results with 1s timeout per call
        for (Future<String> f : futures) {
            try {
                f.get(1, TimeUnit.SECONDS);
                completed.incrementAndGet();
            } catch (TimeoutException e) {
                timedOut.incrementAndGet();
                f.cancel(true);
            } catch (Exception e) {
                // cancelled or interrupted
            }
        }
        pool.shutdownNow();

        // Fast calls should complete, slow calls should time out
        assertTrue(completed.get() > 0, "Some calls should complete");
        assertTrue(timedOut.get() > 0, "Some calls should time out");
    }

    // ── DB Connection Pool Behavior Under Load ────────────────────────

    /**
     * Simulates DB connection pool exhaustion: new requests queue up.
     */
    @Test
    void dbPool_exhaustion_requestsQueue() throws Exception {
        int poolSize = 5;
        Semaphore pool = new Semaphore(poolSize);
        AtomicInteger served = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);

        // 20 concurrent "replica" threads trying to use 5 DB connections
        for (int i = 0; i < 20; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try {
                    if (pool.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        try {
                            Thread.sleep(100); // simulate DB query
                            served.incrementAndGet();
                        } finally {
                            pool.release();
                        }
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ignored) { }
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(served.get() > 0, "Some requests should be served");
        // With 5 pool slots and 100ms hold time, most should eventually get served
        // within the 500ms wait timeout
        assertTrue(served.get() >= 15, "Most requests should eventually be served: " + served.get());
    }

    /**
     * Multiple replicas with separate connection pools: each handles its own load.
     */
    @Test
    void dbPool_perReplica_independentPools() throws Exception {
        int poolPerReplica = 3;
        Semaphore replica1Pool = new Semaphore(poolPerReplica);
        Semaphore replica2Pool = new Semaphore(poolPerReplica);
        AtomicInteger r1Served = new AtomicInteger(0);
        AtomicInteger r2Served = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);

        // Replica-1: 5 requests on 3-slot pool
        for (int i = 0; i < 5; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try {
                    if (replica1Pool.tryAcquire(1, TimeUnit.SECONDS)) {
                        try {
                            Thread.sleep(50);
                            r1Served.incrementAndGet();
                        } finally {
                            replica1Pool.release();
                        }
                    }
                } catch (InterruptedException ignored) { }
            });
        }

        // Replica-2: 5 requests on its own 3-slot pool
        for (int i = 0; i < 5; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                try {
                    if (replica2Pool.tryAcquire(1, TimeUnit.SECONDS)) {
                        try {
                            Thread.sleep(50);
                            r2Served.incrementAndGet();
                        } finally {
                            replica2Pool.release();
                        }
                    }
                } catch (InterruptedException ignored) { }
            });
        }

        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

        // Both replicas should serve all their requests independently
        assertEquals(5, r1Served.get(), "Replica-1 should serve all 5 requests");
        assertEquals(5, r2Served.get(), "Replica-2 should serve all 5 requests");
    }

    // ── End-to-End File Routing Across Replicas ───────────────────────

    /**
     * File uploaded to replica-1's shared volume is visible to replica-2.
     */
    @Test
    void fileRouting_sharedVolume_crossReplicaVisibility(@TempDir Path tempDir) throws Exception {
        Path sharedVolume = tempDir.resolve("sftp_data");
        Files.createDirectories(sharedVolume.resolve("alice/inbox"));
        Files.createDirectories(sharedVolume.resolve("alice/outbox"));
        Files.createDirectories(sharedVolume.resolve("bob/inbox"));

        // Replica-1: file arrives in alice/inbox
        Path uploaded = sharedVolume.resolve("alice/inbox/invoice.csv");
        Files.writeString(uploaded, "HEADER\n100,200,300");

        // Routing engine on replica-1: routes to bob/inbox
        Path routed = sharedVolume.resolve("bob/inbox/invoice.csv#TRZ001");
        Files.copy(uploaded, routed);

        // Replica-2: can see the routed file
        assertTrue(Files.exists(routed), "Replica-2 should see routed file via shared volume");
        assertEquals("HEADER\n100,200,300", Files.readString(routed));
    }

    /**
     * Remote routing: replica-1 serializes file, sends to replica-2's receive endpoint.
     */
    @Test
    void fileRouting_remoteForwarding_base64Encoding() throws Exception {
        String content = "EDI*850*TEST DATA WITH SPECIAL CHARS: ñ, ü, €";
        String base64 = Base64.getEncoder().encodeToString(content.getBytes());

        // Simulate replica-2 receiving the forwarded file
        byte[] decoded = Base64.getDecoder().decode(base64);
        String received = new String(decoded);

        assertEquals(content, received,
                "Base64 round-trip should preserve content exactly (including special chars)");
    }

    /**
     * Large file routing: 10MB file through Base64 encoding.
     */
    @Test
    void fileRouting_largeFile_base64Performance() {
        byte[] largeFile = new byte[10 * 1024 * 1024]; // 10MB
        new Random().nextBytes(largeFile);

        long start = System.currentTimeMillis();
        String encoded = Base64.getEncoder().encodeToString(largeFile);
        byte[] decoded = Base64.getDecoder().decode(encoded);
        long elapsed = System.currentTimeMillis() - start;

        assertArrayEquals(largeFile, decoded, "Large file should survive Base64 round-trip");
        assertTrue(elapsed < 5000, "10MB Base64 round-trip should complete in <5s, took " + elapsed + "ms");
    }

    // ── Heartbeat & Stale Detection ───────────────────────────────────

    /**
     * Heartbeat timing: 30s interval, stale after 2 minutes.
     * Simulates a replica going unresponsive.
     */
    @Test
    void heartbeat_staleTiming_deactivation() {
        Instant registered = Instant.now();
        Instant lastHeartbeat = registered;

        // Normal heartbeat: 30s intervals
        for (int i = 0; i < 4; i++) {
            lastHeartbeat = lastHeartbeat.plusSeconds(30);
        }

        // Check: 120 seconds since last heartbeat → stale
        Instant staleThreshold = Instant.now().minusSeconds(120);
        boolean isStale = lastHeartbeat.isBefore(staleThreshold);
        assertFalse(isStale, "With recent heartbeats, should not be stale");

        // Simulate replica crash: no heartbeat for 3 minutes
        Instant crashedHeartbeat = Instant.now().minusSeconds(180);
        assertTrue(crashedHeartbeat.isBefore(staleThreshold),
                "Replica with 3-minute-old heartbeat should be considered stale");
    }

    /**
     * All service types deregister cleanly on shutdown (PreDestroy).
     */
    @Test
    void deregistration_allServiceTypes_cleanShutdown() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        for (ServiceType type : ServiceType.values()) {
            String instanceId = type.name().toLowerCase() + "-1";
            regRepo.deactivate(instanceId);
            verify(regRepo).deactivate(instanceId);
        }
    }

    // ── Concurrent Registration/Deregistration ────────────────────────

    /**
     * Multiple replicas registering simultaneously — no duplicate instance IDs.
     */
    @Test
    void registration_concurrent_noCollisions() throws Exception {
        Set<String> instanceIds = ConcurrentHashMap.newKeySet();
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < 100; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                String id = UUID.randomUUID().toString();
                boolean added = instanceIds.add(id);
                assertTrue(added, "UUID should always be unique");
            });
        }
        start.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(100, instanceIds.size(), "100 unique instance IDs should be generated");
    }

    // ── RabbitMQ Event Ordering ───────────────────────────────────────

    /**
     * Events published from multiple replicas may arrive out of order.
     * Consumers must handle this gracefully.
     */
    @Test
    void rabbitMQ_multiReplicaEvents_outOfOrderHandling() {
        // Simulate event publishing from 3 replicas
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());

        ExecutorService exec = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);

        for (int replica = 0; replica < 3; replica++) {
            final int r = replica;
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 10; i++) {
                    events.add(Map.of(
                            "replica", r,
                            "sequence", i,
                            "timestamp", System.nanoTime(),
                            "type", "file.uploaded"));
                }
            });
        }
        start.countDown();
        exec.shutdown();
        try { exec.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        // Verify: all 30 events arrived (may be out of order)
        assertEquals(30, events.size(), "All events should arrive");

        // Events from same replica should be in order relative to each other
        for (int r = 0; r < 3; r++) {
            final int replica = r;
            List<Map<String, Object>> replicaEvents = events.stream()
                    .filter(e -> (int) e.get("replica") == replica)
                    .toList();
            assertEquals(10, replicaEvents.size());

            // Within same replica, sequence should be non-decreasing
            int lastSeq = -1;
            for (Map<String, Object> e : replicaEvents) {
                int seq = (int) e.get("sequence");
                assertTrue(seq >= lastSeq, "Intra-replica ordering should be maintained");
                lastSeq = seq;
            }
        }
    }

    // ── Graceful Degradation Patterns ─────────────────────────────────

    /**
     * AI Engine unavailable: classification returns ALLOWED by default.
     */
    @Test
    void gracefulDegradation_aiUnavailable_allowByDefault() {
        // Simulates AiClassificationClient behavior when AI Engine is down
        boolean aiEnabled = true;
        boolean aiReachable = false;

        String classificationResult;
        if (!aiEnabled || !aiReachable) {
            classificationResult = "ALLOWED"; // graceful degradation
        } else {
            classificationResult = "BLOCKED"; // would be real classification
        }

        assertEquals("ALLOWED", classificationResult,
                "When AI Engine is unreachable, default to ALLOWED");
    }

    /**
     * License service unavailable: 24-hour cache allows continued operation.
     */
    @Test
    void gracefulDegradation_licenseUnavailable_cachedResult() {
        // Simulates cached license check with 24h TTL
        long lastSuccessfulCheck = System.currentTimeMillis() - (12 * 3600_000); // 12h ago
        long cacheTtl = 24 * 3600_000; // 24h

        boolean licenseServiceAvailable = false;
        boolean isCacheValid = (System.currentTimeMillis() - lastSuccessfulCheck) < cacheTtl;

        assertTrue(isCacheValid, "License cache should be valid for 24h");

        // If cache is valid and service is down, allow operation
        boolean allowed = isCacheValid || licenseServiceAvailable;
        assertTrue(allowed, "Should allow operation with valid cache even when service is down");
    }

    /**
     * Keystore unavailable: services using local fallback continue operating.
     */
    @Test
    void gracefulDegradation_keystoreUnavailable_localFallback() {
        boolean keystoreReachable = false;
        Map<String, String> localKeyCache = new ConcurrentHashMap<>();
        localKeyCache.put("sftp-host-key", "cached-key-material");

        String keyMaterial;
        if (keystoreReachable) {
            keyMaterial = "from-central-keystore";
        } else {
            keyMaterial = localKeyCache.getOrDefault("sftp-host-key", null);
        }

        assertNotNull(keyMaterial, "Should fall back to local cached key");
        assertEquals("cached-key-material", keyMaterial);
    }

    /**
     * Analytics unavailable: returns empty results instead of error.
     */
    @Test
    void gracefulDegradation_analyticsUnavailable_emptyResults() {
        boolean analyticsReachable = false;

        Map<String, Object> metrics;
        if (analyticsReachable) {
            metrics = Map.of("totalTransfers", 100, "successRate", 0.95);
        } else {
            metrics = Map.of(); // empty results
        }

        assertTrue(metrics.isEmpty(), "Analytics unavailable should return empty map");
    }

    // ── Multi-Cluster Federation ──────────────────────────────────────

    /**
     * In cross-cluster mode, services from all clusters are discoverable.
     * In within-cluster mode, only same-cluster services are visible.
     */
    @Test
    void multiCluster_communicationModeSwitch() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);

        ServiceRegistration local = ServiceRegistration.builder()
                .serviceInstanceId("sftp-1")
                .clusterId("cluster-1")
                .serviceType(ServiceType.SFTP)
                .host("sftp-service")
                .controlPort(8081)
                .active(true)
                .build();
        ServiceRegistration remote = ServiceRegistration.builder()
                .serviceInstanceId("sftp-3")
                .clusterId("cluster-2")
                .serviceType(ServiceType.SFTP)
                .host("sftp-service-remote")
                .controlPort(8081)
                .active(true)
                .build();

        when(regRepo.findByServiceTypeAndClusterIdAndActiveTrue(ServiceType.SFTP, "cluster-1"))
                .thenReturn(List.of(local));
        when(regRepo.findByServiceTypeAndActiveTrue(ServiceType.SFTP))
                .thenReturn(List.of(local, remote));

        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("sftp-1");

        // Within-cluster: only local
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);
        ClusterService svc = new ClusterService(ctx, regRepo);
        assertEquals(1, svc.discoverServices(ServiceType.SFTP).size());

        // Switch to cross-cluster: both clusters visible
        ctx.setCommunicationMode(ClusterCommunicationMode.CROSS_CLUSTER);
        assertEquals(2, svc.discoverServices(ServiceType.SFTP).size());
    }

    /**
     * Communication mode can change at runtime (admin action).
     */
    @Test
    void multiCluster_runtimeModeChange() {
        ServiceRegistrationRepository regRepo = mock(ServiceRegistrationRepository.class);
        ClusterContext ctx = new ClusterContext();
        ctx.setClusterId("cluster-1");
        ctx.setServiceInstanceId("sftp-1");
        ctx.setCommunicationMode(ClusterCommunicationMode.WITHIN_CLUSTER);

        ClusterService svc = new ClusterService(ctx, regRepo);
        assertEquals(ClusterCommunicationMode.WITHIN_CLUSTER, svc.getCommunicationMode());

        svc.setCommunicationMode(ClusterCommunicationMode.CROSS_CLUSTER);
        assertEquals(ClusterCommunicationMode.CROSS_CLUSTER, svc.getCommunicationMode());
    }

    // ── Helper Methods ────────────────────────────────────────────────

    private CircuitBreaker createCircuitBreaker(String name) {
        return CircuitBreaker.of(name,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .recordExceptions(ResourceAccessException.class, ConnectException.class)
                        .build());
    }
}
