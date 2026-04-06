package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.detection.*;
import com.filetransfer.ai.service.intelligence.*;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for the AI engine verdict path.
 *
 * Uses small sample sizes (10K-50K) to measure per-verdict latency,
 * then extrapolates to production-scale (millions). This avoids
 * burning CPU while providing accurate projections.
 *
 * Two profiles are tested:
 * - Core path: 4 base analyzers only (production verdict hot path)
 * - Full AI stack: with MITRE, network analysis, explainability
 */
class ProxyIntelligenceLoadTest {

    private IpReputationService reputationService;
    private ProtocolThreatDetector threatDetector;
    private ConnectionPatternAnalyzer patternAnalyzer;
    private GeoAnomalyDetector geoDetector;

    private static final String[] PROTOCOLS = {"SSH", "FTP", "FTPS", "HTTP", "TLS", "SFTP"};
    private static final int[] PORTS = {22, 21, 990, 80, 443, 2222, 8080, 8443};

    @BeforeEach
    void setUp() {
        reputationService = new IpReputationService();
        threatDetector = new ProtocolThreatDetector();
        patternAnalyzer = new ConnectionPatternAnalyzer();
        geoDetector = new GeoAnomalyDetector();
    }

    private ProxyIntelligenceService coreService() {
        return new ProxyIntelligenceService(
            reputationService, threatDetector, patternAnalyzer, geoDetector,
            new LlmSecurityEscalation(), null, null, null, null, null, null);
    }

    private ProxyIntelligenceService fullService() {
        return new ProxyIntelligenceService(
            reputationService, threatDetector, patternAnalyzer, geoDetector,
            new LlmSecurityEscalation(), null, new MitreAttackMapper(), new NetworkBehaviorAnalyzer(),
            new AttackChainDetector(), new ExplainabilityEngine(), null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Benchmark: measures actual latency, extrapolates to millions
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void performanceBenchmark_withReport() throws Exception {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║     AI ENGINE — PERFORMANCE BENCHMARK & PROJECTION REPORT    ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 1. Core Verdict Path (what end users wait on) ──────────────
        ProxyIntelligenceService core = coreService();
        Random rng = new Random(42);

        // JIT warm-up
        for (int i = 0; i < 5_000; i++) {
            core.computeVerdict(randomIp(rng), randomPort(rng), randomProtocol(rng));
        }
        setUp(); // reset state
        core = coreService();
        rng = new Random(42);

        int sampleSize = 50_000;
        long[] coreLatencies = benchmark(core, sampleSize, rng);
        printProfile("CORE VERDICT PATH (4 analyzers, no enhanced AI)", sampleSize, coreLatencies);

        // ── 2. Blocklist fast-path ─────────────────────────────────────
        setUp();
        ProxyIntelligenceService blockCore = coreService();
        for (int i = 0; i < 500; i++) {
            reputationService.blockIp("10.99." + (i / 256) + "." + (i % 256), "bench");
        }
        rng = new Random(7);
        int blockSample = 20_000;
        long[] blockLatencies = new long[blockSample];
        for (int i = 0; i < blockSample; i++) {
            int idx = rng.nextInt(500);
            String ip = "10.99." + (idx / 256) + "." + (idx % 256);
            long t0 = System.nanoTime();
            blockCore.computeVerdict(ip, 22, "SSH");
            blockLatencies[i] = System.nanoTime() - t0;
        }
        Arrays.sort(blockLatencies);
        printProfile("BLOCKLIST FAST-PATH (blocked IP → instant BLACKHOLE)", blockSample, blockLatencies);

        // ── 3. Full AI Stack ───────────────────────────────────────────
        setUp();
        ProxyIntelligenceService full = fullService();
        rng = new Random(77);
        // Warm up enhanced services
        for (int i = 0; i < 2_000; i++) {
            full.computeVerdict(randomIp(rng), randomPort(rng), randomProtocol(rng));
        }
        setUp();
        full = fullService();
        rng = new Random(77);

        int fullSample = 10_000;
        long[] fullLatencies = benchmark(full, fullSample, rng);
        printProfile("FULL AI STACK (MITRE + network + explainability)", fullSample, fullLatencies);

        // ── 4. Multi-thread core path ──────────────────────────────────
        setUp();
        ProxyIntelligenceService mtCore = coreService();
        int threads = 8;
        int perThread = 5_000;
        AtomicLong totalOps = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        ConcurrentLinkedQueue<Long> mtSamples = new ConcurrentLinkedQueue<>();

        // Warm up
        for (int i = 0; i < 2_000; i++) {
            mtCore.computeVerdict("10.0." + (i % 256) + "." + (i % 256), 22, "SSH");
        }

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Instant mtStart = Instant.now();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tid = t;
            futures.add(exec.submit(() -> {
                Random r = new Random(tid * 1000L);
                try { barrier.await(); } catch (Exception e) { return; }
                for (int i = 0; i < perThread; i++) {
                    try {
                        long t0 = System.nanoTime();
                        mtCore.computeVerdict(randomIp(r), randomPort(r), randomProtocol(r));
                        long lat = System.nanoTime() - t0;
                        totalOps.incrementAndGet();
                        if (i % 5 == 0) mtSamples.add(lat);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        Duration mtElapsed = Duration.between(mtStart, Instant.now());
        exec.shutdown();

        long[] mtSorted = mtSamples.stream().mapToLong(Long::longValue).sorted().toArray();
        int mtCount = mtSorted.length;
        double mtThroughput = totalOps.get() / (mtElapsed.toMillis() / 1000.0);

        System.out.println("─── MULTI-THREAD (" + threads + " threads, " +
            (threads * perThread / 1000) + "K total) ───");
        System.out.printf("  Throughput:     %,.0f verdicts/sec%n", mtThroughput);
        System.out.printf("  P50:            %.1f µs%n", mtSorted[(int)(mtCount * 0.50)] / 1_000.0);
        System.out.printf("  P99:            %.1f µs%n", mtSorted[(int)(mtCount * 0.99)] / 1_000.0);
        System.out.printf("  Errors:         %d%n", errors.get());
        System.out.println();

        // ── 5. Mixed workload (repeat + blocked + new) ─────────────────
        setUp();
        ProxyIntelligenceService mixCore = coreService();
        List<String> knownIps = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String ip = "10.1.0." + (i + 1);
            knownIps.add(ip);
            for (int j = 0; j < 5; j++) {
                reputationService.recordConnection(ip, "SSH");
                reputationService.recordSuccess(ip);
            }
        }
        List<String> blockedIps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String ip = "192.168.99." + i;
            blockedIps.add(ip);
            reputationService.blockIp(ip, "bench");
        }

        rng = new Random(99);
        int mixSample = 20_000;
        long[] mixLatencies = new long[mixSample];
        int allow = 0, throttle = 0, block = 0, blackhole = 0;

        for (int i = 0; i < mixSample; i++) {
            String ip;
            int roll = rng.nextInt(100);
            if (roll < 60) ip = knownIps.get(rng.nextInt(knownIps.size()));
            else if (roll < 65) ip = blockedIps.get(rng.nextInt(blockedIps.size()));
            else ip = randomIp(rng);

            long t0 = System.nanoTime();
            Verdict v = mixCore.computeVerdict(ip, randomPort(rng), randomProtocol(rng));
            mixLatencies[i] = System.nanoTime() - t0;

            switch (v.action()) {
                case ALLOW -> allow++;
                case THROTTLE -> throttle++;
                case BLOCK -> block++;
                case BLACKHOLE -> blackhole++;
                default -> {}
            }
        }
        Arrays.sort(mixLatencies);
        System.out.println("─── MIXED WORKLOAD (60% repeat, 5% blocked, 35% new) ───");
        System.out.printf("  P50: %.1f µs   P95: %.1f µs   P99: %.1f µs%n",
            mixLatencies[(int)(mixSample * 0.50)] / 1_000.0,
            mixLatencies[(int)(mixSample * 0.95)] / 1_000.0,
            mixLatencies[(int)(mixSample * 0.99)] / 1_000.0);
        System.out.printf("  Verdicts: ALLOW=%d  THROTTLE=%d  BLOCK=%d  BLACKHOLE=%d%n",
            allow, throttle, block, blackhole);
        System.out.println();

        // ═══════════════════════════════════════════════════════════════
        //  EXTRAPOLATED PROJECTIONS
        // ═══════════════════════════════════════════════════════════════

        double coreAvg = Arrays.stream(coreLatencies).average().orElse(0) / 1_000.0;
        double blockAvg = Arrays.stream(blockLatencies).average().orElse(0) / 1_000.0;
        double fullAvg = Arrays.stream(fullLatencies).average().orElse(0) / 1_000.0;
        double coreP99 = coreLatencies[(int)(sampleSize * 0.99)] / 1_000.0;
        double blockP99 = blockLatencies[(int)(blockSample * 0.99)] / 1_000.0;
        double fullP99 = fullLatencies[(int)(fullSample * 0.99)] / 1_000.0;
        double coreThroughput = 1_000_000.0 / coreAvg;  // verdicts/sec (single thread)

        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║              PRODUCTION PROJECTIONS (extrapolated)            ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Core path single-thread throughput: %,.0f verdicts/sec      %n", coreThroughput);
        System.out.printf("║  Core path 8-thread throughput:      %,.0f verdicts/sec      %n", mtThroughput);
        System.out.printf("║  Core path 16-thread estimate:       %,.0f verdicts/sec      %n", mtThroughput * 1.7);
        System.out.println("║                                                               ║");
        System.out.printf("║  Time for 1M verdicts (core, 1T):    ~%.1f seconds           %n",
            1_000_000 * coreAvg / 1_000_000.0);
        System.out.printf("║  Time for 1M verdicts (core, 8T):    ~%.1f seconds           %n",
            1_000_000.0 / mtThroughput);
        System.out.printf("║  Time for 10M verdicts (core, 8T):   ~%.1f seconds           %n",
            10_000_000.0 / mtThroughput);
        System.out.println("║                                                               ║");
        System.out.println("║  ── Per-Verdict Latency ──                                    ║");
        System.out.printf("║  Blocklist fast-path:  avg=%.1f µs  P99=%.1f µs             %n", blockAvg, blockP99);
        System.out.printf("║  Core verdict path:    avg=%.1f µs  P99=%.1f µs             %n", coreAvg, coreP99);
        System.out.printf("║  Full AI stack:        avg=%.1f µs  P99=%.1f µs             %n", fullAvg, fullP99);
        System.out.println("║                                                               ║");
        System.out.println("║  ── End-User Impact ──                                        ║");
        System.out.printf("║  Verdict overhead vs connection RTT (~50ms): %.2f%%           %n",
            coreP99 / 50_000 * 100);
        System.out.printf("║  Verdict overhead vs file transfer (~500ms): %.3f%%           %n",
            coreP99 / 500_000 * 100);
        System.out.println("║                                                               ║");
        System.out.println("║  ── Scaling Estimates (production, 4-core VM) ──              ║");
        System.out.printf("║  Sustained capacity:   ~%,.0f verdicts/sec (8 threads)      %n", mtThroughput * 0.8);
        System.out.printf("║  Peak burst capacity:  ~%,.0f verdicts/sec                  %n", mtThroughput * 1.2);
        System.out.printf("║  Daily capacity (80%%): ~%,.0f M verdicts/day               %n",
            mtThroughput * 0.8 * 86400 / 1_000_000);
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Performance Improvement Recommendations ────────────────────
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║          PERFORMANCE IMPROVEMENT RECOMMENDATIONS             ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("║                                                               ║");
        System.out.println("║  1. ASYNC ALERT ENRICHMENT (high impact)                     ║");
        System.out.println("║     raiseAlert() currently runs MITRE mapping, attack chain,  ║");
        System.out.println("║     and explainability synchronously on the verdict thread.   ║");
        System.out.println("║     → Move to CompletableFuture.runAsync() with dedicated     ║");
        System.out.println("║       thread pool. Verdict returns immediately; enrichment    ║");
        System.out.println("║       completes in background.                                ║");
        System.out.println("║     → Expected gain: 2-5x for high-risk verdicts              ║");
        System.out.println("║                                                               ║");
        System.out.println("║  2. VERDICT CACHING (medium impact)                          ║");
        System.out.println("║     Same IP hitting multiple ports within TTL window gets     ║");
        System.out.println("║     recomputed. Add Caffeine cache keyed by (IP, 30s bucket).║");
        System.out.println("║     → Expected gain: 40-60% reduction for repeat IPs          ║");
        System.out.println("║                                                               ║");
        System.out.println("║  3. RING BUFFER LOCK-FREE (low impact)                       ║");
        System.out.println("║     recentVerdicts uses synchronized(Deque). Replace with     ║");
        System.out.println("║     lock-free ring buffer (array + AtomicInteger index).      ║");
        System.out.println("║     → Expected gain: eliminates contention under concurrency  ║");
        System.out.println("║                                                               ║");
        System.out.println("║  4. PATTERN ANALYZER EVICTION (stability)                    ║");
        System.out.println("║     ConnectionPatternAnalyzer grows unbounded with unique IPs.║");
        System.out.println("║     Add LRU eviction or time-based expiry (already scheduled  ║");
        System.out.println("║     at 24h, but should also cap at ~100K entries).            ║");
        System.out.println("║     → Expected gain: stable memory under sustained load       ║");
        System.out.println("║                                                               ║");
        System.out.println("║  5. BATCH VERDICT API (throughput)                            ║");
        System.out.println("║     For proxies handling connection bursts, add batch verdict  ║");
        System.out.println("║     endpoint that processes N IPs in one call, reducing HTTP   ║");
        System.out.println("║     overhead.                                                 ║");
        System.out.println("║     → Expected gain: 3-5x throughput for burst scenarios      ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");

        // ── Assertions (sanity checks) ─────────────────────────────────
        assertTrue(coreP99 < 1000,
            "Core verdict P99 must be < 1ms, was " + coreP99 + " µs");
        assertTrue(blockP99 < 100,
            "Blocklist P99 must be < 100µs, was " + blockP99 + " µs");
        assertEquals(0, errors.get(), "Zero errors under concurrent load");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private long[] benchmark(ProxyIntelligenceService service, int count, Random rng) {
        long[] latencies = new long[count];
        for (int i = 0; i < count; i++) {
            long t0 = System.nanoTime();
            service.computeVerdict(randomIp(rng), randomPort(rng), randomProtocol(rng));
            latencies[i] = System.nanoTime() - t0;
        }
        Arrays.sort(latencies);
        return latencies;
    }

    private void printProfile(String name, int count, long[] sorted) {
        double avg = Arrays.stream(sorted).average().orElse(0) / 1_000.0;
        double p50 = sorted[(int)(count * 0.50)] / 1_000.0;
        double p95 = sorted[(int)(count * 0.95)] / 1_000.0;
        double p99 = sorted[(int)(count * 0.99)] / 1_000.0;
        double p999 = sorted[(int)(count * 0.999)] / 1_000.0;
        double max = sorted[count - 1] / 1_000.0;
        double throughput = 1_000_000.0 / avg;

        System.out.println("─── " + name + " (" + count / 1000 + "K sample) ───");
        System.out.printf("  Avg: %.1f µs   P50: %.1f µs   P95: %.1f µs   P99: %.1f µs   P99.9: %.1f µs   Max: %.1f µs%n",
            avg, p50, p95, p99, p999, max);
        System.out.printf("  Est. single-thread throughput: %,.0f verdicts/sec%n", throughput);
        System.out.println();
    }

    private String randomIp(Random rng) {
        return rng.nextInt(224) + "." + rng.nextInt(256) + "." +
               rng.nextInt(256) + "." + (1 + rng.nextInt(254));
    }

    private int randomPort(Random rng) {
        return PORTS[rng.nextInt(PORTS.length)];
    }

    private String randomProtocol(Random rng) {
        return PROTOCOLS[rng.nextInt(PROTOCOLS.length)];
    }
}
