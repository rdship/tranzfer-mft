package com.filetransfer.client.sftp;

import com.filetransfer.client.config.ClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP transport — connects to TranzFer FTP server.
 */
@Slf4j
public class FtpTransport implements AutoCloseable {

    private final ClientConfig.ServerConnection config;
    private FTPClient ftp;

    public FtpTransport(ClientConfig.ServerConnection config) {
        this.config = config;
    }

    public void connect() throws Exception {
        ftp = new FTPClient();
        ftp.setConnectTimeout(config.getTimeoutSeconds() * 1000);
        ftp.connect(config.getHost(), config.getPort());
        ftp.login(config.getUsername(), config.getPassword());
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        log.info("Connected to FTP server {}:{} as {}", config.getHost(), config.getPort(), config.getUsername());
    }

    public void upload(Path localFile, String remotePath) throws IOException {
        String remoteFile = remotePath + "/" + localFile.getFileName();
        log.info("Uploading {} -> {}", localFile.getFileName(), remoteFile);
        ftp.makeDirectory(remotePath);
        try (InputStream in = Files.newInputStream(localFile)) {
            ftp.storeFile(remoteFile, in);
        }
        log.info("Upload complete: {} ({} bytes)", localFile.getFileName(), Files.size(localFile));
    }

    public List<String> listRemote(String remotePath) throws IOException {
        List<String> files = new ArrayList<>();
        FTPFile[] ftpFiles = ftp.listFiles(remotePath);
        if (ftpFiles != null) {
            for (FTPFile f : ftpFiles) {
                if (f.isFile()) files.add(f.getName());
            }
        }
        return files;
    }

    public void download(String remotePath, String filename, Path localDir) throws IOException {
        String remoteFile = remotePath + "/" + filename;
        Path localFile = localDir.resolve(filename);
        log.info("Downloading {} -> {}", remoteFile, localFile);
        try (OutputStream out = Files.newOutputStream(localFile)) {
            ftp.retrieveFile(remoteFile, out);
        }
        log.info("Download complete: {}", filename);
    }

    public void deleteRemote(String remotePath, String filename) throws IOException {
        ftp.deleteFile(remotePath + "/" + filename);
    }

    public boolean isConnected() {
        return ftp != null && ftp.isConnected();
    }

    @Override
    public void close() {
        try { if (ftp != null) { ftp.logout(); ftp.disconnect(); } } catch (Exception ignored) {}
        log.info("Disconnected from FTP server");
    }
}
