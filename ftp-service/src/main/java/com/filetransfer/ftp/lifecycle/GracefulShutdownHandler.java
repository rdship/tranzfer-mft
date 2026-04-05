package com.filetransfer.ftp.lifecycle;

import com.filetransfer.ftp.connection.ConnectionTracker;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.FtpServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Handles graceful shutdown of the FTP server on SIGTERM / application stop.
 *
 * <p>When the Spring context begins shutdown, this component:
 * <ol>
 *   <li>Stops accepting new connections immediately.</li>
 *   <li>Waits up to {@code ftp.shutdown.drain-timeout-seconds} for active
 *       connections to complete.</li>
 *   <li>Forces shutdown of any remaining connections.</li>
 * </ol>
 *
 * <p>The default drain timeout is 30 seconds.  Set to 0 for immediate shutdown.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownHandler {

    private final FtpServer ftpServer;
    private final ConnectionTracker connectionTracker;

    @Value("${ftp.shutdown.drain-timeout-seconds:30}")
    private int drainTimeoutSeconds;

    /**
     * Drain active connections and stop the FTP server.
     * Called automatically by Spring before the context closes.
     */
    @PreDestroy
    public void onShutdown() {
        if (ftpServer.isStopped() || ftpServer.isSuspended()) {
            log.info("FTP server already stopped/suspended");
            return;
        }

        int active = connectionTracker.getActiveCount();
        log.info("Graceful FTP shutdown initiated: active_connections={} drain_timeout={}s",
                active, drainTimeoutSeconds);

        // Suspend first to stop accepting new connections
        ftpServer.suspend();
        log.info("FTP server suspended -- no new connections accepted");

        if (active > 0 && drainTimeoutSeconds > 0) {
            long deadline = System.currentTimeMillis() + (drainTimeoutSeconds * 1000L);
            while (connectionTracker.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            int remaining = connectionTracker.getActiveCount();
            if (remaining > 0) {
                log.warn("Drain timeout reached with {} active connections remaining -- forcing shutdown", remaining);
            } else {
                log.info("All FTP connections drained successfully");
            }
        }

        ftpServer.stop();
        log.info("FTP server stopped");
    }
}
