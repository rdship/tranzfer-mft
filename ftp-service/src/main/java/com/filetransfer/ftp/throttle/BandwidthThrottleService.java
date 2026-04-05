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
 * <p>These defaults apply to all users.  Per-user overrides could be added
 * by reading from the {@code TransferAccount.metadata} JSON column.
 */
@Slf4j
@Service
public class BandwidthThrottleService {

    @Value("${ftp.throttle.max-upload-rate:0}")
    private int maxUploadRate;

    @Value("${ftp.throttle.max-download-rate:0}")
    private int maxDownloadRate;

    /**
     * Create a {@link TransferRatePermission} authority reflecting the
     * configured bandwidth limits.
     *
     * @return the authority to add to the FTP user, or {@code null} if both rates are unlimited
     */
    public Authority createRatePermission() {
        if (maxUploadRate <= 0 && maxDownloadRate <= 0) {
            return null;
        }
        log.debug("Bandwidth throttle: upload={}B/s download={}B/s", maxUploadRate, maxDownloadRate);
        return new TransferRatePermission(maxDownloadRate, maxUploadRate);
    }

    /** Maximum upload rate in bytes/second (0 = unlimited). */
    public int getMaxUploadRate() {
        return maxUploadRate;
    }

    /** Maximum download rate in bytes/second (0 = unlimited). */
    public int getMaxDownloadRate() {
        return maxDownloadRate;
    }
}
