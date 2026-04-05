package com.filetransfer.forwarder.service;

import com.filetransfer.forwarder.transfer.ProgressTrackingInputStream;
import com.filetransfer.forwarder.transfer.TransferSession;
import com.filetransfer.forwarder.transfer.TransferStallException;
import com.filetransfer.forwarder.transfer.TransferWatchdog;
import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Forwards files to external FTPS (FTP over TLS/SSL) servers using Apache Commons Net.
 *
 * <p>Like {@link FtpForwarderService}, uses progress-tracking I/O to detect stalls.
 * The TLS handshake phase also resets the inactivity clock — a slow TLS negotiation
 * with a partner won't trigger a false stall.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FtpsForwarderService {

    private final CredentialCryptoClient credentialCrypto;
    private final TransferWatchdog transferWatchdog;

    public void forward(DeliveryEndpoint endpoint, String filename, byte[] fileBytes) throws Exception {
        FTPSClient ftps = new FTPSClient("TLS", false); // explicit TLS
        int port = endpoint.getPort() != null ? endpoint.getPort() : 990;

        TransferSession session = transferWatchdog.register(endpoint.getName(), filename, fileBytes.length);
        try {
            ftps.connect(endpoint.getHost(), port);

            int reply = ftps.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftps.disconnect();
                throw new RuntimeException("FTPS server refused connection: " + reply);
            }

            session.recordProgress(0); // reset clock after connect

            boolean loggedIn = ftps.login(
                    endpoint.getUsername(),
                    decryptPassword(endpoint.getEncryptedPassword()));
            if (!loggedIn) {
                throw new RuntimeException("FTPS login failed for user: " + endpoint.getUsername());
            }

            session.recordProgress(0); // reset clock after login + TLS handshake

            // Set protection
            ftps.execPBSZ(0);
            ftps.execPROT("P"); // Private data channel

            ftps.setFileType(FTP.BINARY_FILE_TYPE);
            ftps.enterLocalPassiveMode();

            String remotePath = (endpoint.getBasePath() != null ? endpoint.getBasePath() : "/") + "/" + filename;

            // Progress-tracking stream lets the watchdog know data is flowing
            try (ProgressTrackingInputStream trackingStream =
                         new ProgressTrackingInputStream(new ByteArrayInputStream(fileBytes), session)) {
                boolean stored = ftps.storeFile(remotePath, trackingStream);
                if (!stored) {
                    throw new RuntimeException("FTPS STOR failed: " + ftps.getReplyString());
                }
            }

            long elapsed = session.getElapsedSeconds();
            long bytesPerSec = elapsed > 0 ? fileBytes.length / elapsed : fileBytes.length;
            log.info("FTPS forward complete: {} -> {}:{}{} ({} bytes in {}s, ~{} B/s)",
                    filename, endpoint.getHost(), port, remotePath,
                    fileBytes.length, elapsed, bytesPerSec);
        } catch (IOException e) {
            if (session.isStalled()) {
                throw new TransferStallException(session);
            }
            throw e;
        } finally {
            transferWatchdog.unregister(session.getTransferId());
            if (ftps.isConnected()) {
                try {
                    ftps.logout();
                    ftps.disconnect();
                } catch (IOException ignored) {}
            }
        }
    }

    private String decryptPassword(String encryptedPassword) {
        return credentialCrypto.decrypt(encryptedPassword);
    }
}
