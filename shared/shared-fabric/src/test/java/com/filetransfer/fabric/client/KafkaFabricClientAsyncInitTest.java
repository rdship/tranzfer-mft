package com.filetransfer.fabric.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.config.FabricProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R97: verify {@link KafkaFabricClient}'s constructor does not block on
 * KafkaProducer creation or broker reachability check — that work now runs
 * on a background {@code fabric-kafka-init} thread so Spring context
 * refresh stays off the critical path.
 *
 * <p>We use an unreachable broker address to guarantee the init thread
 * takes non-trivial time (it will retry metadata fetch until the
 * configured timeout). The constructor must still return essentially
 * instantly.
 */
class KafkaFabricClientAsyncInitTest {

    @Test
    void constructorReturnsImmediatelyEvenWhenBrokerUnreachable() throws Exception {
        FabricProperties props = new FabricProperties();
        // Point at a port that's guaranteed not listening — the IANA-reserved
        // discard service (port 9) rejects immediately on most systems, but
        // using an RFC-5737 test-net address + unreachable port forces the
        // Kafka client's own timeout path.
        props.setBrokerUrl("198.51.100.1:9092");
        props.setHealthCheckTimeoutMs(3000);

        long start = System.nanoTime();
        KafkaFabricClient client = new KafkaFabricClient(props, new ObjectMapper());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        try {
            // Constructor must return fast — ideally <50ms, definitely <1s.
            // Pre-R97 behavior: constructor blocked until health check timed out,
            // ~3s+ here. Any value well below that proves the async move worked.
            assertThat(elapsedMs)
                    .as("Constructor must not block on broker reachability")
                    .isLessThan(1500L);

            // The init future exists and is either running or completed.
            Field f = KafkaFabricClient.class.getDeclaredField("initFuture");
            f.setAccessible(true);
            CompletableFuture<?> future = (CompletableFuture<?>) f.get(client);
            assertThat(future).isNotNull();
            // If the broker was unreachable, healthy should still be false after
            // init completes — giving the async thread time to finish.
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(client.isHealthy()).isFalse();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void publishFailsCleanlyWhenInitNeverSucceeds() throws Exception {
        FabricProperties props = new FabricProperties();
        props.setBrokerUrl("198.51.100.1:9092");
        props.setHealthCheckTimeoutMs(500);

        KafkaFabricClient client = new KafkaFabricClient(props, new ObjectMapper());
        try {
            // Wait for async init to finish (failure expected).
            Field f = KafkaFabricClient.class.getDeclaredField("initFuture");
            f.setAccessible(true);
            CompletableFuture<?> future = (CompletableFuture<?>) f.get(client);
            try { future.get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignored) { /* expected */ }

            // publish() must NOT throw — it must log-and-skip the same way the
            // pre-R97 code did when healthy=false.
            client.publish("flow.intake", "k", java.util.Map.of("foo", "bar"));
            // No assertion — the assertion is "did not throw". If publish
            // unexpectedly threw, JUnit fails the test.
        } finally {
            client.shutdown();
        }
    }
}
