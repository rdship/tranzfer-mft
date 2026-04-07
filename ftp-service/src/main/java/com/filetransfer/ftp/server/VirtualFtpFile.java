package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.FtpFile;

import java.io.*;
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

    private VirtualFtpFile(String virtualPath, UUID accountId, VirtualFileSystem vfs,
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
     * Output stream that buffers writes and flushes to CAS on close.
     */
    private class VirtualOutputStream extends ByteArrayOutputStream {
        private final long offset;

        VirtualOutputStream(long offset) { this.offset = offset; }

        @Override
        public void close() throws IOException {
            super.close();
            byte[] data = toByteArray();
            if (data.length == 0) return;

            try {
                String filename = VirtualFileSystem.nameOf(virtualPath);
                Map<String, Object> result = storageClient.store(filename, data, null, accountId.toString());

                String sha256 = (String) result.get("sha256");
                String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;

                log.info("[VFS-FTP] Stored {}: {} ({} bytes)", virtualPath,
                        sha256 != null ? sha256.substring(0, 8) + "..." : "n/a", data.length);

                vfs.writeFile(accountId, virtualPath, sha256, data.length, trackId, null);
            } catch (Exception e) {
                throw new IOException("Failed to store file to CAS: " + virtualPath, e);
            }
        }
    }
}
