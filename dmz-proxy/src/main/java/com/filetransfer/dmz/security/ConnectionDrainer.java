package com.filetransfer.dmz.security;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection Drainer — manages graceful connection draining when a proxy
 * mapping is removed or the service is shutting down.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Close the server channel (stop accepting new connections)</li>
 *   <li>Poll active connection count every 500 ms</li>
 *   <li>Complete when count reaches 0 or timeout expires</li>
 * </ol>
 *
 * <p>Not a Spring bean — instantiated directly by ProxyManager per drain
 * operation. Each instance uses a single-thread scheduler that is shut down
 * after the drain completes.
 */
@Slf4j
public class ConnectionDrainer {

    private static final long POLL_INTERVAL_MS = 500;

    private final int drainTimeoutSeconds;

    // ── Result record ────────────────────────────────────────────────────

    /**
     * Immutable result of a drain operation.
     *
     * @param drained    number of connections that completed during the drain window
     * @param remaining  connections still active when the drain finished
     * @param durationMs wall-clock time the drain took
     * @param timedOut   {@code true} if the timeout was reached before all connections closed
     */
    public record DrainResult(int drained, int remaining, long durationMs, boolean timedOut) {

        @Override
        public String toString() {
            return String.format("DrainResult{drained=%d, remaining=%d, duration=%dms, timedOut=%s}",
                    drained, remaining, durationMs, timedOut);
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────

    /**
     * Creates a ConnectionDrainer with the given timeout.
     *
     * @param drainTimeoutSeconds maximum seconds to wait for active connections
     *                            to close; must be &gt; 0
     */
    public ConnectionDrainer(int drainTimeoutSeconds) {
        if (drainTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("drainTimeoutSeconds must be > 0, got " + drainTimeoutSeconds);
        }
        this.drainTimeoutSeconds = drainTimeoutSeconds;
    }

    /**
     * Creates a ConnectionDrainer with the default 30-second timeout.
     */
    public ConnectionDrainer() {
        this(30);
    }

    // ── Async drain ──────────────────────────────────────────────────────

    /**
     * Initiates an asynchronous drain.
     *
     * <p>Closes the server channel immediately, then polls
     * {@code activeConnections} every 500 ms until the count reaches 0
     * or the timeout expires.
     *
     * @param serverChannel     the Netty server channel to close (stop accepting)
     * @param activeConnections atomic counter of currently active connections
     * @return a future that completes with the {@link DrainResult}
     */
    public CompletableFuture<DrainResult> drain(Channel serverChannel,
                                                AtomicLong activeConnections) {
        CompletableFuture<DrainResult> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        long initialCount = activeConnections.get();

        log.info("Draining {} active connections, timeout={}s", initialCount, drainTimeoutSeconds);

        // Step 1: Close the server channel to stop accepting new connections.
        closeServerChannel(serverChannel);

        // Step 2: Poll on a dedicated scheduler thread.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "connection-drainer");
            t.setDaemon(true);
            return t;
        });

        long deadlineMs = startTime + TimeUnit.SECONDS.toMillis(drainTimeoutSeconds);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                long current = activeConnections.get();
                long elapsed = System.currentTimeMillis() - startTime;

                if (current <= 0) {
                    // All connections drained.
                    int drained = (int) (initialCount - Math.max(current, 0));
                    DrainResult result = new DrainResult(drained, 0, elapsed, false);
                    log.info("Drain complete: {} drained, 0 remaining in {}ms", drained, elapsed);
                    future.complete(result);
                    scheduler.shutdown();
                } else if (System.currentTimeMillis() >= deadlineMs) {
                    // Timeout reached.
                    int remaining = (int) current;
                    int drained = (int) (initialCount - current);
                    DrainResult result = new DrainResult(Math.max(drained, 0), remaining, elapsed, true);
                    log.warn("Drain timed out after {}ms: {} drained, {} remaining",
                            elapsed, Math.max(drained, 0), remaining);
                    future.complete(result);
                    scheduler.shutdown();
                } else {
                    log.debug("Drain in progress: {} connections remaining, {}ms elapsed",
                            current, elapsed);
                }
            } catch (Exception e) {
                log.error("Unexpected error during drain polling", e);
                long elapsed = System.currentTimeMillis() - startTime;
                long current = activeConnections.get();
                future.complete(new DrainResult(
                        (int) (initialCount - current), (int) current, elapsed, true));
                scheduler.shutdown();
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Safety net: ensure scheduler is cleaned up when the future completes.
        future.whenComplete((result, throwable) -> {
            if (!scheduler.isShutdown()) {
                scheduler.shutdown();
            }
        });

        return future;
    }

    // ── Blocking drain ───────────────────────────────────────────────────

    /**
     * Blocking version of {@link #drain(Channel, AtomicLong)}.
     *
     * <p>Blocks the calling thread until the drain completes or times out.
     * Suitable for use in shutdown hooks or synchronous proxy-management paths.
     *
     * @param serverChannel     the Netty server channel to close (stop accepting)
     * @param activeConnections atomic counter of currently active connections
     * @return the {@link DrainResult}
     */
    public DrainResult drainSync(Channel serverChannel, AtomicLong activeConnections) {
        long startTime = System.currentTimeMillis();
        long initialCount = activeConnections.get();

        log.info("Draining (sync) {} active connections, timeout={}s", initialCount, drainTimeoutSeconds);

        // Step 1: Close the server channel.
        closeServerChannel(serverChannel);

        // Step 2: Busy-poll with sleep.
        long deadlineMs = startTime + TimeUnit.SECONDS.toMillis(drainTimeoutSeconds);

        while (System.currentTimeMillis() < deadlineMs) {
            long current = activeConnections.get();
            if (current <= 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                int drained = (int) initialCount;
                DrainResult result = new DrainResult(drained, 0, elapsed, false);
                log.info("Drain complete: {} drained, 0 remaining in {}ms", drained, elapsed);
                return result;
            }

            log.debug("Drain in progress: {} connections remaining", current);

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long elapsed = System.currentTimeMillis() - startTime;
                int remaining = (int) activeConnections.get();
                int drained = (int) (initialCount - remaining);
                log.warn("Drain interrupted after {}ms: {} drained, {} remaining",
                        elapsed, Math.max(drained, 0), remaining);
                return new DrainResult(Math.max(drained, 0), remaining, elapsed, true);
            }
        }

        // Timeout reached.
        long elapsed = System.currentTimeMillis() - startTime;
        long current = activeConnections.get();
        int remaining = (int) current;
        int drained = (int) (initialCount - current);
        DrainResult result = new DrainResult(Math.max(drained, 0), remaining, elapsed, true);
        log.warn("Drain timed out after {}ms: {} drained, {} remaining",
                elapsed, Math.max(drained, 0), remaining);
        return result;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Closes the server channel and waits briefly for the close to propagate.
     * Logs any failure but does not throw — draining should still proceed
     * even if the channel was already closed.
     */
    private void closeServerChannel(Channel serverChannel) {
        if (serverChannel == null || !serverChannel.isOpen()) {
            log.debug("Server channel already closed or null — skipping close");
            return;
        }

        try {
            serverChannel.close().await(5, TimeUnit.SECONDS);
            log.debug("Server channel closed successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing server channel");
        } catch (Exception e) {
            log.warn("Failed to close server channel cleanly: {}", e.getMessage());
        }
    }
}
