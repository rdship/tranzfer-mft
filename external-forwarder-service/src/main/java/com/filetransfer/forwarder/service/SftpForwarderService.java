package com.filetransfer.forwarder.service;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SftpForwarderService {

    private final CredentialCryptoClient credentialCrypto;

    public void forward(ExternalDestination dest, String filename, byte[] fileBytes) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            ClientSession session = client
                    .connect(dest.getUsername(), dest.getHost(), dest.getPort())
                    .verify(15, TimeUnit.SECONDS)
                    .getSession();

            session.addPasswordIdentity(decryptPassword(dest.getEncryptedPassword()));
            session.auth().verify(15, TimeUnit.SECONDS);

            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
                        sftp.write(handle, offset, buffer, 0, read);
                        offset += read;
                    }
                }
                log.info("SFTP forward complete: {} → {}:{}{}", filename, dest.getHost(), dest.getPort(), remotePath);
            }
            session.close();
        } finally {
            client.stop();
        }
    }

    private String decryptPassword(String encryptedPassword) {
        return credentialCrypto.decrypt(encryptedPassword);
    }
}
