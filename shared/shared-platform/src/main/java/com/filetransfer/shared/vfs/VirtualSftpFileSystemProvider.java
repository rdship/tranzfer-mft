package com.filetransfer.shared.vfs;

import com.filetransfer.shared.entity.vfs.VirtualEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.*;

/**
 * Virtual FileSystemProvider for SFTP.
 *
 * <p>Translates POSIX-like file operations from Apache SSHD into:
 * <ul>
 *   <li>Directory operations → VirtualFileSystem (DB)</li>
 *   <li>File reads → StorageServiceClient.retrieve() (CAS)</li>
 *   <li>File writes → StorageServiceClient.store() (CAS)</li>
 * </ul>
 *
 * <p>One instance per session. Thread-safe for concurrent SFTP channels.
 */
@Slf4j
public class VirtualSftpFileSystemProvider extends FileSystemProvider {

    private final VirtualSftpFileSystem fileSystem;

    VirtualSftpFileSystemProvider(VirtualSftpFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    private UUID accountId() { return fileSystem.getAccountId(); }
    private VirtualFileSystem vfs() { return fileSystem.getVfs(); }

    private String pathString(Path path) {
        if (path instanceof VirtualSftpPath vp) return vp.getPathString();
        return VirtualFileSystem.normalizePath(path.toString());
    }

    @Override
    public String getScheme() { return "vfs"; }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) { return fileSystem; }

    @Override
    public FileSystem getFileSystem(URI uri) { return fileSystem; }

    @Override
    public Path getPath(URI uri) {
        return new VirtualSftpPath(fileSystem, uri.getPath());
    }

    // ── Byte Channels (Read / Write) ────────────────────────────────────

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                               FileAttribute<?>... attrs) throws IOException {
        String vpath = pathString(path);
        boolean write = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.CREATE)
                || options.contains(StandardOpenOption.CREATE_NEW) || options.contains(StandardOpenOption.APPEND);

        // R134I — tester R134H confirmed this provider IS reached by MINA
        // but R134G's close-time callback log never fires. Log the channel
        // class we return here so the next run shows whether SSHD gets our
        // VirtualWriteChannel (expected) or something else.
        SeekableByteChannel ch = write ? new VirtualWriteChannel(vpath) : new VirtualReadChannel(vpath);
        log.info("[vfs-provider] newByteChannel vpath={} write={} returned={} callbackPresent={}",
                vpath, write, ch.getClass().getSimpleName(),
                fileSystem.getWriteCallback() != null);
        return ch;
    }

    // ── Directory Streams ───────────────────────────────────────────────

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        String vpath = pathString(dir);
        List<VirtualEntry> entries = vfs().list(accountId(), vpath);

        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return entries.stream()
                        .map(e -> (Path) new VirtualSftpPath(fileSystem, e.getPath()))
                        .filter(p -> {
                            try { return filter == null || filter.accept(p); }
                            catch (IOException ex) { return false; }
                        })
                        .iterator();
            }
            @Override
            public void close() {}
        };
    }

    // ── File Creation (required for SFTP put operations) ──────────────

    /**
     * Creates an empty file at the given path. Apache MINA SSHD calls
     * {@code Files.newByteChannel(path, CREATE, WRITE)} for SFTP put operations.
     * The default FileSystemProvider.createFile() delegates to newByteChannel with
     * CREATE_NEW + WRITE, which our implementation already handles.
     *
     * <p>We override the behavior here NOT via @Override (createFile is not abstract
     * in FileSystemProvider — it's already implemented correctly), but by ensuring
     * newByteChannel handles all write open options properly (line 68-69: checks for
     * WRITE, CREATE, CREATE_NEW, APPEND).
     *
     * <p>The actual fix for "Operation unsupported": ensure newByteChannel correctly
     * handles the case when the file does not yet exist and CREATE/CREATE_NEW is set.
     */
    // Note: FileSystemProvider.createFile() already delegates to newByteChannel.
    // The real issue is in newByteChannel — VirtualWriteChannel must handle file creation.

    // ── Directory Creation ──────────────────────────────────────────────

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        try {
            vfs().mkdir(accountId(), pathString(dir));
        } catch (IllegalStateException e) {
            throw new FileAlreadyExistsException(pathString(dir));
        } catch (IllegalArgumentException e) {
            throw new NoSuchFileException(pathString(dir), null, "Parent directory does not exist");
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────

    @Override
    public void delete(Path path) throws IOException {
        String vpath = pathString(path);
        if ("/".equals(vpath)) throw new IOException("Cannot delete root directory");
        int deleted = vfs().delete(accountId(), vpath);
        if (deleted == 0) throw new NoSuchFileException(vpath);
    }

    // ── Copy / Move ─────────────────────────────────────────────────────

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        String srcPath = pathString(source);
        String dstPath = pathString(target);

        Optional<VirtualEntry> srcEntry = vfs().stat(accountId(), srcPath);
        if (srcEntry.isEmpty()) throw new NoSuchFileException(srcPath);

        boolean replaceExisting = Arrays.asList(options).contains(StandardCopyOption.REPLACE_EXISTING);
        if (!replaceExisting && vfs().exists(accountId(), dstPath)) {
            throw new FileAlreadyExistsException(dstPath);
        }

        VirtualEntry src = srcEntry.get();
        if (src.isFile()) {
            // Copy file: create new virtual entry pointing to same CAS object (dedup!)
            vfs().writeFile(accountId(), dstPath, src.getStorageKey(),
                    src.getSizeBytes(), src.getTrackId(), src.getContentType());
        } else {
            // Copy directory: create the dir (not recursive)
            vfs().mkdirs(accountId(), dstPath);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        try {
            vfs().move(accountId(), pathString(source), pathString(target));
        } catch (NoSuchElementException e) {
            throw new NoSuchFileException(pathString(source));
        } catch (IllegalStateException e) {
            throw new FileAlreadyExistsException(pathString(target));
        }
    }

    // ── Path Queries ────────────────────────────────────────────────────

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return pathString(path).equals(pathString(path2));
    }

    @Override
    public boolean isHidden(Path path) { return false; }

    @Override
    public FileStore getFileStore(Path path) {
        // Return a minimal FileStore — Apache MINA SSHD may query this during write ops.
        // "Operation unsupported" errors can originate from this throwing UnsupportedOperationException.
        return new FileStore() {
            @Override public String name() { return "vfs"; }
            @Override public String type() { return "virtual"; }
            @Override public boolean isReadOnly() { return false; }
            @Override public long getTotalSpace() { return Long.MAX_VALUE; }
            @Override public long getUsableSpace() { return Long.MAX_VALUE; }
            @Override public long getUnallocatedSpace() { return Long.MAX_VALUE; }
            @Override public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) { return false; }
            @Override public boolean supportsFileAttributeView(String name) { return false; }
            @Override public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) { return null; }
            @Override public Object getAttribute(String attribute) { return null; }
        };
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        String vpath = pathString(path);
        if ("/".equals(vpath)) return; // Root always exists
        if (!vfs().exists(accountId(), vpath)) {
            throw new NoSuchFileException(vpath);
        }
    }

    /**
     * Fixes SFTP {@code ls} on VFS-backed sessions.
     *
     * <p>Apache MINA SSHD 2.12's {@code DefaultSftpFileSystemAccessor.resolveLinkTarget}
     * unconditionally calls {@code Files.readSymbolicLink(entry)} during
     * directory iteration. The default {@link FileSystemProvider#readSymbolicLink}
     * throws {@link UnsupportedOperationException}, which the SFTP subsystem
     * converts to {@code SSH_FX_OP_UNSUPPORTED} — rendered by the OpenSSH
     * client as "Couldn't read directory: Operation unsupported".</p>
     *
     * <p>Throwing {@link NotLinkException} (an {@link IOException} subclass)
     * instead is caught by MINA as "this entry is simply not a symlink,"
     * and the listing proceeds normally. VFS has no symbolic-link concept,
     * so this is semantically correct — every entry is a regular file or
     * directory, never a link.</p>
     */
    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        throw new java.nio.file.NotLinkException(pathString(link),
                null, "virtual filesystem has no symbolic links");
    }

    // ── Attributes ──────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        // MINA SSHD's SFTP subsystem calls Files.getOwner(path) and
        // Files.getAttribute(path, "posix:...") while building SSH_FXP_NAME
        // responses. When getFileAttributeView returns null, Files.getOwner
        // throws UnsupportedOperationException → SSH_FX_OP_UNSUPPORTED →
        // "Couldn't read directory: Operation unsupported" on the client.
        // Return a minimal view for Basic / Posix / FileOwner that delegates
        // to readAttributes and accepts but discards mutations.
        if (type == BasicFileAttributeView.class
                || type == PosixFileAttributeView.class
                || type == FileOwnerAttributeView.class) {
            return (V) new VirtualFileAttributeView(path);
        }
        return null;
    }

    /**
     * Minimal read-only attribute view for VIRTUAL paths. Mutation methods
     * (setTimes, setOwner, setPermissions, etc.) are silently accepted and
     * discarded — VFS has no per-entry permission/ownership concept, so SFTP
     * chmod/chown from clients is a no-op rather than a hard error.
     */
    private class VirtualFileAttributeView implements PosixFileAttributeView {
        private final Path path;
        VirtualFileAttributeView(Path path) { this.path = path; }

        @Override public String name() { return "posix"; }

        @Override
        public VirtualSftpFileAttributes readAttributes() throws IOException {
            return (VirtualSftpFileAttributes) VirtualSftpFileSystemProvider.this
                    .readAttributes(path, BasicFileAttributes.class);
        }

        @Override public UserPrincipal getOwner() throws IOException { return readAttributes().owner(); }
        @Override public void setOwner(UserPrincipal owner) { /* no-op */ }
        @Override public void setPermissions(java.util.Set<PosixFilePermission> perms) { /* no-op */ }
        @Override public void setGroup(GroupPrincipal group) { /* no-op */ }
        @Override public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) { /* no-op */ }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        String vpath = pathString(path);

        // Root directory
        if ("/".equals(vpath)) {
            return (A) VirtualSftpFileAttributes.rootDirectory();
        }

        Optional<VirtualEntry> entry = vfs().stat(accountId(), vpath);
        if (entry.isEmpty()) {
            throw new NoSuchFileException(vpath);
        }

        return (A) new VirtualSftpFileAttributes(entry.get());
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        BasicFileAttributes attrs = readAttributes(path, BasicFileAttributes.class, options);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("size", attrs.size());
        map.put("isDirectory", attrs.isDirectory());
        map.put("isRegularFile", attrs.isRegularFile());
        map.put("isSymbolicLink", attrs.isSymbolicLink());
        map.put("isOther", attrs.isOther());
        map.put("lastModifiedTime", attrs.lastModifiedTime());
        map.put("lastAccessTime", attrs.lastAccessTime());
        map.put("creationTime", attrs.creationTime());
        map.put("fileKey", attrs.fileKey());
        // Best-effort POSIX defaults when the caller asks for "posix:*" — Apache
        // MINA SSHD's SFTP subsystem reads these via Files.readAttributes(p,
        // "posix:*") when building directory listing responses. Returning real
        // values here keeps SFTP readdir compatible with strict clients even
        // though we don't track per-file ownership.
        String view = attributes != null ? attributes.split(":", 2)[0] : "basic";
        if ("posix".equalsIgnoreCase(view) || "*".equals(view)) {
            map.put("owner", (java.nio.file.attribute.UserPrincipal) () -> "vfs");
            map.put("group", (java.nio.file.attribute.GroupPrincipal) () -> "vfs");
            map.put("permissions",
                    attrs.isDirectory()
                            ? java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
                            : java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        }
        return map;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        // Silently ignore attribute changes (permissions, timestamps)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Inner classes: SeekableByteChannel implementations
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Read channel: loads file bytes from CAS into memory for seeking.
     */
    private class VirtualReadChannel implements SeekableByteChannel {
        private final byte[] data;
        private int position = 0;
        private boolean open = true;

        VirtualReadChannel(String vpath) throws IOException {
            try {
                this.data = vfs().readFile(accountId(), vpath);
            } catch (NoSuchElementException e) {
                throw new NoSuchFileException(vpath);
            } catch (Exception e) {
                throw new IOException("Failed to read from CAS: " + vpath, e);
            }
        }

        @Override
        public int read(ByteBuffer dst) {
            if (position >= data.length) return -1;
            int toRead = Math.min(dst.remaining(), data.length - position);
            dst.put(data, position, toRead);
            position += toRead;
            return toRead;
        }

        @Override public int write(ByteBuffer src) { throw new NonWritableChannelException(); }
        @Override public long position() { return position; }

        @Override
        public SeekableByteChannel position(long newPosition) {
            this.position = (int) Math.min(newPosition, data.length);
            return this;
        }

        @Override public long size() { return data.length; }
        @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }

    /**
     * Write channel: spools to temp file, flushes to storage on close.
     *
     * <p>Uses a temp {@link FileChannel} instead of in-memory buffering so that
     * STANDARD (64KB–64MB) and CHUNKED (>64MB) files never occupy heap.
     * INLINE files (&lt;64KB) are read back into a small byte[] for the DB row.
     */
    private class VirtualWriteChannel implements SeekableByteChannel {
        private final String vpath;
        private final Path tempFile;
        private final FileChannel tempChannel;
        private boolean open = true;

        VirtualWriteChannel(String vpath) throws IOException {
            this.vpath = vpath;
            this.tempFile = Files.createTempFile("vfs-sftp-", ".tmp");
            this.tempChannel = FileChannel.open(tempFile,
                    StandardOpenOption.WRITE, StandardOpenOption.READ);
        }

        @Override public int read(ByteBuffer dst) { throw new NonReadableChannelException(); }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return tempChannel.write(src);
        }

        @Override public long position() throws IOException { return tempChannel.position(); }
        @Override public SeekableByteChannel position(long newPosition) throws IOException {
            tempChannel.position(newPosition);
            return this;
        }
        @Override public long size() throws IOException { return tempChannel.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            tempChannel.truncate(size);
            return this;
        }
        @Override public boolean isOpen() { return open; }

        @Override
        public void close() throws IOException {
            // R134I — log entry before any early-returns so we can see if
            // MINA actually calls close() at all. Tester R134G+R134H saw a
            // 17-byte SFTP upload succeed at the audit layer but neither the
            // callback log nor any other post-switch log fired — so either
            // close() never ran, or close() early-returned at fileSize==0.
            // Both cases now produce a log line.
            log.info("[vfs-channel] close() entered: vpath={} open={}", vpath, open);
            if (!open) return;
            open = false;

            try {
                long fileSize = tempChannel.size();
                log.info("[vfs-channel] close() tempFileSize={} for vpath={}", fileSize, vpath);
                if (fileSize == 0) {
                    log.warn("[vfs-channel] close() empty file — skipping VFS write for vpath={}", vpath);
                    return;
                }

                String filename = VirtualFileSystem.nameOf(vpath);
                String bucket = vfs().determineBucket(fileSize, accountId());

                String storedKey = null;
                switch (bucket) {
                    case "INLINE" -> {
                        // Small file — read to byte[] (< 64KB, safe in heap)
                        byte[] data = readTempBytes(fileSize);
                        vfs().writeFile(accountId(), vpath, null, fileSize, null, null, data);
                        log.info("[VFS] Inline stored {}: {} bytes", vpath, fileSize);
                    }
                    case "CHUNKED" -> {
                        // Stream 4MB chunks from temp file to CAS
                        storeChunked(filename, fileSize);
                    }
                    default -> {
                        // STANDARD: stream temp file to Storage Manager — zero heap copy
                        tempChannel.close();
                        Map<String, Object> result = fileSystem.getStorageClient()
                                .store(tempFile, null, accountId().toString());
                        String sha256 = (String) result.get("sha256");
                        String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;
                        log.info("[VFS] CAS stored {}: {} ({} bytes)",
                                vpath, sha256 != null ? sha256.substring(0, 8) + "..." : "n/a", fileSize);
                        vfs().writeFile(accountId(), vpath, sha256, fileSize, trackId, null, null);
                        storedKey = sha256;
                    }
                }

                // VFS entry created — notify SFTP server to trigger file routing.
                //
                // R134G — tester R134C-E-runtime-verification.md saw an SFTP upload
                // succeed at the listener level with zero flow_executions row. Root
                // cause candidate: SftpFileSystemFactory creates TWO VirtualSftpFileSystem
                // instances per session — getUserHomeDir() uses the 3-arg constructor
                // (no callback) and createFileSystem() uses the 4-arg (with callback).
                // If MINA SSHD resolves the write path through a Path owned by the
                // home-dir FS, `getWriteCallback()` returns null and routing silently
                // never fires. Log loudly so the next tester run either confirms this
                // branch is the culprit OR rules it out.
                VfsWriteCallback cb = fileSystem.getWriteCallback();
                if (cb != null) {
                    log.info("[VFS] Invoking write callback for vpath={} size={} storedKey={}",
                            vpath, fileSize,
                            storedKey != null ? storedKey.substring(0, Math.min(8, storedKey.length())) + "..." : "n/a");
                    try {
                        cb.onFileWritten(vpath, fileSize, storedKey);
                    } catch (Exception cbErr) {
                        log.error("[VFS] Write completion callback failed for {}: {}", vpath, cbErr.getMessage());
                    }
                } else {
                    log.warn("[VFS] Write callback is NULL — routing NOT triggered for vpath={} size={}. "
                            + "Likely cause: SFTP session's path is bound to a VirtualSftpFileSystem "
                            + "instance that was created WITHOUT a callback (getUserHomeDir branch in "
                            + "SftpFileSystemFactory). File bytes are stored but flow_executions row will NOT be created.",
                            vpath, fileSize);
                }
            } catch (Exception e) {
                throw new IOException("Failed to store file to CAS: " + vpath, e);
            } finally {
                try { tempChannel.close(); } catch (Exception ignored) {}
                Files.deleteIfExists(tempFile);
            }
        }

        private byte[] readTempBytes(long fileSize) throws IOException {
            tempChannel.position(0);
            byte[] data = new byte[(int) fileSize];
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) tempChannel.read(buf);
            return data;
        }

        private void storeChunked(String filename, long fileSize) throws IOException {
            int chunkSize = 4 * 1024 * 1024; // 4MB chunks

            // 1. Create the VFS entry as a CHUNKED manifest (WAIP-protected)
            VirtualEntry entry = vfs().writeFile(accountId(), vpath, null, fileSize, null, null, null);

            // 2. Stream each chunk from temp file → Storage Manager → register in VFS
            int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
            tempChannel.position(0);

            for (int i = 0; i < totalChunks; i++) {
                int length = (int) Math.min(chunkSize, fileSize - (long) i * chunkSize);
                byte[] chunk = new byte[length];
                ByteBuffer buf = ByteBuffer.wrap(chunk);
                while (buf.hasRemaining()) tempChannel.read(buf);

                String chunkName = filename + ".chunk." + i;
                Map<String, Object> result = fileSystem.getStorageClient()
                        .store(chunkName, chunk, null, accountId().toString());

                String sha256 = (String) result.get("sha256");
                vfs().registerChunk(entry.getId(), i, sha256 != null ? sha256 : "",
                        length, sha256 != null ? sha256 : "");

                log.debug("[VFS] Chunk {}/{} onboarded for {}: {}", i + 1, totalChunks, vpath,
                        sha256 != null ? sha256.substring(0, 8) + "..." : "n/a");
            }
            log.info("[VFS] Chunked onboarded {}: {} bytes in {} chunks", vpath, fileSize, totalChunks);
        }
    }
}
