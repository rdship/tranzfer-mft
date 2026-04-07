package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
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

    public VirtualFtpFileSystemView(UUID accountId, VirtualFileSystem vfs, StorageServiceClient storageClient) {
        this.accountId = accountId;
        this.vfs = vfs;
        this.storageClient = storageClient;
    }

    @Override
    public FtpFile getHomeDirectory() {
        return new VirtualFtpFile("/", accountId, vfs, storageClient);
    }

    @Override
    public FtpFile getWorkingDirectory() {
        return new VirtualFtpFile(workingDir, accountId, vfs, storageClient);
    }

    @Override
    public boolean changeWorkingDirectory(String dir) throws FtpException {
        String resolved = resolvePath(dir);
        // Root always exists
        if ("/".equals(resolved)) {
            workingDir = "/";
            return true;
        }
        if (vfs.exists(accountId, resolved)) {
            workingDir = resolved;
            return true;
        }
        return false;
    }

    @Override
    public FtpFile getFile(String file) throws FtpException {
        return new VirtualFtpFile(resolvePath(file), accountId, vfs, storageClient);
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
