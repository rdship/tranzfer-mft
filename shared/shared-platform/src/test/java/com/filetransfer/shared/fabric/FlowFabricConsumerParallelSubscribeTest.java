package com.filetransfer.shared.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R94: pin the boot-time optimization invariants for
 * {@link FlowFabricConsumer}'s per-function subscribe loop.
 *
 * <p>Three invariants under test:
 * <ol>
 *   <li>Every step topic is subscribed exactly once (no duplicates, no drops)
 *       when parallelism > 1.</li>
 *   <li>The parallel path returns only after all subscribes complete — the
 *       barrier is load-bearing because Spring marks the bean Started on
 *       return, and a premature Started drops the first inbound message.</li>
 *   <li>{@code subscribeParallelism=1} restores the pre-R94 sequential order
 *       byte-for-byte — the emergency rollback switch works.</li>
 * </ol>
 *
 * <p>Uses a hand-rolled FabricClient double so we can measure wall-clock
 * speedup directly rather than mocking the underlying Kafka calls.
 */
class FlowFabricConsumerParallelSubscribeTest {

    @Test
    void parallelSubscribeSubscribesEverySteptopicExactlyOnce() throws Exception {
        CountingFabricClient client = new CountingFabricClient(0L);
        FlowFabricConsumer consumer = newConsumerWithParallelism(client, 8);

        invokeSubscribeInParallel(consumer, "test-service", FULL_STEP_TYPES);

        assertThat(client.topicsSubscribed)
                .as("Every step topic subscribed exactly once")
                .hasSize(FULL_STEP_TYPES.length)
                .containsExactlyInAnyOrderElementsOf(expectedTopicNames());
        assertThat(client.subscribeCallCount.get()).isEqualTo(FULL_STEP_TYPES.length);
    }

    @Test
    void barrierBlocksUntilAllSubscribesComplete() throws Exception {
        // Each subscribe sleeps 50 ms. Sequential would take 20*50=1000 ms;
        // parallelism=8 should complete in ~150 ms. Either way, when this
        // method returns, every subscribe MUST have finished (barrier contract).
        CountingFabricClient client = new CountingFabricClient(50L);
        FlowFabricConsumer consumer = newConsumerWithParallelism(client, 8);

        long start = System.nanoTime();
        invokeSubscribeInParallel(consumer, "test-service", FULL_STEP_TYPES);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(client.subscribeCallCount.get())
                .as("All subscribes completed before method returned")
                .isEqualTo(FULL_STEP_TYPES.length);
        assertThat(elapsedMs)
                .as("Parallelism 8 cut wall-clock well below sequential cost")
                .isLessThan(700L);
    }

    @Test
    void parallelismOneRestoresSequentialBehaviour() throws Exception {
        CountingFabricClient client = new CountingFabricClient(0L);
        FlowFabricConsumer consumer = newConsumerWithParallelism(client, 1);

        invokeSubscribeInParallel(consumer, "test-service", FULL_STEP_TYPES);

        // Sequential path preserves the exact step-type order from the array —
        // use the ordered subscribeOrder list for this (topicsSubscribed is a
        // Set and carries no ordering guarantee).
        assertThat(client.subscribeOrder)
                .containsExactlyElementsOf(expectedTopicNames());
    }

    @Test
    void ensureTopicsIsInvokedOnceWithFullBatch() throws Exception {
        // Option 2: batch topic pre-create so 20 sequential AdminClient
        // round-trips become one. Consumer must call ensureTopics exactly
        // once per init with the full topic list.
        CountingFabricClient client = new CountingFabricClient(0L);
        FlowFabricConsumer consumer = newConsumerWithParallelism(client, 8);

        invokeSubscribeInParallel(consumer, "test-service", FULL_STEP_TYPES);

        assertThat(client.ensureTopicsCallCount.get()).isEqualTo(1);
        assertThat(client.lastEnsureTopicsBatch)
                .containsExactlyInAnyOrderElementsOf(expectedTopicNames());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static final String[] FULL_STEP_TYPES = {
            "SCREEN", "CHECKSUM_VERIFY",
            "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
            "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
            "CONVERT_EDI", "RENAME",
            "MAILBOX", "FILE_DELIVERY",
            "DELIVER_SFTP", "DELIVER_FTP", "DELIVER_HTTP", "DELIVER_AS2", "DELIVER_KAFKA",
            "EXECUTE_SCRIPT"
    };

    private static java.util.List<String> expectedTopicNames() {
        return java.util.Arrays.stream(FULL_STEP_TYPES).map(s -> "flow.step." + s).toList();
    }

    private static FlowFabricConsumer newConsumerWithParallelism(CountingFabricClient client, int parallelism) {
        FabricProperties props = new FabricProperties();
        props.setEnabled(true);
        props.getFlow().setConsume(true);
        props.getFlow().setSubscribeParallelism(parallelism);

        FlowFabricBridge bridge = new FlowFabricBridge(client, props, null, new ObjectMapper());
        // R119: fabricBridge is now optional field injection (@Autowired(required=false))
        // rather than constructor-injected, so Lombok's @RequiredArgsConstructor no longer
        // includes it. Set it via reflection post-construct to preserve this test's behaviour.
        FlowFabricConsumer consumer = new FlowFabricConsumer(
                props,
                new ObjectMapper(),
                (FlowExecutionRepository) null,
                (FileFlowRepository) null);
        try {
            java.lang.reflect.Field f = FlowFabricConsumer.class.getDeclaredField("fabricBridge");
            f.setAccessible(true);
            f.set(consumer, bridge);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject fabricBridge via reflection", e);
        }
        return consumer;
    }

    /** Reflectively invoke the private {@code subscribeStepTopicsInParallel}. */
    private static void invokeSubscribeInParallel(FlowFabricConsumer consumer,
                                                   String serviceName,
                                                   String[] stepTypes) throws Exception {
        Method m = FlowFabricConsumer.class.getDeclaredMethod(
                "subscribeStepTopicsInParallel", String.class, String[].class);
        m.setAccessible(true);
        m.invoke(consumer, serviceName, stepTypes);
    }

    /** Hand-rolled fabric client that tracks calls for assertions. */
    private static final class CountingFabricClient implements FabricClient {
        private final long perSubscribeSleepMs;
        final Set<String> topicsSubscribed = ConcurrentHashMap.newKeySet();
        /** Insertion-ordered record of subscribe calls — used to verify sequential order. */
        final List<String> subscribeOrder = new CopyOnWriteArrayList<>();
        final AtomicInteger subscribeCallCount = new AtomicInteger();
        final AtomicInteger ensureTopicsCallCount = new AtomicInteger();
        volatile Collection<String> lastEnsureTopicsBatch = Collections.emptyList();

        CountingFabricClient(long perSubscribeSleepMs) {
            this.perSubscribeSleepMs = perSubscribeSleepMs;
        }

        @Override public void publish(String topic, String key, Object value) { /* no-op */ }
        @Override public boolean isHealthy() { return true; }
        @Override public boolean isDistributed() { return true; }

        @Override
        public void subscribe(String topic, String groupId, MessageHandler handler) {
            if (perSubscribeSleepMs > 0) {
                try { Thread.sleep(perSubscribeSleepMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            topicsSubscribed.add(topic);
            subscribeOrder.add(topic);
            subscribeCallCount.incrementAndGet();
        }

        @Override
        public void ensureTopics(Collection<String> topics) {
            ensureTopicsCallCount.incrementAndGet();
            lastEnsureTopicsBatch = java.util.List.copyOf(topics);
        }
    }
}
