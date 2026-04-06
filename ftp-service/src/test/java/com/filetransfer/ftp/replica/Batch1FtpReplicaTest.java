package com.filetransfer.ftp.replica;

import com.filetransfer.ftp.connection.ConnectionTracker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch 1b: FTP — Replica-aware connection limits.
 *
 * Tests:
 * 1. Replica adjustment halves limits (2 replicas)
 * 2. Three replicas — floor at 1
 * 3. Two replicas enforce limits independently
 * 4. Concurrent acquire — no over-admission
 * 5. Release and re-acquire — counters correct
 * 6. High contention — no deadlock
 */
class Batch1FtpReplicaTest {

    @Test
    void connectionTracker_twoReplicas_halvesLimits() throws Exception {
        ConnectionTracker tracker = createTracker(200, 10, 10, 2);
        assertEquals(100, tracker.getMaxTotal());
        assertEquals(5, tracker.getMaxPerUser());
        assertEquals(5, tracker.getMaxPerIp());
    }

    @Test
    void connectionTracker_threeReplicas_floorAtOne() throws Exception {
        ConnectionTracker tracker = createTracker(2, 2, 2, 3);
        assertEquals(1, tracker.getMaxTotal());
        assertEquals(1, tracker.getMaxPerUser());
        assertEquals(1, tracker.getMaxPerIp());
    }

    @Test
    void connectionTracker_twoReplicas_independentEnforcement() throws Exception {
        ConnectionTracker replica1 = createTracker(10, 5, 5, 2);
        ConnectionTracker replica2 = createTracker(10, 5, 5, 2);

        // 10/2=5 total, 5/2=2 per-user
        int r1 = 0;
        for (int i = 0; i < 10; i++) {
            if (replica1.tryAcquire("alice", "10.0.0.1")) r1++;
        }
        assertEquals(2, r1, "Replica-1 should allow 2 per-user (5/2)");

        int r2 = 0;
        for (int i = 0; i < 10; i++) {
            if (replica2.tryAcquire("alice", "10.0.0.2")) r2++;
        }
        assertEquals(2, r2, "Replica-2 should also allow 2 independently");
    }

    @Test
    void connectionTracker_concurrentAcquire_noOverAdmission() throws Exception {
        ConnectionTracker tracker = createTracker(20, 20, 20, 1);

        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final String ip = "10.0.0." + (i % 5 + 1);
            futures.add(exec.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                if (tracker.tryAcquire("user", ip)) admitted.incrementAndGet();
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        exec.shutdown();

        assertTrue(admitted.get() <= 20, "Should not exceed maxTotal=20, got " + admitted.get());
        assertEquals(admitted.get(), tracker.getActiveCount());
    }

    @Test
    void connectionTracker_releaseAndReacquire_countersCorrect() throws Exception {
        ConnectionTracker tracker = createTracker(5, 5, 5, 1);

        assertTrue(tracker.tryAcquire("alice", "10.0.0.1"));
        assertTrue(tracker.tryAcquire("alice", "10.0.0.1"));
        assertEquals(2, tracker.getActiveCount());

        tracker.release("alice", "10.0.0.1");
        tracker.release("alice", "10.0.0.1");
        assertEquals(0, tracker.getActiveCount());

        assertTrue(tracker.tryAcquire("alice", "10.0.0.1"));
        assertEquals(1, tracker.getActiveCount());
    }

    @Test
    void connectionTracker_highContention_noDeadlock() throws Exception {
        ConnectionTracker tracker = createTracker(100, 100, 100, 1);

        ExecutorService exec = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger(0);

        for (int i = 0; i < 200; i++) {
            final String user = "user" + (i % 10);
            final String ip = "10.0.0." + (i % 20 + 1);
            exec.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { return; }
                for (int j = 0; j < 50; j++) {
                    if (tracker.tryAcquire(user, ip)) tracker.release(user, ip);
                }
                completions.incrementAndGet();
            });
        }
        latch.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "Should not deadlock");
        assertEquals(200, completions.get());
        assertEquals(0, tracker.getActiveCount(), "All connections should be released");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private ConnectionTracker createTracker(int maxTotal, int maxPerUser, int maxPerIp, int replicas) throws Exception {
        ConnectionTracker tracker = new ConnectionTracker();
        setField(tracker, "maxTotal", maxTotal);
        setField(tracker, "maxPerUser", maxPerUser);
        setField(tracker, "maxPerIp", maxPerIp);
        setField(tracker, "replicaCount", replicas);
        // adjustForReplicas() is package-private — invoke via reflection
        java.lang.reflect.Method m = ConnectionTracker.class.getDeclaredMethod("adjustForReplicas");
        m.setAccessible(true);
        m.invoke(tracker);
        return tracker;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
