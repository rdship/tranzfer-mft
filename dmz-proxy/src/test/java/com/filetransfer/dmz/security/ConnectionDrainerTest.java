package com.filetransfer.dmz.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionDrainerTest {

    // ── Constructor validation ──────────────────────────────────────────

    @Test
    void constructor_zeroTimeout_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDrainer(0));
    }

    @Test
    void constructor_negativeTimeout_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionDrainer(-5));
    }

    @Test
    void constructor_defaultTimeout() {
        ConnectionDrainer drainer = new ConnectionDrainer();
        assertNotNull(drainer, "Default constructor should create a valid drainer");
    }

    // ── drainSync tests ─────────────────────────────────────────────────

    @Test
    void drainSync_noActiveConnections_completesImmediately() {
        ConnectionDrainer drainer = new ConnectionDrainer(1);
        AtomicLong active = new AtomicLong(0);

        long start = System.currentTimeMillis();
        ConnectionDrainer.DrainResult result = drainer.drainSync(null, active);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(0, result.remaining(), "No remaining connections expected");
        assertEquals(0, result.drained(), "Drained count should be 0 when starting at 0");
        assertFalse(result.timedOut(), "Should not time out with 0 connections");
        assertTrue(elapsed < 1000, "Should complete well before the 1s timeout");
    }

    @Test
    void drainSync_connectionsDropDuringDrain() {
        ConnectionDrainer drainer = new ConnectionDrainer(5);
        AtomicLong active = new AtomicLong(5);

        // Background thread decrements connections to 0 after a short delay
        new Thread(() -> {
            try {
                Thread.sleep(200);
                active.set(0);
            } catch (InterruptedException ignored) {
            }
        }).start();

        ConnectionDrainer.DrainResult result = drainer.drainSync(null, active);

        assertFalse(result.timedOut(), "Should not time out when connections drop to 0");
        assertEquals(0, result.remaining(), "No remaining connections expected");
        assertTrue(result.durationMs() < 5000, "Should complete well before the 5s timeout");
    }

    @Test
    void drainSync_timeout_returnsRemaining() {
        ConnectionDrainer drainer = new ConnectionDrainer(1);
        AtomicLong active = new AtomicLong(5);

        ConnectionDrainer.DrainResult result = drainer.drainSync(null, active);

        assertTrue(result.timedOut(), "Should time out when connections never drop");
        assertEquals(5, result.remaining(), "All 5 connections should remain");
    }

    // ── drain (async) tests ─────────────────────────────────────────────

    @Test
    void drain_async_completesWhenConnectionsReachZero() throws Exception {
        ConnectionDrainer drainer = new ConnectionDrainer(5);
        AtomicLong active = new AtomicLong(3);

        CompletableFuture<ConnectionDrainer.DrainResult> future = drainer.drain(null, active);

        // Background thread decrements connections to 0
        new Thread(() -> {
            try {
                Thread.sleep(300);
                active.set(0);
            } catch (InterruptedException ignored) {
            }
        }).start();

        ConnectionDrainer.DrainResult result = future.get(5, TimeUnit.SECONDS);

        assertNotNull(result, "Async drain should return a result");
        assertFalse(result.timedOut(), "Should not time out when connections reach 0");
        assertEquals(0, result.remaining(), "No remaining connections expected");
    }

    // ── DrainResult ─────────────────────────────────────────────────────

    @Test
    void drainResult_toString_containsFields() {
        ConnectionDrainer.DrainResult result = new ConnectionDrainer.DrainResult(3, 2, 1500, true);

        String str = result.toString();
        assertTrue(str.contains("drained=3"), "toString should contain drained count");
        assertTrue(str.contains("remaining=2"), "toString should contain remaining count");
        assertTrue(str.contains("1500ms"), "toString should contain duration");
        assertTrue(str.contains("timedOut=true"), "toString should contain timedOut flag");
    }
}
