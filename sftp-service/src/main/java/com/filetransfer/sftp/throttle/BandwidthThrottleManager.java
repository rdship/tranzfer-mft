package com.filetransfer.sftp.throttle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages bandwidth throttling for upload and download operations.
 *
 * <p>Supports per-user limits (from TransferAccount QoS fields) with fallback
 * to global defaults. A value of 0 means unlimited.</p>
 *
 * <p>The throttle is implemented by sleeping between I/O operations to cap
 * the effective transfer rate.</p>
 */
@Slf4j
@Component
public class BandwidthThrottleManager {

    /** Global upload speed limit in bytes per second (0 = unlimited). */
    @Value("${sftp.throttle.upload-bytes-per-second:0}")
    private long uploadBytesPerSecond;

    /** Global download speed limit in bytes per second (0 = unlimited). */
    @Value("${sftp.throttle.download-bytes-per-second:0}")
    private long downloadBytesPerSecond;

    /** Per-user upload limits: username → bytes/second. */
    private final ConcurrentHashMap<String, Long> userUploadLimits = new ConcurrentHashMap<>();

    /** Per-user download limits: username → bytes/second. */
    private final ConcurrentHashMap<String, Long> userDownloadLimits = new ConcurrentHashMap<>();

    /**
     * Register per-user QoS limits (called after successful authentication).
     *
     * @param username              the authenticated username
     * @param uploadBps             upload limit in bytes/second (null = use global, 0 = unlimited)
     * @param downloadBps           download limit in bytes/second (null = use global, 0 = unlimited)
     */
    public void registerUserLimits(String username, Long uploadBps, Long downloadBps) {
        if (uploadBps != null) {
            userUploadLimits.put(username, uploadBps);
        }
        if (downloadBps != null) {
            userDownloadLimits.put(username, downloadBps);
        }
        log.debug("QoS registered for {}: upload={}B/s download={}B/s",
                username, uploadBps, downloadBps);
    }

    /**
     * Unregister per-user QoS limits (called on session close).
     */
    public void unregisterUser(String username) {
        userUploadLimits.remove(username);
        userDownloadLimits.remove(username);
    }

    /**
     * Applies throttle delay after a chunk of bytes has been transferred.
     * Uses per-user limit if registered, otherwise falls back to global.
     *
     * @param bytesTransferred number of bytes just transferred
     * @param isUpload         true for upload throttle, false for download
     */
    public void throttleIfNeeded(long bytesTransferred, boolean isUpload) {
        long limitBps = isUpload ? uploadBytesPerSecond : downloadBytesPerSecond;
        if (limitBps <= 0 || bytesTransferred <= 0) return;

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
     * Per-user throttle: resolves the effective limit for a specific user.
     *
     * @param username         the authenticated username
     * @param bytesTransferred number of bytes just transferred
     * @param isUpload         true for upload throttle, false for download
     */
    public void throttleIfNeeded(String username, long bytesTransferred, boolean isUpload) {
        long limitBps;
        if (isUpload) {
            limitBps = userUploadLimits.getOrDefault(username, uploadBytesPerSecond);
        } else {
            limitBps = userDownloadLimits.getOrDefault(username, downloadBytesPerSecond);
        }
        if (limitBps <= 0 || bytesTransferred <= 0) return;

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

    public long getUploadBytesPerSecond() {
        return uploadBytesPerSecond;
    }

    public long getDownloadBytesPerSecond() {
        return downloadBytesPerSecond;
    }

    public boolean isThrottlingEnabled() {
        return uploadBytesPerSecond > 0 || downloadBytesPerSecond > 0;
    }

    /**
     * Check if any per-user limit is registered (global or user-specific).
     */
    public boolean hasUserLimits(String username) {
        return userUploadLimits.containsKey(username) || userDownloadLimits.containsKey(username);
    }
}
