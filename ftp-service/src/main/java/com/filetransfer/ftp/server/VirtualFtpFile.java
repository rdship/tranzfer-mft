package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.FtpFile;

import com.filetransfer.shared.entity.vfs.VirtualEntry;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual FTP file backed by the Phantom Folder VFS.
 * Implements Apache FtpServer's FtpFile interface.
 *
 * <p>Directories are zero-cost DB rows.
 * Files stream from/to content-addressed storage via Storage Manager.
 */
@Slf4j
public class VirtualFtpFile implements FtpFile {

    private final String virtualPath;
    private final UUID accountId;
    private final VirtualFileSystem vfs;
    private final StorageServiceClient storageClient;
    private final VirtualEntry entry; // null if path doesn't exist yet

    public VirtualFtpFile(String virtualPath, UUID accountId, VirtualFileSystem vfs,
                           StorageServiceClient storageClient) {
        this.virtualPath = VirtualFileSystem.normalizePath(virtualPath);
        this.accountId = accountId;
        this.vfs = vfs;
        this.storageClient = storageClient;
        this.entry = vfs.stat(accountId, this.virtualPath).orElse(null);
    }

    VirtualFtpFile(String virtualPath, UUID accountId, VirtualFileSystem vfs,
                    StorageServiceClient storageClient, VirtualEntry entry) {
        this.virtualPath = VirtualFileSystem.normalizePath(virtualPath);
        this.accountId = accountId;
        this.vfs = vfs;
        this.storageClient = storageClient;
        this.entry = entry;
    }

    @Override
    public String getAbsolutePath() { return virtualPath; }

    @Override
    public String getName() {
        String name = VirtualFileSystem.nameOf(virtualPath);
        return name.isEmpty() ? "/" : name;
    }

    @Override
    public boolean isHidden() { return getName().startsWith("."); }

    @Override
    public boolean isDirectory() {
        if ("/".equals(virtualPath)) return true;
        return entry != null && entry.isDirectory();
    }

    @Override
    public boolean isFile() { return entry != null && entry.isFile(); }

    @Override
    public boolean doesExist() {
        return "/".equals(virtualPath) || entry != null;
    }

    @Override
    public boolean isReadable() { return doesExist(); }

    @Override
    public boolean isWritable() { return true; }

    @Override
    public boolean isRemovable() { return !"/".equals(virtualPath); }

    @Override
    public String getOwnerName() { return accountId.toString(); }

    @Override
    public String getGroupName() { return "mft"; }

    @Override
    public int getLinkCount() { return isDirectory() ? 2 : 1; }

    @Override
    public long getLastModified() {
        if (entry != null && entry.getUpdatedAt() != null) {
            return entry.getUpdatedAt().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    @Override
    public boolean setLastModified(long time) { return true; }

    @Override
    public long getSize() {
        return entry != null ? entry.getSizeBytes() : 0;
    }

    @Override
    public Object getPhysicalFile() { return null; }

    @Override
    public boolean mkdir() {
        try {
            vfs.mkdir(accountId, virtualPath);
            return true;
        } catch (Exception e) {
            log.warn("VFS mkdir failed: {}: {}", virtualPath, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete() {
        try {
            return vfs.delete(accountId, virtualPath) > 0;
        } catch (Exception e) {
            log.warn("VFS delete failed: {}: {}", virtualPath, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean move(FtpFile destination) {
        try {
            vfs.move(accountId, virtualPath, destination.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.warn("VFS move failed: {} → {}: {}", virtualPath, destination.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<? extends FtpFile> listFiles() {
        if (!isDirectory()) return List.of();
        return vfs.list(accountId, virtualPath).stream()
                .map(e -> new VirtualFtpFile(e.getPath(), accountId, vfs, storageClient, e))
                .map(f -> (FtpFile) f)
                .toList();
    }

    @Override
    public OutputStream createOutputStream(long offset) throws IOException {
        return new VirtualOutputStream(offset);
    }

    @Override
    public InputStream createInputStream(long offset) throws IOException {
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Cannot read: " + virtualPath);
        }
        try {
            byte[] data = vfs.readFile(accountId, virtualPath);
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            if (offset > 0) in.skip(offset);
            return in;
        } catch (Exception e) {
            throw new IOException("Failed to read from CAS: " + virtualPath, e);
        }
    }

    /**
     * Output stream that spools to a temp file, flushes to storage on close.
     *
     * <p>Bucket-aware: routes to INLINE (DB), STANDARD (CAS), or CHUNKED (CAS chunks)
     * based on file size. Uses a temp file instead of heap buffering so STANDARD and
     * CHUNKED files never occupy heap memory. Never creates storage — always onboards
     * to Storage Manager for STANDARD/CHUNKED, or stores inline in VFS for small files.
     */
    private class VirtualOutputStream extends OutputStream {
        private final Path tempFile;
        private final OutputStream tempOut;
        private long bytesWritten = 0;

        VirtualOutputStream(long offset) throws IOException {
            this.tempFile = Files.createTempFile("vfs-ftp-", ".tmp");
            this.tempOut = Files.newOutputStream(tempFile);
        }

        @Override
        public void write(int b) throws IOException {
            tempOut.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            tempOut.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public void close() throws IOException {
            tempOut.close();
            if (bytesWritten == 0) {
                Files.deleteIfExists(tempFile);
                return;
            }

            try {
                String filename = VirtualFileSystem.nameOf(virtualPath);
                String bucket = vfs.determineBucket(bytesWritten, accountId);

                switch (bucket) {
                    case "INLINE" -> {
                        // Small file — read back to byte[] (< 64KB, safe in heap)
                        byte[] data = Files.readAllBytes(tempFile);
                        vfs.writeFile(accountId, virtualPath, null, bytesWritten, null, null, data);
                        log.info("[VFS-FTP] Inline stored {}: {} bytes", virtualPath, bytesWritten);
                    }
                    case "CHUNKED" -> {
                        storeChunked(filename, bytesWritten);
                    }
                    default -> {
                        // STANDARD: stream temp file to Storage Manager — zero heap copy
                        Map<String, Object> result = storageClient.store(tempFile, null, accountId.toString());
                        String sha256 = (String) result.get("sha256");
                        String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;
                        log.info("[VFS-FTP] CAS stored {}: {} ({} bytes)", virtualPath,
                                sha256 != null ? sha256.substring(0, 8) + "..." : "n/a", bytesWritten);
                        vfs.writeFile(accountId, virtualPath, sha256, bytesWritten, trackId, null, null);
                    }
                }
            } catch (Exception e) {
                throw new IOException("Failed to store file: " + virtualPath, e);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        private void storeChunked(String filename, long fileSize) throws IOException {
            int chunkSize = 4 * 1024 * 1024; // 4MB chunks

            // 1. Create VFS entry as CHUNKED manifest (WAIP-protected)
            VirtualEntry entry = vfs.writeFile(accountId, virtualPath, null, fileSize, null, null, null);

            // 2. Stream each chunk from temp file → Storage Manager → register in VFS
            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            try (InputStream in = Files.newInputStream(tempFile)) {
                for (int i = 0; i < totalChunks; i++) {
                    int length = (int) Math.min(chunkSize, fileSize - (long) i * chunkSize);
                    byte[] chunk = in.readNBytes(length);

                    String chunkName = filename + ".chunk." + i;
                    Map<String, Object> result = storageClient.store(chunkName, chunk, null, accountId.toString());

                    String sha256 = (String) result.get("sha256");
                    vfs.registerChunk(entry.getId(), i, sha256 != null ? sha256 : "",
                            length, sha256 != null ? sha256 : "");

                    log.debug("[VFS-FTP] Chunk {}/{} onboarded for {}", i + 1, totalChunks, virtualPath);
                }
            }
            log.info("[VFS-FTP] Chunked onboarded {}: {} bytes in {} chunks", virtualPath, fileSize, totalChunks);
        }
    }
}
