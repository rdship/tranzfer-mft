package com.filetransfer.forwarder.service;

import com.filetransfer.shared.entity.ExternalDestination;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Forwards files to an external FTP server using Apache Commons Net.
 */
@Slf4j
@Service
public class FtpForwarderService {

    public void forward(ExternalDestination dest, String filename, byte[] fileBytes) throws Exception {
        FTPClient ftp = new FTPClient();
        ftp.connect(dest.getHost(), dest.getPort() != null ? dest.getPort() : 21);

        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftp.disconnect();
            throw new RuntimeException("FTP server refused connection: " + reply);
        }

        boolean loggedIn = ftp.login(dest.getUsername(), decryptPassword(dest.getEncryptedPassword()));
        if (!loggedIn) throw new RuntimeException("FTP login failed for user: " + dest.getUsername());

        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();

        String remotePath = (dest.getRemotePath() != null ? dest.getRemotePath() : "/") + "/" + filename;

        boolean stored = ftp.storeFile(remotePath, new ByteArrayInputStream(fileBytes));
        if (!stored) throw new RuntimeException("FTP STOR failed: " + ftp.getReplyString());

        log.info("FTP forward complete: {} → {}:{}{}", filename, dest.getHost(), dest.getPort(), remotePath);
        ftp.logout();
        ftp.disconnect();
    }

    private String decryptPassword(String encryptedPassword) {
        return encryptedPassword; // TODO: decrypt via encryption-service
    }
}
