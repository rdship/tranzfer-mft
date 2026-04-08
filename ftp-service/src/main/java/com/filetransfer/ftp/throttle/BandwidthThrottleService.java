package com.filetransfer.ftp.throttle;

import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Provides per-user bandwidth throttling via Apache FtpServer's
 * {@link TransferRatePermission} authority.
 *
 * <p>Upload and download rates are specified in <strong>bytes per second</strong>.
 * A value of {@code 0} means unlimited.
 *
 * <p>Supports per-user overrides from TransferAccount QoS fields, with
 * fallback to global configuration defaults.</p>
 */
@Slf4j
@Service
public class BandwidthThrottleService {

    @Value("${ftp.throttle.max-upload-rate:0}")
    private int maxUploadRate;

    @Value("${ftp.throttle.max-download-rate:0}")
    private int maxDownloadRate;

    /**
     * Create a {@link TransferRatePermission} authority with global defaults.
     *
     * @return the authority, or {@code null} if both rates are unlimited
     */
    public Authority createRatePermission() {
        if (maxUploadRate <= 0 && maxDownloadRate <= 0) {
            return null;
        }
        log.debug("Bandwidth throttle (global): upload={}B/s download={}B/s", maxUploadRate, maxDownloadRate);
        return new TransferRatePermission(maxDownloadRate, maxUploadRate);
    }

    /**
     * Create a per-user {@link TransferRatePermission} from TransferAccount QoS fields.
     * Falls back to global defaults for null fields.
     *
     * @param userUploadBps   per-user upload limit (null = use global, 0 = unlimited)
     * @param userDownloadBps per-user download limit (null = use global, 0 = unlimited)
     * @return the authority, or {@code null} if both effective rates are unlimited
     */
    public Authority createRatePermission(Long userUploadBps, Long userDownloadBps) {
        int effectiveUpload = userUploadBps != null ? userUploadBps.intValue() : maxUploadRate;
        int effectiveDownload = userDownloadBps != null ? userDownloadBps.intValue() : maxDownloadRate;

        if (effectiveUpload <= 0 && effectiveDownload <= 0) {
            return null;
        }
        log.debug("Bandwidth throttle (per-user): upload={}B/s download={}B/s",
                effectiveUpload, effectiveDownload);
        return new TransferRatePermission(effectiveDownload, effectiveUpload);
    }

    public int getMaxUploadRate() {
        return maxUploadRate;
    }

    public int getMaxDownloadRate() {
        return maxDownloadRate;
    }
}
