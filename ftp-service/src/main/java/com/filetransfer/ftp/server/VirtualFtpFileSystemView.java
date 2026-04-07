package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;

import java.util.UUID;

/**
 * Virtual FileSystemView for FTP sessions.
 * Presents the Phantom Folder VFS to Apache FtpServer.
 * One instance per FTP session, scoped to a single account.
 */
public class VirtualFtpFileSystemView implements FileSystemView {

    private final UUID accountId;
    private final VirtualFileSystem vfs;
    private final StorageServiceClient storageClient;
    private String workingDir = "/";
    private VirtualEntry workingDirEntry; // cached from changeWorkingDirectory to avoid re-stat

    public VirtualFtpFileSystemView(UUID accountId, VirtualFileSystem vfs, StorageServiceClient storageClient) {
        this.accountId = accountId;
        this.vfs = vfs;
        this.storageClient = storageClient;
    }

    @Override
    public FtpFile getHomeDirectory() {
        // Root is special-cased in VirtualFtpFile (always exists, always directory) — no stat needed
        return new VirtualFtpFile("/", accountId, vfs, storageClient, null);
    }

    @Override
    public FtpFile getWorkingDirectory() {
        // Root is special-cased — skip stat; otherwise use cached entry from changeWorkingDirectory
        if ("/".equals(workingDir)) {
            return new VirtualFtpFile("/", accountId, vfs, storageClient, null);
        }
        return new VirtualFtpFile(workingDir, accountId, vfs, storageClient, workingDirEntry);
    }

    @Override
    public boolean changeWorkingDirectory(String dir) throws FtpException {
        String resolved = resolvePath(dir);
        // Root always exists
        if ("/".equals(resolved)) {
            workingDir = "/";
            workingDirEntry = null;
            return true;
        }
        // Use stat() instead of exists() so we can cache the entry for getWorkingDirectory()
        VirtualEntry entry = vfs.stat(accountId, resolved).orElse(null);
        if (entry != null) {
            workingDir = resolved;
            workingDirEntry = entry;
            return true;
        }
        return false;
    }

    @Override
    public FtpFile getFile(String file) throws FtpException {
        String resolved = resolvePath(file);
        // Root is special-cased in VirtualFtpFile — skip the stat call
        if ("/".equals(resolved)) {
            return new VirtualFtpFile("/", accountId, vfs, storageClient, null);
        }
        // Single stat call here; entry passed directly to constructor (no second stat)
        VirtualEntry entry = vfs.stat(accountId, resolved).orElse(null);
        return new VirtualFtpFile(resolved, accountId, vfs, storageClient, entry);
    }

    @Override
    public boolean isRandomAccessible() { return true; }

    @Override
    public void dispose() {
        // No resources to release — session cleanup is automatic
    }

    /**
     * Resolve a path relative to the current working directory.
     */
    private String resolvePath(String path) {
        if (path == null || path.isEmpty()) return workingDir;
        if (path.startsWith("/")) return VirtualFileSystem.normalizePath(path);
        String combined = workingDir.endsWith("/") ? workingDir + path : workingDir + "/" + path;
        return VirtualFileSystem.normalizePath(combined);
    }
}
