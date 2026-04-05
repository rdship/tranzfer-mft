package com.filetransfer.forwarder.service;

import com.filetransfer.forwarder.transfer.TransferSession;
import com.filetransfer.forwarder.transfer.TransferStallException;
import com.filetransfer.forwarder.transfer.TransferWatchdog;
import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.ExternalDestination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Forwards files to an external SFTP server using Apache MINA SSHD client.
 *
 * <p>Uses the {@link TransferWatchdog} for intelligent session management:
 * the connection stays open as long as data is flowing. If no data is
 * transferred for the configured stall threshold (default 30 s), the
 * transfer is interrupted and a {@link TransferStallException} is raised
 * for smart retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SftpForwarderService {

    private final CredentialCryptoClient credentialCrypto;
    private final TransferWatchdog transferWatchdog;

    public void forward(ExternalDestination dest, String filename, byte[] fileBytes) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        TransferSession session = transferWatchdog.register(dest.getName(), filename, fileBytes.length);
        try {
            ClientSession sshSession = client
                    .connect(dest.getUsername(), dest.getHost(), dest.getPort())
                    .verify(15, TimeUnit.SECONDS)
                    .getSession();

            session.recordProgress(0); // reset clock after connection established

            sshSession.addPasswordIdentity(decryptPassword(dest.getEncryptedPassword()));
            sshSession.auth().verify(15, TimeUnit.SECONDS);

            session.recordProgress(0); // reset clock after auth

            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(sshSession)) {
                String remotePath = (dest.getRemotePath() != null ? dest.getRemotePath() : "/") + "/" + filename;

                // Ensure remote directory exists
                String remoteDir = Paths.get(remotePath).getParent().toString();
                try { sftp.mkdir(remoteDir); } catch (Exception ignored) {}

                try (InputStream in = new ByteArrayInputStream(fileBytes);
                     SftpClient.CloseableHandle handle = sftp.open(remotePath,
                             EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create,
                                        SftpClient.OpenMode.Truncate))) {
                    byte[] buffer = new byte[32768];
                    long offset = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        // Check if watchdog flagged us as stalled
                        if (session.isStalled()) {
                            throw new TransferStallException(session);
                        }

                        sftp.write(handle, offset, buffer, 0, read);
                        offset += read;

                        // Record progress — resets the inactivity clock
                        session.recordProgress(read);
                    }
                }

                long elapsed = session.getElapsedSeconds();
                long bytesPerSec = elapsed > 0 ? fileBytes.length / elapsed : fileBytes.length;
                log.info("SFTP forward complete: {} -> {}:{}{} ({} bytes in {}s, ~{} B/s)",
                        filename, dest.getHost(), dest.getPort(), remotePath,
                        fileBytes.length, elapsed, bytesPerSec);
            }
            sshSession.close();
        } catch (TransferStallException e) {
            throw e;
        } catch (Exception e) {
            // When the watchdog interrupts us, I/O calls throw IOException —
            // wrap it as a TransferStallException so the retry logic recognises it
            if (session.isStalled()) {
                throw new TransferStallException(session);
            }
            throw e;
        } finally {
            transferWatchdog.unregister(session.getTransferId());
            client.stop();
        }
    }

    private String decryptPassword(String encryptedPassword) {
        return credentialCrypto.decrypt(encryptedPassword);
    }
}
