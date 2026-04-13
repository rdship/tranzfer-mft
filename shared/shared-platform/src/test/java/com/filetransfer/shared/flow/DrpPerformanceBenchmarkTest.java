package com.filetransfer.shared.flow;

import com.filetransfer.shared.entity.transfer.FlowEvent;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.flow.builtin.ChecksumVerifyFunction;
import com.filetransfer.shared.matching.CompiledFlowRule;
import com.filetransfer.shared.matching.FlowRuleRegistry;
import com.filetransfer.shared.matching.MatchContext;
import com.filetransfer.shared.repository.FlowEventRepository;
import com.filetransfer.shared.routing.FlowActor;
import com.filetransfer.shared.routing.FlowEventJournal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for every component of the DRP (Durable Reactive Pipeline) engine.
 *
 * Pure JUnit 5 — no Spring context, no @SpringBootTest.
 * Uses reflection for @Value fields, @TempDir for filesystem tests.
 * Warm-up iterations before measurement. Generous CI thresholds (2-3x expected).
 */
class DrpPerformanceBenchmarkTest {

    @TempDir
    Path tempDir;

    // ── Helper infrastructure ───────────────────────────────────────────────

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private Path createTestFile(String name, int sizeBytes) throws Exception {
        Path file = tempDir.resolve(name);
        byte[] data = new byte[sizeBytes];
        new Random(42).nextBytes(data);
        Files.write(file, data);
        return file;
    }

    private long heapUsed() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    record BenchResult(double avgMs, double minMs, double maxMs, double p99Ms, int iterations) {
        void print(String name) {
            System.out.printf("[BENCHMARK] %s: avg=%.3fms min=%.3fms max=%.3fms p99=%.3fms (%d iterations)%n",
                    name, avgMs, minMs, maxMs, p99Ms, iterations);
        }
    }

    private BenchResult runBench(String name, int warmup, int iterations, Runnable task) {
        for (int i = 0; i < warmup; i++) task.run();
        long[] times = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            task.run();
            times[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(times);
        double avg = java.util.Arrays.stream(times).average().orElse(0) / 1_000_000.0;
        double min = times[0] / 1_000_000.0;
        double max = times[iterations - 1] / 1_000_000.0;
        double p99 = times[(int) (iterations * 0.99)] / 1_000_000.0;
        var result = new BenchResult(avg, min, max, p99, iterations);
        result.print(name);
        return result;
    }

    // ── Inline I/O engine (mirrors storage-manager's ParallelIOEngine) ─────
    // ParallelIOEngine lives in storage-manager which depends on shared-platform
    // (circular dependency). We inline the core I/O logic here for benchmarking.

    private static final int STRIPE_SIZE_KB = 4096;
    private static final int IO_THREADS = 8;
    private static final int WRITE_BUFFER_MB = 64;
    private ExecutorService ioPool;

    @BeforeEach
    void initIOPool() {
        ioPool = Executors.newFixedThreadPool(IO_THREADS, r -> {
            Thread t = new Thread(r, "bench-io-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void shutdownIOPool() {
        if (ioPool != null) ioPool.shutdownNow();
    }

    /** Direct (non-striped) write — mirrors ParallelIOEngine.writeDirect(). */
    private long writeDirect(byte[] data, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        long start = System.nanoTime();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dest), WRITE_BUFFER_MB * 1024)) {
            out.write(data);
        }
        return System.nanoTime() - start;
    }

    /** Striped parallel write — mirrors ParallelIOEngine.writeStriped(). */
    private int writeStriped(byte[] data, Path dest) throws Exception {
        Files.createDirectories(dest.getParent());
        long stripeSize = STRIPE_SIZE_KB * 1024L;
        int stripes = (int) Math.ceil((double) data.length / stripeSize);

        // Write to temp first, then parallel copy
        Path tmp = tempDir.resolve("tmp-" + UUID.randomUUID());
        Files.write(tmp, data);

        try (FileChannel src = FileChannel.open(tmp, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(dest, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            dst.truncate(data.length);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < stripes; i++) {
                final long offset = i * stripeSize;
                final long length = Math.min(stripeSize, data.length - offset);
                futures.add(ioPool.submit(() -> {
                    try {
                        ByteBuffer buf = ByteBuffer.allocate((int) length);
                        src.read(buf, offset);
                        buf.flip();
                        dst.write(buf, offset);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return stripes;
    }

    /** Streaming read via transferTo — mirrors ParallelIOEngine.readTo(). */
    private void readTo(Path source, OutputStream target) throws Exception {
        try (FileChannel fc = FileChannel.open(source, StandardOpenOption.READ)) {
            var out = java.nio.channels.Channels.newChannel(target);
            long pos = 0, rem = fc.size();
            while (rem > 0) {
                long transferred = fc.transferTo(pos, rem, out);
                pos += transferred;
                rem -= transferred;
            }
        }
    }

    /** Old-path read (full materialization) — mirrors ParallelIOEngine.read(). */
    private byte[] readFull(Path source) throws Exception {
        return Files.readAllBytes(source);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section A: Function Execution Benchmarks (7 tests)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void benchmark_checksumVerify_1kb_shouldBeUnder5ms() throws Exception {
        Path file = createTestFile("bench-1kb.dat", 1024);
        var fn = new ChecksumVerifyFunction();

        var result = runBench("checksumVerify_1kb", 10, 100, () -> {
            try {
                fn.executePhysical(new FlowFunctionContext(file, tempDir, Map.of(), "TRK-001", "bench-1kb.dat"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(result.avgMs < 5.0,
                "1KB checksum avg should be under 5ms, was " + result.avgMs + "ms");
    }

    @Test
    void benchmark_checksumVerify_10mb_shouldBeUnder200ms() throws Exception {
        Path file = createTestFile("bench-10mb.dat", 10 * 1024 * 1024);
        var fn = new ChecksumVerifyFunction();

        var result = runBench("checksumVerify_10mb", 10, 100, () -> {
            try {
                fn.executePhysical(new FlowFunctionContext(file, tempDir, Map.of(), "TRK-002", "bench-10mb.dat"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(result.avgMs < 200.0,
                "10MB checksum avg should be under 200ms, was " + result.avgMs + "ms");
    }

    @Test
    void benchmark_registryLookup_shouldBeUnder1microsecond() {
        var registry = new FlowFunctionRegistry();

        // Register 17 functions (16 built-in stubs + 1 ChecksumVerify)
        String[] types = {
                "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
                "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                "RENAME", "SCREEN", "EXECUTE_SCRIPT", "MAILBOX",
                "FILE_DELIVERY", "CONVERT_EDI", "ROUTE", "APPROVE"
        };
        for (String type : types) {
            final String t = type;
            registry.register(new FlowFunction() {
                @Override public String executePhysical(FlowFunctionContext ctx) { return null; }
                @Override public String type() { return t; }
                @Override public IOMode ioMode() { return IOMode.METADATA_ONLY; }
            });
        }
        registry.register(new ChecksumVerifyFunction());
        assertEquals(17, registry.size());

        // Warm up
        for (int i = 0; i < 100; i++) registry.get("ENCRYPT_PGP");

        // Measure 100,000 lookups
        long start = System.nanoTime();
        int lookupCount = 100_000;
        for (int i = 0; i < lookupCount; i++) {
            registry.get(types[i % types.length]);
        }
        long elapsed = System.nanoTime() - start;
        double totalMs = elapsed / 1_000_000.0;
        double throughputOps = lookupCount / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] registryLookup: total=%.3fms for %d lookups (%.0f ops/sec, %.3f us/op)%n",
                totalMs, lookupCount, throughputOps, (totalMs * 1000.0) / lookupCount);

        assertTrue(totalMs < 100.0,
                "100,000 lookups should complete in <100ms, took " + totalMs + "ms");
    }

    @Test
    void benchmark_ruleMatching_100Rules_shouldBeUnder1ms() {
        var registry = new FlowRuleRegistry();

        // Register 100 compiled rules with simple predicate matchers
        Map<UUID, CompiledFlowRule> rules = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            final int idx = i;
            rules.put(id, new CompiledFlowRule(
                    id, "flow-" + i, i,
                    "INBOUND", Set.of("SFTP"),
                    ctx -> ctx.filename() != null && ctx.filename().startsWith("match-" + idx)
            ));
        }
        registry.loadAll(rules);

        // Build a context that matches rule #50 (middle of the list)
        MatchContext ctx = MatchContext.builder()
                .withFilename("match-50-data.csv")
                .withExtension("csv")
                .withDirection(MatchContext.Direction.INBOUND)
                .withProtocol("SFTP")
                .build();

        var result = runBench("ruleMatching_100Rules", 10, 1000, () -> {
            CompiledFlowRule match = registry.findMatch(ctx);
            assertNotNull(match);
        });

        assertTrue(result.avgMs < 1.0,
                "100-rule matching avg should be under 1ms, was " + result.avgMs + "ms");
    }

    @Test
    void benchmark_flowActorReplay_10Events_shouldBeUnder1ms() {
        List<FlowEvent> events = buildReplayEvents(10);

        var result = runBench("flowActorReplay_10Events", 100, 10_000, () -> {
            FlowActor actor = new FlowActor("TRK-PERF-010");
            actor.replayFromJournal(events);
        });

        assertTrue(result.avgMs < 1.0,
                "10-event replay avg should be under 1ms, was " + result.avgMs + "ms");
    }

    @Test
    void benchmark_flowActorReplay_100Events_shouldBeUnder5ms() {
        List<FlowEvent> events = buildReplayEvents(100);

        var result = runBench("flowActorReplay_100Events", 100, 10_000, () -> {
            FlowActor actor = new FlowActor("TRK-PERF-100");
            actor.replayFromJournal(events);
        });

        assertTrue(result.avgMs < 5.0,
                "100-event replay avg should be under 5ms, was " + result.avgMs + "ms");
    }

    @Test
    void benchmark_flowActorReplay_1000Events_shouldBeUnder50ms() {
        List<FlowEvent> events = buildReplayEvents(1000);

        var result = runBench("flowActorReplay_1000Events", 100, 10_000, () -> {
            FlowActor actor = new FlowActor("TRK-PERF-1K");
            actor.replayFromJournal(events);
        });

        assertTrue(result.avgMs < 50.0,
                "1000-event replay avg should be under 50ms, was " + result.avgMs + "ms");
    }

    /** Build a realistic event sequence: STARTED, N*(STEP_STARTED+STEP_COMPLETED), COMPLETED. */
    private List<FlowEvent> buildReplayEvents(int stepCount) {
        List<FlowEvent> events = new ArrayList<>();
        UUID execId = UUID.randomUUID();

        events.add(FlowEvent.builder()
                .trackId("TRK-PERF").executionId(execId)
                .eventType("EXECUTION_STARTED").storageKey("key-init")
                .build());

        for (int i = 0; i < stepCount; i++) {
            events.add(FlowEvent.builder()
                    .trackId("TRK-PERF").executionId(execId)
                    .eventType("STEP_STARTED").stepIndex(i).attemptNumber(1)
                    .build());
            events.add(FlowEvent.builder()
                    .trackId("TRK-PERF").executionId(execId)
                    .eventType("STEP_COMPLETED").stepIndex(i).storageKey("key-step-" + i)
                    .build());
        }

        events.add(FlowEvent.builder()
                .trackId("TRK-PERF").executionId(execId)
                .eventType("EXECUTION_COMPLETED")
                .build());

        return events;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section B: I/O Engine Benchmarks (5 tests)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void benchmark_parallelIO_directWrite_1mb_throughput() throws Exception {
        byte[] data = new byte[1024 * 1024];
        new Random(42).nextBytes(data);

        int iterations = 100;
        // Warm up
        for (int i = 0; i < 10; i++) {
            Path dest = tempDir.resolve("warmup-direct-" + i + ".dat");
            writeDirect(data, dest);
            Files.deleteIfExists(dest);
        }

        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            Path dest = tempDir.resolve("bench-direct-" + i + ".dat");
            totalNanos += writeDirect(data, dest);
            Files.deleteIfExists(dest);
        }

        double totalMs = totalNanos / 1_000_000.0;
        double avgMs = totalMs / iterations;
        double totalMb = (1.0 * iterations);
        double throughput = totalMb / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] parallelIO_directWrite_1mb: throughput=%.2f MB/s (avg=%.3fms per write, %d iterations)%n",
                throughput, avgMs, iterations);

        assertTrue(throughput > 10.0, "Direct 1MB write throughput should exceed 10 MB/s, was " + throughput);
    }

    @Test
    void benchmark_parallelIO_stripedWrite_50mb_throughput() throws Exception {
        byte[] data = new byte[50 * 1024 * 1024];
        new Random(42).nextBytes(data);

        // Warm up
        for (int i = 0; i < 3; i++) {
            Path dest = tempDir.resolve("warmup-striped-" + i + ".dat");
            writeStriped(data, dest);
            Files.deleteIfExists(dest);
        }

        int iterations = 10;
        long totalNanos = 0;
        int stripeCount = 0;
        for (int i = 0; i < iterations; i++) {
            Path dest = tempDir.resolve("bench-striped-" + i + ".dat");
            long start = System.nanoTime();
            stripeCount = writeStriped(data, dest);
            totalNanos += System.nanoTime() - start;
            Files.deleteIfExists(dest);
        }

        double totalMs = totalNanos / 1_000_000.0;
        double totalMb = 50.0 * iterations;
        double throughput = totalMb / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] parallelIO_stripedWrite_50mb: throughput=%.2f MB/s stripes=%d (%d iterations)%n",
                throughput, stripeCount, iterations);

        assertTrue(throughput > 10.0, "Striped 50MB write throughput should exceed 10 MB/s, was " + throughput);
    }

    @Test
    void benchmark_parallelIO_readTo_1mb_throughput() throws Exception {
        Path file = createTestFile("read-1mb.dat", 1024 * 1024);

        int iterations = 100;
        // Warm up
        for (int i = 0; i < 10; i++) {
            readTo(file, OutputStream.nullOutputStream());
        }

        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            readTo(file, OutputStream.nullOutputStream());
            totalNanos += System.nanoTime() - start;
        }

        double totalMs = totalNanos / 1_000_000.0;
        double avgMs = totalMs / iterations;
        double throughput = (1.0 * iterations) / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] parallelIO_readTo_1mb: avg=%.3fms throughput=%.2f MB/s (%d iterations)%n",
                avgMs, throughput, iterations);

        assertTrue(throughput > 50.0, "1MB readTo throughput should exceed 50 MB/s, was " + throughput);
    }

    @Test
    void benchmark_parallelIO_readTo_50mb_throughput() throws Exception {
        Path file = createTestFile("read-50mb.dat", 50 * 1024 * 1024);

        int iterations = 10;
        // Warm up
        for (int i = 0; i < 3; i++) {
            readTo(file, OutputStream.nullOutputStream());
        }

        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            readTo(file, OutputStream.nullOutputStream());
            totalNanos += System.nanoTime() - start;
        }

        double totalMs = totalNanos / 1_000_000.0;
        double throughput = (50.0 * iterations) / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] parallelIO_readTo_50mb: throughput=%.2f MB/s (%d iterations)%n",
                throughput, iterations);

        assertTrue(throughput > 50.0, "50MB readTo throughput should exceed 50 MB/s, was " + throughput);
    }

    @Test
    void benchmark_parallelIO_writeAndRead_roundtrip_10mb() throws Exception {
        byte[] data = new byte[10 * 1024 * 1024];
        new Random(42).nextBytes(data);

        int iterations = 20;
        // Warm up
        for (int i = 0; i < 5; i++) {
            Path dest = tempDir.resolve("warmup-rt-" + i + ".dat");
            writeDirect(data, dest);
            readTo(dest, OutputStream.nullOutputStream());
            Files.deleteIfExists(dest);
        }

        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            Path dest = tempDir.resolve("bench-rt-" + i + ".dat");
            long start = System.nanoTime();
            writeDirect(data, dest);
            readTo(dest, OutputStream.nullOutputStream());
            totalNanos += System.nanoTime() - start;
            Files.deleteIfExists(dest);
        }

        double totalMs = totalNanos / 1_000_000.0;
        double avgMs = totalMs / iterations;
        // Each round-trip moves 10MB write + 10MB read = 20MB effective
        double throughput = (20.0 * iterations) / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] parallelIO_writeAndRead_roundtrip_10mb: avg=%.3fms throughput=%.2f MB/s (%d iterations)%n",
                avgMs, throughput, iterations);

        assertTrue(throughput > 20.0,
                "10MB round-trip throughput should exceed 20 MB/s, was " + throughput);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section C: Concurrency Benchmarks (5 tests)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void benchmark_ioLane_acquireRelease_throughput() throws Exception {
        var laneManager = new IOLaneManager();
        setField(laneManager, "realtimePermits", 8);
        setField(laneManager, "bulkPermits", 4);
        setField(laneManager, "backgroundPermits", 2);
        laneManager.init();

        int cycles = 100_000;
        // Warm up
        for (int i = 0; i < 1000; i++) {
            laneManager.acquire(IOLane.REALTIME);
            laneManager.release(IOLane.REALTIME);
        }

        long start = System.nanoTime();
        for (int i = 0; i < cycles; i++) {
            laneManager.acquire(IOLane.REALTIME);
            laneManager.release(IOLane.REALTIME);
        }
        long elapsed = System.nanoTime() - start;
        double totalMs = elapsed / 1_000_000.0;
        double opsPerSec = cycles / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] ioLane_acquireRelease: %.0f ops/sec (%.3fms for %d cycles)%n",
                opsPerSec, totalMs, cycles);

        assertTrue(opsPerSec > 100_000, "IOLane acquire/release should exceed 100K ops/sec, was " + opsPerSec);
    }

    @Test
    void benchmark_sedaStage_submitProcess_throughput() throws Exception {
        int itemCount = 10_000;
        CountDownLatch latch = new CountDownLatch(itemCount);

        ProcessingStage<Runnable> stage = new ProcessingStage<>("bench-seda", 10_000, 8, item -> {
            item.run();
        });

        // Warm up — submit and drain 1000 items
        CountDownLatch warmupLatch = new CountDownLatch(1000);
        for (int i = 0; i < 1000; i++) {
            stage.submit(warmupLatch::countDown);
        }
        warmupLatch.await(10, TimeUnit.SECONDS);

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < itemCount; i++) {
            stage.submit(latch::countDown);
        }
        latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        stage.shutdown();

        double totalMs = elapsed / 1_000_000.0;
        double itemsPerSec = itemCount / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] sedaStage_submitProcess: %.0f items/sec (%.3fms for %d items)%n",
                itemsPerSec, totalMs, itemCount);

        assertTrue(itemsPerSec > 10_000,
                "SEDA stage should exceed 10K items/sec, was " + itemsPerSec);
    }

    @Test
    void benchmark_concurrentFlows_10_10mbFiles_totalTime() throws Exception {
        int flowCount = 10;
        int fileSizeMb = 10;
        byte[] template = new byte[fileSizeMb * 1024 * 1024];
        new Random(42).nextBytes(template);

        // Pre-create source files
        Path[] sources = new Path[flowCount];
        for (int i = 0; i < flowCount; i++) {
            sources[i] = createTestFile("concurrent-10mb-" + i + ".dat", fileSizeMb * 1024 * 1024);
        }

        long start = System.nanoTime();
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < flowCount; i++) {
                final int idx = i;
                futures.add(scope.submit(() -> {
                    try {
                        Path dest = tempDir.resolve("out-10mb-" + idx + ".dat");
                        writeDirect(Files.readAllBytes(sources[idx]), dest);
                        readTo(dest, OutputStream.nullOutputStream());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        }
        long elapsed = System.nanoTime() - start;
        double totalMs = elapsed / 1_000_000.0;
        double avgMs = totalMs / flowCount;

        System.out.printf("[BENCHMARK] concurrentFlows_10_10mbFiles: total=%.3fms avg=%.3fms/flow (%d flows)%n",
                totalMs, avgMs, flowCount);

        assertTrue(totalMs < 30_000, "10 concurrent 10MB flows should complete in <30s, took " + totalMs + "ms");
    }

    @Test
    void benchmark_concurrentFlows_100_1kbFiles_totalTime() throws Exception {
        int flowCount = 100;

        // Pre-create source files
        Path[] sources = new Path[flowCount];
        for (int i = 0; i < flowCount; i++) {
            sources[i] = createTestFile("concurrent-1kb-" + i + ".dat", 1024);
        }

        long start = System.nanoTime();
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < flowCount; i++) {
                final int idx = i;
                futures.add(scope.submit(() -> {
                    try {
                        Path dest = tempDir.resolve("out-1kb-" + idx + ".dat");
                        writeDirect(Files.readAllBytes(sources[idx]), dest);
                        readTo(dest, OutputStream.nullOutputStream());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        }
        long elapsed = System.nanoTime() - start;
        double totalMs = elapsed / 1_000_000.0;

        System.out.printf("[BENCHMARK] concurrentFlows_100_1kbFiles: total=%.3fms (%d flows)%n",
                totalMs, flowCount);

        assertTrue(totalMs < 10_000, "100 concurrent 1KB flows should complete in <10s, took " + totalMs + "ms");
    }

    @Test
    void benchmark_journalWrite_throughput() throws Exception {
        // Create a mock FlowEventRepository that just counts saves
        AtomicLong saveCount = new AtomicLong();
        FlowEventRepository mockRepo = new FlowEventRepository() {
            @Override public FlowEvent save(FlowEvent entity) {
                saveCount.incrementAndGet();
                return entity;
            }
            @Override public List<FlowEvent> findByTrackIdOrderByCreatedAtAsc(String trackId) { return List.of(); }
            @Override public List<FlowEvent> findByExecutionIdOrderByCreatedAtAsc(UUID executionId) { return List.of(); }
            @Override public List<FlowEvent> findByTrackIdAndEventTypeOrderByCreatedAtAsc(String trackId, String eventType) { return List.of(); }
            @Override public List<FlowEvent> findByCreatedAtAfterOrderByCreatedAtAsc(java.time.Instant after) { return List.of(); }
            @Override public long countByTrackId(String trackId) { return 0; }

            // JpaRepository methods — minimal stubs
            @Override public <S extends FlowEvent> List<S> saveAll(Iterable<S> entities) { return List.of(); }
            @Override public Optional<FlowEvent> findById(UUID uuid) { return Optional.empty(); }
            @Override public boolean existsById(UUID uuid) { return false; }
            @Override public List<FlowEvent> findAll() { return List.of(); }
            @Override public List<FlowEvent> findAllById(Iterable<UUID> uuids) { return List.of(); }
            @Override public long count() { return 0; }
            @Override public void deleteById(UUID uuid) {}
            @Override public void delete(FlowEvent entity) {}
            @Override public void deleteAllById(Iterable<? extends UUID> uuids) {}
            @Override public void deleteAll(Iterable<? extends FlowEvent> entities) {}
            @Override public void deleteAll() {}
            @Override public void flush() {}
            @Override public <S extends FlowEvent> S saveAndFlush(S entity) { return entity; }
            @Override public <S extends FlowEvent> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
            @Override public void deleteAllInBatch(Iterable<FlowEvent> entities) {}
            @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
            @Override public void deleteAllInBatch() {}
            @Override public FlowEvent getOne(UUID uuid) { return null; }
            @Override public FlowEvent getById(UUID uuid) { return null; }
            @Override public FlowEvent getReferenceById(UUID uuid) { return null; }
            @Override public <S extends FlowEvent> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
            @Override public <S extends FlowEvent> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
            @Override public <S extends FlowEvent> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
            @Override public <S extends FlowEvent> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
            @Override public <S extends FlowEvent> long count(org.springframework.data.domain.Example<S> example) { return 0; }
            @Override public <S extends FlowEvent> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
            @Override public <S extends FlowEvent, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
            @Override public List<FlowEvent> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
            @Override public org.springframework.data.domain.Page<FlowEvent> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        };

        var journal = new FlowEventJournal(mockRepo);

        int eventCount = 10_000;
        UUID execId = UUID.randomUUID();

        // Warm up
        for (int i = 0; i < 100; i++) {
            journal.recordStepCompleted("TRK-WARMUP", execId, 0, "COMPRESS_GZIP", "key-0", 1024, 10);
        }

        saveCount.set(0);
        long start = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            journal.recordStepCompleted("TRK-BENCH", execId, i % 10, "COMPRESS_GZIP", "key-" + i, 1024, 10);
        }
        long elapsed = System.nanoTime() - start;

        double totalMs = elapsed / 1_000_000.0;
        double eventsPerSec = eventCount / (totalMs / 1000.0);

        System.out.printf("[BENCHMARK] journalWrite: %.0f events/sec (%.3fms for %d events)%n",
                eventsPerSec, totalMs, eventCount);

        assertTrue(eventsPerSec > 10_000,
                "Journal write throughput should exceed 10K events/sec, was " + eventsPerSec);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section D: Memory Profile Benchmarks (4 tests)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void benchmark_memory_readOldPath_10mb_heapDelta() throws Exception {
        Path file = createTestFile("mem-old-10mb.dat", 10 * 1024 * 1024);

        // Force GC, measure baseline
        System.gc();
        Thread.sleep(100);
        long heapBefore = heapUsed();

        // Old path: full materialization (like ParallelIOEngine.read())
        byte[] data = readFull(file);

        long heapAfter = heapUsed();
        long deltaBytes = heapAfter - heapBefore;
        double deltaMb = deltaBytes / (1024.0 * 1024.0);

        System.out.printf("[BENCHMARK] memory_readOldPath_10mb: heap_delta=%.2f MB%n", deltaMb);

        // The byte[] must be at least 10MB — proves full materialization
        assertTrue(data.length >= 10 * 1024 * 1024, "Data should be 10MB");
        assertTrue(deltaMb >= 9.0,
                "Old path heap delta should be >= 9MB (proves full load), was " + deltaMb + " MB");
    }

    @Test
    void benchmark_memory_readToNewPath_10mb_heapDelta() throws Exception {
        Path file = createTestFile("mem-new-10mb.dat", 10 * 1024 * 1024);

        // Counting OutputStream that discards bytes
        var counter = new OutputStream() {
            long count = 0;
            @Override public void write(int b) { count++; }
            @Override public void write(byte[] b, int off, int len) { count += len; }
        };

        // Force GC, measure baseline
        System.gc();
        Thread.sleep(100);
        long heapBefore = heapUsed();

        // New path: streaming via transferTo (like ParallelIOEngine.readTo())
        readTo(file, counter);

        long heapAfter = heapUsed();
        long deltaBytes = heapAfter - heapBefore;
        double deltaMb = deltaBytes / (1024.0 * 1024.0);

        System.out.printf("[BENCHMARK] memory_readToNewPath_10mb: heap_delta=%.2f MB%n", deltaMb);

        assertTrue(counter.count >= 10 * 1024 * 1024, "Should have streamed all 10MB");
        assertTrue(deltaMb < 2.0,
                "New path heap delta should be < 2MB (proves streaming), was " + deltaMb + " MB");
    }

    @Test
    void benchmark_memory_actorCreation_10000Actors_heapDelta() throws Exception {
        // Force GC, measure baseline
        System.gc();
        Thread.sleep(100);
        long heapBefore = heapUsed();

        FlowActor[] actors = new FlowActor[10_000];
        for (int i = 0; i < 10_000; i++) {
            actors[i] = new FlowActor("TRK-" + String.format("%05d", i));
        }

        long heapAfter = heapUsed();
        long deltaBytes = heapAfter - heapBefore;
        double bytesPerActor = (double) deltaBytes / 10_000;

        System.out.printf("[BENCHMARK] memory_actorCreation_10000: heap_delta=%.2f MB (%.0f bytes/actor)%n",
                deltaBytes / (1024.0 * 1024.0), bytesPerActor);

        // Keep reference alive to prevent GC
        assertNotNull(actors[9999]);
        assertTrue(bytesPerActor < 1024,
                "FlowActor should be < 1KB each, was " + bytesPerActor + " bytes");
    }

    @Test
    void benchmark_memory_registryWith1000Functions_heapDelta() throws Exception {
        // Force GC, measure baseline
        System.gc();
        Thread.sleep(100);
        long heapBefore = heapUsed();

        var registry = new FlowFunctionRegistry();
        for (int i = 0; i < 1000; i++) {
            final String type = "FUNC_" + i;
            registry.register(new FlowFunction() {
                @Override public String executePhysical(FlowFunctionContext ctx) { return null; }
                @Override public String type() { return type; }
                @Override public IOMode ioMode() { return IOMode.METADATA_ONLY; }
                @Override public String description() { return "Bench function " + type; }
            });
        }

        long heapAfter = heapUsed();
        long deltaBytes = heapAfter - heapBefore;
        double bytesPerFunction = (double) deltaBytes / 1000;

        System.out.printf("[BENCHMARK] memory_registryWith1000Functions: heap_delta=%.2f MB (%.0f bytes/function)%n",
                deltaBytes / (1024.0 * 1024.0), bytesPerFunction);

        assertEquals(1000, registry.size());
        assertTrue(bytesPerFunction < 5120,
                "Function registration should be < 5KB each, was " + bytesPerFunction + " bytes");
    }
}
