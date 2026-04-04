package com.filetransfer.client.sync;

import com.filetransfer.client.config.ClientConfig;
import com.filetransfer.client.sftp.FtpTransport;
import com.filetransfer.client.sftp.SftpTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Core sync engine:
 *  1. Watches local outbox → uploads to server /inbox
 *  2. Polls server /outbox → downloads to local inbox
 *  3. Manages sent/failed folders
 *  4. Handles retries and connection recovery
 */
@Slf4j
public class FileSyncEngine {

    private final ClientConfig config;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicLong uploadCount = new AtomicLong();
    private final AtomicLong downloadCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final List<TransferLog> recentTransfers = Collections.synchronizedList(new ArrayList<>());

    private AutoCloseable transport;
    private volatile boolean running = false;

    public FileSyncEngine(ClientConfig config) {
        this.config = config;
    }

    public void start() throws Exception {
        log.info("Starting MFT Client sync engine...");
        running = true;

        // Create local folders
        createFolders();

        // Connect
        connectWithRetry();

        // Schedule sync tasks
        if (config.getSync().isWatchOutbox()) {
            scheduler.scheduleWithFixedDelay(this::syncOutbox, 2,
                    config.getSync().getPollIntervalSeconds(), TimeUnit.SECONDS);
            log.info("Outbox watcher started: {}", config.getFolders().getOutbox());
        }

        if (config.getSync().isPollInbox()) {
            scheduler.scheduleWithFixedDelay(this::syncInbox, 5,
                    config.getSync().getPollIntervalSeconds(), TimeUnit.SECONDS);
            log.info("Inbox poller started (every {}s)", config.getSync().getPollIntervalSeconds());
        }

        log.info("=======================================================");
        log.info("  MFT Client READY");
        log.info("  Server: {}://{}:{}",
                config.getServer().getProtocol(),
                config.getServer().getHost(),
                config.getServer().getPort());
        log.info("  Outbox: {} -> server {}", config.getFolders().getOutbox(), config.getFolders().getRemoteInbox());
        log.info("  Inbox:  server {} -> {}", config.getFolders().getRemoteOutbox(), config.getFolders().getInbox());
        log.info("=======================================================");
        log.info("Drop files into '{}' to transfer them to the server.", config.getFolders().getOutbox());
    }

    private void createFolders() throws IOException {
        for (String dir : List.of(
                config.getFolders().getOutbox(),
                config.getFolders().getInbox(),
                config.getFolders().getSent(),
                config.getFolders().getFailed())) {
            Files.createDirectories(Paths.get(dir));
        }
    }

    private void connectWithRetry() throws Exception {
        int maxRetries = config.getServer().getMaxRetries();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if ("FTP".equalsIgnoreCase(config.getServer().getProtocol())) {
                    FtpTransport ftp = new FtpTransport(config.getServer());
                    ftp.connect();
                    transport = ftp;
                } else {
                    SftpTransport sftp = new SftpTransport(config.getServer());
                    sftp.connect();
                    transport = sftp;
                }
                return;
            } catch (Exception e) {
                log.warn("Connection attempt {}/{} failed: {}", attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) throw e;
                Thread.sleep(5000L * attempt);
            }
        }
    }

    private void ensureConnected() {
        boolean connected = transport instanceof SftpTransport s ? s.isConnected() :
                transport instanceof FtpTransport f ? f.isConnected() : false;
        if (!connected) {
            log.warn("Connection lost. Reconnecting...");
            try { connectWithRetry(); } catch (Exception e) {
                log.error("Reconnection failed: {}", e.getMessage());
            }
        }
    }

    /** Upload files from local outbox to server inbox */
    private void syncOutbox() {
        if (!running) return;
        try {
            ensureConnected();
            Path outbox = Paths.get(config.getFolders().getOutbox());
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outbox)) {
                for (Path file : stream) {
                    if (Files.isDirectory(file)) continue;
                    if (!matchesPattern(file.getFileName().toString())) continue;
                    if (config.getSync().getMaxFileSizeMb() > 0 &&
                            Files.size(file) > config.getSync().getMaxFileSizeMb() * 1024L * 1024L) {
                        log.warn("Skipping {} — exceeds max size {}MB", file.getFileName(), config.getSync().getMaxFileSizeMb());
                        continue;
                    }
                    uploadFile(file);
                }
            }
        } catch (Exception e) {
            log.error("Outbox sync error: {}", e.getMessage());
            errorCount.incrementAndGet();
        }
    }

    private void uploadFile(Path file) {
        try {
            if (transport instanceof SftpTransport s) {
                s.upload(file, config.getFolders().getRemoteInbox());
            } else if (transport instanceof FtpTransport f) {
                f.upload(file, config.getFolders().getRemoteInbox());
            }

            // Move to sent
            Path sent = Paths.get(config.getFolders().getSent()).resolve(file.getFileName());
            Files.move(file, sent, StandardCopyOption.REPLACE_EXISTING);
            uploadCount.incrementAndGet();

            logTransfer("UPLOAD", file.getFileName().toString(), "OK", null);
            log.info("Uploaded and archived: {} -> sent/", file.getFileName());
        } catch (Exception e) {
            log.error("Upload failed for {}: {}", file.getFileName(), e.getMessage());
            try {
                Path failed = Paths.get(config.getFolders().getFailed()).resolve(file.getFileName());
                Files.move(file, failed, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
            errorCount.incrementAndGet();
            logTransfer("UPLOAD", file.getFileName().toString(), "FAIL", e.getMessage());
        }
    }

    /** Download files from server outbox to local inbox */
    private void syncInbox() {
        if (!running) return;
        try {
            ensureConnected();
            List<String> remoteFiles;
            if (transport instanceof SftpTransport s) {
                remoteFiles = s.listRemote(config.getFolders().getRemoteOutbox());
            } else if (transport instanceof FtpTransport f) {
                remoteFiles = f.listRemote(config.getFolders().getRemoteOutbox());
            } else return;

            Path inbox = Paths.get(config.getFolders().getInbox());
            for (String filename : remoteFiles) {
                if (!matchesPattern(filename)) continue;
                // Skip if already downloaded
                if (Files.exists(inbox.resolve(filename))) continue;
                downloadFile(filename, inbox);
            }
        } catch (Exception e) {
            log.error("Inbox sync error: {}", e.getMessage());
            errorCount.incrementAndGet();
        }
    }

    private void downloadFile(String filename, Path inbox) {
        try {
            if (transport instanceof SftpTransport s) {
                s.download(config.getFolders().getRemoteOutbox(), filename, inbox);
                if (config.getSync().isDeleteAfterDownload()) {
                    s.deleteRemote(config.getFolders().getRemoteOutbox(), filename);
                }
            } else if (transport instanceof FtpTransport f) {
                f.download(config.getFolders().getRemoteOutbox(), filename, inbox);
                if (config.getSync().isDeleteAfterDownload()) {
                    f.deleteRemote(config.getFolders().getRemoteOutbox(), filename);
                }
            }
            downloadCount.incrementAndGet();
            logTransfer("DOWNLOAD", filename, "OK", null);
        } catch (Exception e) {
            log.error("Download failed for {}: {}", filename, e.getMessage());
            errorCount.incrementAndGet();
            logTransfer("DOWNLOAD", filename, "FAIL", e.getMessage());
        }
    }

    private boolean matchesPattern(String filename) {
        if (config.getSync().getExcludePattern() != null &&
                Pattern.matches(config.getSync().getExcludePattern(), filename)) return false;
        if (config.getSync().getIncludePattern() != null) {
            return Pattern.matches(config.getSync().getIncludePattern(), filename);
        }
        return true;
    }

    private void logTransfer(String direction, String filename, String status, String error) {
        TransferLog entry = new TransferLog(Instant.now(), direction, filename, status, error);
        recentTransfers.add(entry);
        if (recentTransfers.size() > 100) recentTransfers.remove(0);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        try { if (transport != null) transport.close(); } catch (Exception ignored) {}
        log.info("MFT Client stopped. Uploads: {}, Downloads: {}, Errors: {}",
                uploadCount.get(), downloadCount.get(), errorCount.get());
    }

    public String getStatus() {
        return String.format("Uploads: %d | Downloads: %d | Errors: %d | Connected: %s",
                uploadCount.get(), downloadCount.get(), errorCount.get(),
                transport instanceof SftpTransport s ? s.isConnected() :
                        transport instanceof FtpTransport f ? f.isConnected() : false);
    }

    public record TransferLog(Instant time, String direction, String filename, String status, String error) {}
}
