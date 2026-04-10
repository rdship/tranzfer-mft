package com.filetransfer.shared.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IOLaneManager — the semaphore-based I/O concurrency
 * controller that prevents traffic classes from starving each other.
 */
class IOLaneManagerTest {

    private IOLaneManager manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = new IOLaneManager();
        setField(manager, "realtimePermits", 4);
        setField(manager, "bulkPermits", 2);
        setField(manager, "backgroundPermits", 2);
        manager.init();
    }

    @Test
    void acquire_release_shouldWorkCorrectly() throws InterruptedException {
        int before = manager.availablePermits(IOLane.REALTIME);
        manager.acquire(IOLane.REALTIME);

        assertEquals(before - 1, manager.availablePermits(IOLane.REALTIME));

        manager.release(IOLane.REALTIME);

        assertEquals(before, manager.availablePermits(IOLane.REALTIME));
    }

    @Test
    void tryAcquire_whenAvailable_shouldReturnTrue() throws InterruptedException {
        boolean acquired = manager.tryAcquire(IOLane.BULK, 1, TimeUnit.SECONDS);

        assertTrue(acquired);
        manager.release(IOLane.BULK);
    }

    @Test
    void tryAcquire_whenExhausted_shouldReturnFalse() throws InterruptedException {
        // Exhaust all BACKGROUND permits (configured as 2)
        manager.acquire(IOLane.BACKGROUND);
        manager.acquire(IOLane.BACKGROUND);

        try {
            boolean acquired = manager.tryAcquire(IOLane.BACKGROUND, 100, TimeUnit.MILLISECONDS);
            assertFalse(acquired, "Should fail when all permits are exhausted");
        } finally {
            manager.release(IOLane.BACKGROUND);
            manager.release(IOLane.BACKGROUND);
        }
    }

    @Test
    void getStats_shouldReturnAllLanes() {
        Map<String, Object> stats = manager.getStats();

        // Verify all 3 lanes have available and waiting keys
        assertTrue(stats.containsKey("realtimeAvailable"));
        assertTrue(stats.containsKey("realtimeWaiting"));
        assertTrue(stats.containsKey("bulkAvailable"));
        assertTrue(stats.containsKey("bulkWaiting"));
        assertTrue(stats.containsKey("backgroundAvailable"));
        assertTrue(stats.containsKey("backgroundWaiting"));

        // Verify initial values are correct
        assertEquals(4, stats.get("realtimeAvailable"));
        assertEquals(2, stats.get("bulkAvailable"));
        assertEquals(2, stats.get("backgroundAvailable"));
    }

    @Test
    void concurrentAcquire_shouldRespectPermitLimit() throws Exception {
        // BACKGROUND lane has 2 permits — acquire both from separate threads
        CountDownLatch bothAcquired = new CountDownLatch(2);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        AtomicBoolean thirdBlocked = new AtomicBoolean(true);

        // Thread 1: acquire and hold
        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                manager.acquire(IOLane.BACKGROUND);
                bothAcquired.countDown();
                releaseSignal.await();
                manager.release(IOLane.BACKGROUND);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        // Thread 2: acquire and hold
        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                manager.acquire(IOLane.BACKGROUND);
                bothAcquired.countDown();
                releaseSignal.await();
                manager.release(IOLane.BACKGROUND);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        assertTrue(bothAcquired.await(2, TimeUnit.SECONDS), "Both threads should acquire permits");

        // Thread 3: should NOT be able to acquire immediately
        Thread t3 = Thread.ofVirtual().start(() -> {
            try {
                boolean got = manager.tryAcquire(IOLane.BACKGROUND, 200, TimeUnit.MILLISECONDS);
                thirdBlocked.set(!got);
                if (got) manager.release(IOLane.BACKGROUND);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        t3.join(2000);
        assertTrue(thirdBlocked.get(), "Third thread should be blocked when all permits are taken");

        // Release and clean up
        releaseSignal.countDown();
        t1.join(2000);
        t2.join(2000);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
