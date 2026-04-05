package com.filetransfer.forwarder.transfer;

/**
 * Thrown when a file transfer stalls — no data has been transferred
 * for longer than the configured inactivity threshold.
 *
 * <p>This exception is always retryable. The retry logic uses shorter backoff
 * because the connection was partially working (unlike a hard connection failure).
 */
public class TransferStallException extends RuntimeException {

    private final String transferId;
    private final long bytesTransferred;
    private final long totalBytes;
    private final long idleSeconds;

    public TransferStallException(TransferSession session) {
        super(buildMessage(session));
        this.transferId = session.getTransferId();
        this.bytesTransferred = session.getBytesTransferred();
        this.totalBytes = session.getTotalBytes();
        this.idleSeconds = session.getIdleSeconds();
    }

    public TransferStallException(String transferId, long bytesTransferred,
                                   long totalBytes, long idleSeconds) {
        super(String.format("Transfer stalled: %s — %d/%d bytes (%d%%) transferred, "
                        + "idle for %ds",
                transferId, bytesTransferred, totalBytes,
                totalBytes > 0 ? (bytesTransferred * 100 / totalBytes) : 0, idleSeconds));
        this.transferId = transferId;
        this.bytesTransferred = bytesTransferred;
        this.totalBytes = totalBytes;
        this.idleSeconds = idleSeconds;
    }

    private static String buildMessage(TransferSession s) {
        return String.format(
                "Transfer stalled: %s [%s → %s] — %d/%d bytes (%d%%) transferred, "
                        + "idle for %ds, elapsed %ds",
                s.getTransferId(), s.getFilename(), s.getEndpointName(),
                s.getBytesTransferred(), s.getTotalBytes(), s.getProgressPercent(),
                s.getIdleSeconds(), s.getElapsedSeconds());
    }

    public String getTransferId() { return transferId; }
    public long getBytesTransferred() { return bytesTransferred; }
    public long getTotalBytes() { return totalBytes; }
    public long getIdleSeconds() { return idleSeconds; }

    /** Percentage of the transfer completed before stalling. */
    public int getProgressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) ((bytesTransferred * 100) / totalBytes);
    }
}
