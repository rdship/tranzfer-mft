package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * Virtual FileSystem for SFTP sessions.
 * One instance per SFTP session, scoped to a single account.
 * All I/O delegates to VirtualFileSystem (DB) + StorageServiceClient (CAS).
 *
 * <p>When a file write completes ({@code VirtualWriteChannel.close()}), the
 * optional {@link WriteCompletionCallback} fires so the SFTP server can
 * trigger file routing after the VFS entry is guaranteed to exist.
 */
public class VirtualSftpFileSystem extends FileSystem {

    @Getter
    private final VirtualSftpFileSystemProvider provider;
    @Getter
    private final UUID accountId;
    @Getter
    private final VirtualFileSystem vfs;
    @Getter
    private final StorageServiceClient storageClient;
    @Getter
    private final VfsWriteCallback writeCallback;
    private volatile boolean open = true;

    public VirtualSftpFileSystem(UUID accountId, VirtualFileSystem vfs,
                                  StorageServiceClient storageClient,
                                  VfsWriteCallback writeCallback) {
        this.accountId = accountId;
        this.vfs = vfs;
        this.storageClient = storageClient;
        this.writeCallback = writeCallback;
        this.provider = new VirtualSftpFileSystemProvider(this);
    }

    /** Backwards-compatible constructor (no callback — used by getUserHomeDir). */
    public VirtualSftpFileSystem(UUID accountId, VirtualFileSystem vfs, StorageServiceClient storageClient) {
        this(accountId, vfs, storageClient, null);
    }

    @Override
    public FileSystemProvider provider() { return provider; }

    @Override
    public void close() throws IOException { open = false; }

    @Override
    public boolean isOpen() { return open; }

    @Override
    public boolean isReadOnly() { return false; }

    @Override
    public String getSeparator() { return "/"; }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(new VirtualSftpPath(this, "/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() { return List.of(); }

    @Override
    public Set<String> supportedFileAttributeViews() {
        // Only "basic" — VirtualSftpFileAttributes implements BasicFileAttributes,
        // NOT PosixFileAttributes. Claiming "posix" caused MINA SSHD's readdir to
        // request posix:* attributes on every entry, fail the cast to
        // PosixFileAttributes, and abort the listing with "Couldn't read directory".
        // Clients that want owner/group/mode fall back to SFTP v3 default bits
        // from VirtualSftpFileAttributes.
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String m : more) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(m);
        }
        return new VirtualSftpPath(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        // Simple glob support
        String[] parts = syntaxAndPattern.split(":", 2);
        String pattern = parts.length > 1 ? parts[1] : parts[0];
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);
        return path -> compiled.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("User principal lookup not supported");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("Watch service not supported on virtual filesystem");
    }
}
