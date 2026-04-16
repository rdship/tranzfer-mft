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

    /**
     * Fired after VirtualWriteChannel.close() stores bytes and creates the VFS entry.
     * The SFTP server uses this to publish FileUploadedEvent to RabbitMQ.
     */
    @FunctionalInterface
    public interface WriteCompletionCallback {
        void onFileWritten(String virtualPath, long sizeBytes, String storageKey);
    }

    @Getter
    private final VirtualSftpFileSystemProvider provider;
    @Getter
    private final UUID accountId;
    @Getter
    private final VirtualFileSystem vfs;
    @Getter
    private final StorageServiceClient storageClient;
    @Getter
    private final WriteCompletionCallback writeCallback;
    private volatile boolean open = true;

    public VirtualSftpFileSystem(UUID accountId, VirtualFileSystem vfs,
                                  StorageServiceClient storageClient,
                                  WriteCompletionCallback writeCallback) {
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
        return Set.of("basic", "posix");
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
