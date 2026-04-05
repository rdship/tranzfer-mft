package com.filetransfer.forwarder.transfer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the live state of a single file transfer.
 *
 * <p>Every byte written to the remote partner updates {@link #recordProgress(long)},
 * which resets the inactivity clock. The {@link TransferWatchdog} polls active
 * sessions and interrupts any transfer whose inactivity exceeds the threshold.
 */
public class TransferSession {

    private final String transferId;
    private final String endpointName;
    private final String filename;
    private final long totalBytes;
    private final long startedAtMs;
    private final Thread transferThread;

    private final AtomicLong bytesTransferred = new AtomicLong(0);
    private final AtomicLong lastActivityMs;

    private volatile boolean stalled = false;
    private volatile boolean completed = false;

    public TransferSession(String transferId, String endpointName, String filename,
                           long totalBytes, Thread transferThread) {
        this.transferId = transferId;
        this.endpointName = endpointName;
        this.filename = filename;
        this.totalBytes = totalBytes;
        this.startedAtMs = System.currentTimeMillis();
        this.transferThread = transferThread;
        this.lastActivityMs = new AtomicLong(startedAtMs);
    }

    /** Record that bytes were successfully transferred to the remote partner. */
    public void recordProgress(long bytes) {
        bytesTransferred.addAndGet(bytes);
        lastActivityMs.set(System.currentTimeMillis());
    }

    /** Mark the transfer as cleanly completed (prevents false stall on finish). */
    public void markCompleted() {
        this.completed = true;
    }

    // --- Getters ---

    public String getTransferId() { return transferId; }
    public String getEndpointName() { return endpointName; }
    public String getFilename() { return filename; }
    public long getTotalBytes() { return totalBytes; }
    public long getBytesTransferred() { return bytesTransferred.get(); }
    public long getLastActivityMs() { return lastActivityMs.get(); }
    public long getStartedAtMs() { return startedAtMs; }
    public Thread getTransferThread() { return transferThread; }

    public boolean isStalled() { return stalled; }
    public void setStalled(boolean stalled) { this.stalled = stalled; }

    public boolean isCompleted() { return completed; }

    /** Percentage of total bytes transferred (0-100). */
    public int getProgressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) ((bytesTransferred.get() * 100) / totalBytes);
    }

    /** Seconds since the last recorded activity. */
    public long getIdleSeconds() {
        return (System.currentTimeMillis() - lastActivityMs.get()) / 1000;
    }

    /** Total elapsed seconds since transfer started. */
    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - startedAtMs) / 1000;
    }
}
