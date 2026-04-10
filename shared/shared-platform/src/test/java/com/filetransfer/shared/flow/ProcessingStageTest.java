package com.filetransfer.shared.flow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessingStage — the SEDA-style bounded-queue
 * processing stage with admission control.
 */
class ProcessingStageTest {

    @Test
    void submit_shouldProcessItem() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ProcessingStage<String> stage = new ProcessingStage<>("test-submit", 10, 1, item -> latch.countDown());

        try {
            boolean accepted = stage.submit("hello");
            assertTrue(accepted);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Item should be processed within timeout");
        } finally {
            stage.shutdown();
        }
    }

    @Test
    void submit_whenQueueFull_shouldReturnFalse() throws InterruptedException {
        // Use a very slow handler and queue size of 1
        CountDownLatch blockHandler = new CountDownLatch(1);
        ProcessingStage<Integer> stage = new ProcessingStage<>("test-full", 1, 1, item -> {
            try { blockHandler.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        try {
            // Submit many items — at least some should be rejected
            int rejectedCount = 0;
            for (int i = 0; i < 100; i++) {
                if (!stage.submit(i)) rejectedCount++;
            }
            assertTrue(rejectedCount > 0, "Some items should be rejected when queue is full");
        } finally {
            blockHandler.countDown();
            stage.shutdown();
        }
    }

    @Test
    void submitBlocking_shouldEventuallyProcess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ProcessingStage<String> stage = new ProcessingStage<>("test-blocking", 10, 1, item -> latch.countDown());

        try {
            stage.submitBlocking("blocking-item");
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Blocking submit should eventually process");
        } finally {
            stage.shutdown();
        }
    }

    @Test
    void getStats_shouldTrackProcessedCount() throws InterruptedException {
        int total = 5;
        CountDownLatch latch = new CountDownLatch(total);
        ProcessingStage<Integer> stage = new ProcessingStage<>("test-stats", 100, 2, item -> latch.countDown());

        try {
            for (int i = 0; i < total; i++) {
                stage.submit(i);
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "All items should be processed");

            // Small delay to allow atomic counter update after latch countdown
            Thread.sleep(50);

            assertTrue(stage.processedCount() >= total,
                    "Processed count should be >= " + total + ", was " + stage.processedCount());

            var stats = stage.getStats();
            assertEquals("test-stats", stats.get("name"));
            assertTrue((long) stats.get("processed") >= total);
        } finally {
            stage.shutdown();
        }
    }

    @Test
    void shutdown_shouldDrainQueue() throws InterruptedException {
        AtomicLong processedCount = new AtomicLong();
        ProcessingStage<Integer> stage = new ProcessingStage<>("test-shutdown", 100, 2,
                item -> processedCount.incrementAndGet());

        for (int i = 0; i < 10; i++) {
            stage.submit(i);
        }

        // Give workers a brief moment to start processing
        Thread.sleep(200);

        stage.shutdown();

        // After shutdown, the stage should have processed items gracefully
        assertTrue(processedCount.get() > 0, "Should have processed at least some items before shutdown");
    }
}
