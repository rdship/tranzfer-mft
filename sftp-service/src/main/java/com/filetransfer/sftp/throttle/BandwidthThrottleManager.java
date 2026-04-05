package com.filetransfer.sftp.throttle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manages bandwidth throttling configuration for upload and download operations.
 *
 * <p>Per-user speed limits are expressed in bytes per second. A value of 0
 * means unlimited. The throttle is implemented by sleeping between I/O
 * operations to cap the effective transfer rate.</p>
 */
@Slf4j
@Component
public class BandwidthThrottleManager {

    /** Per-user upload speed limit in bytes per second (0 = unlimited). */
    @Value("${sftp.throttle.upload-bytes-per-second:0}")
    private long uploadBytesPerSecond;

    /** Per-user download speed limit in bytes per second (0 = unlimited). */
    @Value("${sftp.throttle.download-bytes-per-second:0}")
    private long downloadBytesPerSecond;

    /**
     * Applies throttle delay after a chunk of bytes has been transferred.
     * Call this after each read/write buffer operation to enforce the rate limit.
     *
     * @param bytesTransferred number of bytes just transferred
     * @param isUpload         true for upload throttle, false for download
     */
    public void throttleIfNeeded(long bytesTransferred, boolean isUpload) {
        long limitBps = isUpload ? uploadBytesPerSecond : downloadBytesPerSecond;
        if (limitBps <= 0 || bytesTransferred <= 0) return;

        // Calculate how long the transfer "should" have taken at the limit rate
        double expectedSeconds = (double) bytesTransferred / limitBps;
        long expectedMs = (long) (expectedSeconds * 1000);

        if (expectedMs > 1) {
            try {
                Thread.sleep(expectedMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns the configured upload speed limit in bytes per second.
     */
    public long getUploadBytesPerSecond() {
        return uploadBytesPerSecond;
    }

    /**
     * Returns the configured download speed limit in bytes per second.
     */
    public long getDownloadBytesPerSecond() {
        return downloadBytesPerSecond;
    }

    /**
     * Returns true if any throttle is configured.
     */
    public boolean isThrottlingEnabled() {
        return uploadBytesPerSecond > 0 || downloadBytesPerSecond > 0;
    }
}
