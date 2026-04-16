package com.filetransfer.shared.vfs;

/**
 * Callback fired after a VFS write completes — file stored in MinIO + VFS entry created.
 * Used by protocol servers (SFTP, FTP, FTP-Web, AS2) to trigger file routing
 * at the correct moment for VIRTUAL-mode accounts.
 */
@FunctionalInterface
public interface VfsWriteCallback {
    void onFileWritten(String virtualPath, long sizeBytes, String storageKey);
}
