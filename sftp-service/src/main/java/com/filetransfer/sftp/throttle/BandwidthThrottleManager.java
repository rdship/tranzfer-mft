package com.filetransfer.sftp.throttle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
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

    /** Per-user active session count: username → count. */
    private final ConcurrentHashMap<String, Integer> userSessionCounts = new ConcurrentHashMap<>();

    /** Per-user burst allowance: username → percent above base rate (e.g. 20 = 120% of base). */
    private final ConcurrentHashMap<String, Integer> userBurstAllowance = new ConcurrentHashMap<>();

    /** Per-user transfer window tracking for burst: username → first-chunk timestamp (epoch ms). */
    private final ConcurrentHashMap<String, Long> userBurstWindowStart = new ConcurrentHashMap<>();

    /** Burst window duration in milliseconds — burst rate allowed for the first N ms of a transfer. */
    private static final long BURST_WINDOW_MS = 5000;

    /**
     * Register per-user QoS limits (called after successful authentication).
     * Tracks session count so limits survive until the last session closes.
     *
     * @param username              the authenticated username
     * @param uploadBps             upload limit in bytes/second (null = use global, 0 = unlimited)
     * @param downloadBps           download limit in bytes/second (null = use global, 0 = unlimited)
     * @param burstAllowancePercent burst allowance percent (null or 0 = no burst)
     */
    public void registerUserLimits(String username, Long uploadBps, Long downloadBps, Integer burstAllowancePercent) {
        if (uploadBps != null) {
            userUploadLimits.put(username, uploadBps);
        }
        if (downloadBps != null) {
            userDownloadLimits.put(username, downloadBps);
        }
        if (burstAllowancePercent != null && burstAllowancePercent > 0) {
            userBurstAllowance.put(username, burstAllowancePercent);
        }
        userSessionCounts.merge(username, 1, Integer::sum);
        log.debug("QoS registered for {}: upload={}B/s download={}B/s burst={}% sessions={}",
                username, uploadBps, downloadBps, burstAllowancePercent, userSessionCounts.get(username));
    }

    /**
     * Unregister per-user QoS limits (called on session close).
     * Only removes limits when the last session for the user closes.
     */
    public void unregisterUser(String username) {
        Integer remaining = userSessionCounts.compute(username, (k, v) -> {
            if (v == null || v <= 1) return null;
            return v - 1;
        });
        if (remaining == null) {
            userUploadLimits.remove(username);
            userDownloadLimits.remove(username);
            userBurstAllowance.remove(username);
            userBurstWindowStart.remove(username);
            log.debug("QoS fully unregistered for {} (last session closed)", username);
        } else {
            log.debug("QoS kept for {} ({} sessions remaining)", username, remaining);
        }
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
     * Supports burst allowance — allows higher throughput for the first
     * {@link #BURST_WINDOW_MS} of a transfer window.
     *
     * @param username         the authenticated username
     * @param bytesTransferred number of bytes just transferred
     * @param isUpload         true for upload throttle, false for download
     */
    public void throttleIfNeeded(String username, long bytesTransferred, boolean isUpload) {
        long baseLimitBps;
        if (isUpload) {
            baseLimitBps = userUploadLimits.getOrDefault(username, uploadBytesPerSecond);
        } else {
            baseLimitBps = userDownloadLimits.getOrDefault(username, downloadBytesPerSecond);
        }
        if (baseLimitBps <= 0 || bytesTransferred <= 0) return;

        // Apply burst allowance if within burst window
        long effectiveLimitBps = baseLimitBps;
        Integer burstPct = userBurstAllowance.get(username);
        if (burstPct != null && burstPct > 0) {
            long now = System.currentTimeMillis();
            long windowStart = userBurstWindowStart.computeIfAbsent(username, k -> now);
            if (now - windowStart <= BURST_WINDOW_MS) {
                effectiveLimitBps = baseLimitBps + (baseLimitBps * burstPct / 100);
            } else {
                // Reset burst window for next transfer
                userBurstWindowStart.put(username, now);
            }
        }

        double expectedSeconds = (double) bytesTransferred / effectiveLimitBps;
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

    /**
     * Returns per-user QoS limits for health/metrics reporting.
     */
    public Map<String, Map<String, Object>> getUserLimits() {
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        java.util.Set<String> allUsers = new java.util.HashSet<>();
        allUsers.addAll(userUploadLimits.keySet());
        allUsers.addAll(userDownloadLimits.keySet());
        for (String user : allUsers) {
            Map<String, Object> limits = new java.util.LinkedHashMap<>();
            Long up = userUploadLimits.get(user);
            Long down = userDownloadLimits.get(user);
            Integer burst = userBurstAllowance.get(user);
            if (up != null) limits.put("uploadBps", up);
            if (down != null) limits.put("downloadBps", down);
            if (burst != null) limits.put("burstAllowancePct", burst);
            result.put(user, limits);
        }
        return result;
    }
}
