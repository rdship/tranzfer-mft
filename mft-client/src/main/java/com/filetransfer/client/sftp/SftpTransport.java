package com.filetransfer.client.sftp;

import com.filetransfer.client.config.ClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SFTP transport — connects to TranzFer SFTP server.
 */
@Slf4j
public class SftpTransport implements AutoCloseable {

    private final ClientConfig.ServerConnection config;
    private SshClient sshClient;
    private ClientSession session;
    private SftpClient sftp;

    public SftpTransport(ClientConfig.ServerConnection config) {
        this.config = config;
    }

    public void connect() throws Exception {
        sshClient = SshClient.setUpDefaultClient();
        sshClient.start();

        session = sshClient.connect(config.getUsername(), config.getHost(), config.getPort())
                .verify(config.getTimeoutSeconds() * 1000L).getSession();

        if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isBlank()) {
            var keyProvider = new org.apache.sshd.common.keyprovider.FileKeyPairProvider(
                    Paths.get(config.getPrivateKeyPath()));
            for (java.security.KeyPair kp : keyProvider.loadKeys(session)) {
                session.addPublicKeyIdentity(kp);
            }
        } else {
            session.addPasswordIdentity(config.getPassword());
        }

        session.auth().verify(config.getTimeoutSeconds() * 1000L);
        sftp = SftpClientFactory.instance().createSftpClient(session);
        log.info("Connected to SFTP server {}:{} as {}", config.getHost(), config.getPort(), config.getUsername());
    }

    public void upload(Path localFile, String remotePath) throws IOException {
        String remoteFile = remotePath + "/" + localFile.getFileName();
        log.info("Uploading {} -> {}", localFile.getFileName(), remoteFile);

        try {
            sftp.stat(remotePath);
        } catch (Exception e) {
            sftp.mkdir(remotePath);
        }

        try (OutputStream out = sftp.write(remoteFile);
             InputStream in = Files.newInputStream(localFile)) {
            in.transferTo(out);
        }
        log.info("Upload complete: {} ({} bytes)", localFile.getFileName(), Files.size(localFile));
    }

    public List<String> listRemote(String remotePath) throws IOException {
        List<String> files = new ArrayList<>();
        try {
            for (SftpClient.DirEntry entry : sftp.readDir(remotePath)) {
                String name = entry.getFilename();
                if (!name.equals(".") && !name.equals("..") && !entry.getAttributes().isDirectory()) {
                    files.add(name);
                }
            }
        } catch (Exception e) {
            log.debug("Could not list remote path {}: {}", remotePath, e.getMessage());
        }
        return files;
    }

    public void download(String remotePath, String filename, Path localDir) throws IOException {
        String remoteFile = remotePath + "/" + filename;
        Path localFile = localDir.resolve(filename);
        log.info("Downloading {} -> {}", remoteFile, localFile);

        try (InputStream in = sftp.read(remoteFile);
             OutputStream out = Files.newOutputStream(localFile)) {
            in.transferTo(out);
        }
        log.info("Download complete: {} ({} bytes)", filename, Files.size(localFile));
    }

    public void deleteRemote(String remotePath, String filename) throws IOException {
        sftp.remove(remotePath + "/" + filename);
        log.debug("Deleted remote file: {}/{}", remotePath, filename);
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @Override
    public void close() {
        try { if (sftp != null) sftp.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (sshClient != null) sshClient.stop(); } catch (Exception ignored) {}
        log.info("Disconnected from SFTP server");
    }
}
