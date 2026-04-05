package com.filetransfer.forwarder.service;

import com.filetransfer.shared.crypto.CredentialCryptoClient;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Forwards files to external FTPS (FTP over TLS/SSL) servers using Apache Commons Net.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FtpsForwarderService {

    private final CredentialCryptoClient credentialCrypto;

    public void forward(DeliveryEndpoint endpoint, String filename, byte[] fileBytes) throws Exception {
        FTPSClient ftps = new FTPSClient("TLS", false); // explicit TLS
        int port = endpoint.getPort() != null ? endpoint.getPort() : 990;

        try {
            ftps.connect(endpoint.getHost(), port);

            int reply = ftps.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftps.disconnect();
                throw new RuntimeException("FTPS server refused connection: " + reply);
            }

            boolean loggedIn = ftps.login(
                    endpoint.getUsername(),
                    decryptPassword(endpoint.getEncryptedPassword()));
            if (!loggedIn) {
                throw new RuntimeException("FTPS login failed for user: " + endpoint.getUsername());
            }

            // Set protection
            ftps.execPBSZ(0);
            ftps.execPROT("P"); // Private data channel

            ftps.setFileType(FTP.BINARY_FILE_TYPE);
            ftps.enterLocalPassiveMode();

            String remotePath = (endpoint.getBasePath() != null ? endpoint.getBasePath() : "/") + "/" + filename;

            boolean stored = ftps.storeFile(remotePath, new ByteArrayInputStream(fileBytes));
            if (!stored) {
                throw new RuntimeException("FTPS STOR failed: " + ftps.getReplyString());
            }

            log.info("FTPS forward complete: {} → {}:{}{} ({} bytes)",
                    filename, endpoint.getHost(), port, remotePath, fileBytes.length);
        } finally {
            if (ftps.isConnected()) {
                ftps.logout();
                ftps.disconnect();
            }
        }
    }

    private String decryptPassword(String encryptedPassword) {
        return credentialCrypto.decrypt(encryptedPassword);
    }
}
