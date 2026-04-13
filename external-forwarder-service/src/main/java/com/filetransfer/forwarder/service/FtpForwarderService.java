package com.filetransfer.forwarder.service;

import com.filetransfer.forwarder.transfer.ProgressTrackingInputStream;
import com.filetransfer.forwarder.transfer.TransferSession;
import com.filetransfer.forwarder.transfer.TransferStallException;
import com.filetransfer.forwarder.transfer.TransferWatchdog;
import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.core.ExternalDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Forwards files to an external FTP server using Apache Commons Net.
 *
 * <p>Uses {@link ProgressTrackingInputStream} to monitor data flow during
 * the upload. The {@link TransferWatchdog} detects stalls and interrupts
 * the transfer if no data moves for the configured threshold (default 30 s).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FtpForwarderService {

    private final CredentialCryptoClient credentialCrypto;
    private final TransferWatchdog transferWatchdog;

    public void forward(ExternalDestination dest, String filename, byte[] fileBytes) throws Exception {
        FTPClient ftp = new FTPClient();
        int port = dest.getPort() != null ? dest.getPort() : 21;

        TransferSession session = transferWatchdog.register(dest.getName(), filename, fileBytes.length);
        try {
            ftp.connect(dest.getHost(), port);

            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                throw new RuntimeException("FTP server refused connection: " + reply);
            }

            session.recordProgress(0); // reset clock after connect

            boolean loggedIn = ftp.login(dest.getUsername(), decryptPassword(dest.getEncryptedPassword()));
            if (!loggedIn) throw new RuntimeException("FTP login failed for user: " + dest.getUsername());

            session.recordProgress(0); // reset clock after login

            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.enterLocalPassiveMode();

            String remotePath = (dest.getRemotePath() != null ? dest.getRemotePath() : "/") + "/" + filename;

            // Wrap the source stream with progress tracking — each read by the FTP
            // library updates the inactivity clock so the watchdog knows data is flowing
            try (ProgressTrackingInputStream trackingStream =
                         new ProgressTrackingInputStream(new ByteArrayInputStream(fileBytes), session)) {
                boolean stored = ftp.storeFile(remotePath, trackingStream);
                if (!stored) throw new RuntimeException("FTP STOR failed: " + ftp.getReplyString());
            }

            long elapsed = session.getElapsedSeconds();
            long bytesPerSec = elapsed > 0 ? fileBytes.length / elapsed : fileBytes.length;
            log.info("FTP forward complete: {} -> {}:{}{} ({} bytes in {}s, ~{} B/s)",
                    filename, dest.getHost(), port, remotePath,
                    fileBytes.length, elapsed, bytesPerSec);

            ftp.logout();
        } catch (IOException e) {
            if (session.isStalled()) {
                throw new TransferStallException(session);
            }
            throw e;
        } finally {
            transferWatchdog.unregister(session.getTransferId());
            if (ftp.isConnected()) {
                try { ftp.disconnect(); } catch (IOException ignored) {}
            }
        }
    }

    private String decryptPassword(String encryptedPassword) {
        return credentialCrypto.decrypt(encryptedPassword);
    }
}
