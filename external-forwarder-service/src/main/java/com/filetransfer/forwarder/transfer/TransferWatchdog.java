package com.filetransfer.forwarder.transfer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and terminates stalled file transfers.
 *
 * <p>Instead of a fixed session timeout that blindly kills connections, the watchdog
 * monitors actual data flow. As long as bytes are being transferred, the connection
 * stays open indefinitely. When no data moves for longer than the stall threshold
 * (default 30 s), the transfer thread is interrupted and a {@link TransferStallException}
 * is raised — which the retry logic treats as always-retryable with short backoff.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Per-transfer tracking, not per-connection — one slow transfer doesn't affect others</li>
 *   <li>Thread interruption is the cancellation mechanism — works with all I/O libraries</li>
 *   <li>The watchdog polls every 5 s by default — low overhead, fast detection</li>
 * </ul>
 */
@Component
@Slf4j
public class TransferWatchdog {

    private final ConcurrentHashMap<String, TransferSession> activeSessions = new ConcurrentHashMap<>();

    @Value("${forwarder.transfer.stall-timeout-seconds:30}")
    private int stallTimeoutSeconds;

    /**
     * Register a new transfer for monitoring.
     *
     * @param endpointName human-readable name of the delivery endpoint
     * @param filename     the file being transferred
     * @param totalBytes   total expected bytes
     * @return the transfer session — call {@link TransferSession#recordProgress(long)} on each chunk
     */
    public TransferSession register(String endpointName, String filename, long totalBytes) {
        String transferId = "xfer-" + UUID.randomUUID().toString().substring(0, 8);
        TransferSession session = new TransferSession(
                transferId, endpointName, filename, totalBytes, Thread.currentThread());
        activeSessions.put(transferId, session);
        log.debug("Transfer registered: {} [{} → {}] ({} bytes)",
                transferId, filename, endpointName, totalBytes);
        return session;
    }

    /** Unregister a completed or failed transfer. */
    public void unregister(String transferId) {
        TransferSession removed = activeSessions.remove(transferId);
        if (removed != null) {
            removed.markCompleted();
            log.debug("Transfer unregistered: {} ({} bytes transferred in {}s)",
                    transferId, removed.getBytesTransferred(), removed.getElapsedSeconds());
        }
    }

    /**
     * Periodic check for stalled transfers. Runs every 5 seconds.
     * If a transfer has had no data activity for longer than the stall threshold,
     * the transfer thread is interrupted.
     */
    @Scheduled(fixedDelayString = "${forwarder.transfer.watchdog-interval-ms:5000}")
    public void detectStalls() {
        if (activeSessions.isEmpty()) return;

        long now = System.currentTimeMillis();
        long thresholdMs = stallTimeoutSeconds * 1000L;

        for (Map.Entry<String, TransferSession> entry : activeSessions.entrySet()) {
            TransferSession session = entry.getValue();
            if (session.isCompleted() || session.isStalled()) continue;

            long idleMs = now - session.getLastActivityMs();
            if (idleMs > thresholdMs) {
                log.warn("STALL DETECTED: {} [{}→{}] — idle {}s, transferred {}/{} bytes ({}%), elapsed {}s",
                        session.getTransferId(), session.getFilename(), session.getEndpointName(),
                        idleMs / 1000, session.getBytesTransferred(), session.getTotalBytes(),
                        session.getProgressPercent(), session.getElapsedSeconds());

                session.setStalled(true);
                session.getTransferThread().interrupt();
            }
        }
    }

    /** Snapshot of active transfer count (for health/metrics). */
    public int getActiveTransferCount() {
        return activeSessions.size();
    }

    /** Get all active sessions (for diagnostics). */
    public Map<String, TransferSession> getActiveSessions() {
        return Map.copyOf(activeSessions);
    }

    /** Get the configured stall timeout. */
    public int getStallTimeoutSeconds() {
        return stallTimeoutSeconds;
    }
}
