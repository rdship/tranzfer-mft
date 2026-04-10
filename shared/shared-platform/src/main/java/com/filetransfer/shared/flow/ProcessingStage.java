package com.filetransfer.shared.flow;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A SEDA processing stage with a bounded queue, dedicated thread pool,
 * and admission control. Work items are submitted to the queue; worker
 * threads drain and process them.
 *
 * @param <T> the type of work item processed by this stage
 */
@Slf4j
public class ProcessingStage<T> {

    @Getter private final String name;
    private final BlockingQueue<T> queue;
    private final ExecutorService workers;
    private final Consumer<T> handler;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean running = true;

    /**
     * Create a new processing stage.
     *
     * @param name       stage name for logging and metrics
     * @param queueSize  bounded queue capacity (admission control)
     * @param threads    number of worker threads
     * @param handler    function to process each work item
     */
    public ProcessingStage(String name, int queueSize, int threads, Consumer<T> handler) {
        this.name = name;
        this.queue = new LinkedBlockingQueue<>(queueSize);
        this.handler = handler;
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("stage-" + name + "-", 0).factory());

        // Start worker loops
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("SEDA stage '{}' started: queue={}, threads={}", name, queueSize, threads);
    }

    /** Submit work to the stage. Returns false if queue is full (admission control). */
    public boolean submit(T item) {
        boolean accepted = queue.offer(item);
        if (!accepted) {
            rejected.incrementAndGet();
            log.warn("SEDA stage '{}': queue full, rejected item (queue={}, rejected={})",
                    name, queue.size(), rejected.get());
        }
        return accepted;
    }

    /** Submit work to the stage, blocking until space is available. */
    public void submitBlocking(T item) throws InterruptedException {
        queue.put(item);
    }

    /** Submit work with a timeout. Returns false if queue didn't accept within timeout. */
    public boolean submit(T item, long timeout, TimeUnit unit) throws InterruptedException {
        boolean accepted = queue.offer(item, timeout, unit);
        if (!accepted) rejected.incrementAndGet();
        return accepted;
    }

    private void workerLoop() {
        while (running) {
            try {
                T item = queue.poll(1, TimeUnit.SECONDS);
                if (item != null) {
                    try {
                        handler.accept(item);
                        processed.incrementAndGet();
                    } catch (Exception e) {
                        log.error("SEDA stage '{}': handler error — {}", name, e.getMessage(), e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Graceful shutdown — drain remaining items then stop workers. */
    public void shutdown() {
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SEDA stage '{}' shut down: processed={}, rejected={}, remaining={}",
                name, processed.get(), rejected.get(), queue.size());
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    public int queueSize() { return queue.size(); }
    public long processedCount() { return processed.get(); }
    public long rejectedCount() { return rejected.get(); }

    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
                "name", name,
                "queueSize", queue.size(),
                "queueRemaining", queue.remainingCapacity(),
                "processed", processed.get(),
                "rejected", rejected.get()
        );
    }
}
