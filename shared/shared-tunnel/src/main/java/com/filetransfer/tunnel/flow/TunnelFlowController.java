package com.filetransfer.tunnel.flow;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-stream sliding window flow control for the tunnel protocol.
 * <p>
 * Each stream gets an initial window of {@link #initialWindowSize} bytes.
 * The sender decrements the window on each DATA frame; the receiver sends
 * WINDOW_UPDATE after consuming data to replenish the sender's window.
 * <p>
 * Lock-free: uses AtomicInteger per stream for zero-contention on the hot path.
 */
@Slf4j
public class TunnelFlowController {

    public static final int DEFAULT_WINDOW_SIZE = 256 * 1024; // 256KB
    private static final int WINDOW_UPDATE_THRESHOLD_DIVISOR = 2; // send update when half consumed

    private final int initialWindowSize;
    private final ConcurrentHashMap<Integer, AtomicInteger> sendWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> recvConsumed = new ConcurrentHashMap<>();

    public TunnelFlowController() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public TunnelFlowController(int initialWindowSize) {
        this.initialWindowSize = initialWindowSize;
    }

    public void registerStream(int streamId) {
        sendWindows.put(streamId, new AtomicInteger(initialWindowSize));
        recvConsumed.put(streamId, new AtomicInteger(0));
    }

    public void deregisterStream(int streamId) {
        sendWindows.remove(streamId);
        recvConsumed.remove(streamId);
    }

    /**
     * Sender side: try to consume bytes from the send window.
     * Returns the number of bytes actually allowed (may be less than requested).
     * Returns 0 if the window is exhausted.
     */
    public int tryConsume(int streamId, int requested) {
        AtomicInteger window = sendWindows.get(streamId);
        if (window == null) return 0;

        while (true) {
            int current = window.get();
            if (current <= 0) return 0;
            int allowed = Math.min(current, requested);
            if (window.compareAndSet(current, current - allowed)) {
                return allowed;
            }
            // CAS retry — another thread consumed concurrently
        }
    }

    /**
     * Sender side: WINDOW_UPDATE received from remote — replenish send window.
     */
    public void onWindowUpdate(int streamId, int increment) {
        AtomicInteger window = sendWindows.get(streamId);
        if (window != null) {
            window.addAndGet(increment);
            log.trace("Stream {} send window += {} (now {})", streamId, increment, window.get());
        }
    }

    /**
     * Receiver side: DATA received — track bytes received (not yet consumed by application).
     */
    public void onDataReceived(int streamId, int bytes) {
        // Receive-side tracking: we've accepted bytes into our buffer
        // The actual WINDOW_UPDATE is sent when the application consumes the data
    }

    /**
     * Receiver side: application consumed data. Returns the increment to send as WINDOW_UPDATE,
     * or 0 if the threshold hasn't been reached yet (batching small updates).
     */
    public int onDataConsumed(int streamId, int consumed) {
        AtomicInteger counter = recvConsumed.get(streamId);
        if (counter == null) return 0;

        int total = counter.addAndGet(consumed);
        int threshold = initialWindowSize / WINDOW_UPDATE_THRESHOLD_DIVISOR;

        if (total >= threshold) {
            // Reset counter and return the amount to replenish
            counter.addAndGet(-total);
            return total;
        }
        return 0; // batch — don't send tiny updates
    }

    public int getSendWindow(int streamId) {
        AtomicInteger window = sendWindows.get(streamId);
        return window != null ? window.get() : 0;
    }

    public int getInitialWindowSize() {
        return initialWindowSize;
    }
}
