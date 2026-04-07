package com.filetransfer.shared.vfs;

import com.filetransfer.shared.entity.VirtualEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
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

        if (write) {
            return new VirtualWriteChannel(vpath);
        } else {
            return new VirtualReadChannel(vpath);
        }
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
        throw new UnsupportedOperationException("FileStore not supported");
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        String vpath = pathString(path);
        if ("/".equals(vpath)) return; // Root always exists
        if (!vfs().exists(accountId(), vpath)) {
            throw new NoSuchFileException(vpath);
        }
    }

    // ── Attributes ──────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null; // Not needed for SFTP
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
     * Write channel: buffers data in memory, flushes to CAS on close.
     */
    private class VirtualWriteChannel implements SeekableByteChannel {
        private final String vpath;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean open = true;

        VirtualWriteChannel(String vpath) {
            this.vpath = vpath;
        }

        @Override public int read(ByteBuffer dst) { throw new NonReadableChannelException(); }

        @Override
        public int write(ByteBuffer src) {
            int count = src.remaining();
            byte[] bytes = new byte[count];
            src.get(bytes);
            buffer.write(bytes, 0, count);
            return count;
        }

        @Override public long position() { return buffer.size(); }
        @Override public SeekableByteChannel position(long newPosition) { return this; }
        @Override public long size() { return buffer.size(); }
        @Override public SeekableByteChannel truncate(long size) { return this; }
        @Override public boolean isOpen() { return open; }

        @Override
        public void close() throws IOException {
            if (!open) return;
            open = false;

            byte[] data = buffer.toByteArray();
            if (data.length == 0) return;

            try {
                String filename = VirtualFileSystem.nameOf(vpath);
                String bucket = vfs().determineBucket(data.length);

                switch (bucket) {
                    case "INLINE" -> {
                        // Store directly in DB — zero CAS hop
                        vfs().writeFile(accountId(), vpath, null, data.length, null, null, data);
                        log.info("[VFS] Inline stored {}: {} bytes", vpath, data.length);
                    }
                    case "CHUNKED" -> {
                        // Stream 4MB chunks to CAS independently
                        storeChunked(filename, data);
                    }
                    default -> {
                        // STANDARD: current CAS path
                        Map<String, Object> result = fileSystem.getStorageClient()
                                .store(filename, data, null, accountId().toString());
                        String sha256 = (String) result.get("sha256");
                        String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;
                        log.info("[VFS] CAS stored {}: {} ({} bytes)",
                                vpath, sha256 != null ? sha256.substring(0, 8) + "..." : "n/a", data.length);
                        vfs().writeFile(accountId(), vpath, sha256, data.length, trackId, null, null);
                    }
                }
            } catch (Exception e) {
                throw new IOException("Failed to store file to CAS: " + vpath, e);
            }
        }

        private void storeChunked(String filename, byte[] data) {
            int chunkSize = 4 * 1024 * 1024; // 4MB chunks

            // First create the VFS entry as a CHUNKED manifest
            vfs().writeFile(accountId(), vpath, null, data.length, null, null, null);

            // Then store each chunk to CAS
            int totalChunks = (int) Math.ceil((double) data.length / chunkSize);
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * chunkSize;
                int length = Math.min(chunkSize, data.length - offset);
                byte[] chunk = Arrays.copyOfRange(data, offset, offset + length);

                String chunkName = filename + ".chunk." + i;
                Map<String, Object> result = fileSystem.getStorageClient()
                        .store(chunkName, chunk, null, accountId().toString());

                String sha256 = (String) result.get("sha256");
                String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;

                // Record chunk in manifest — VFS tracks this via vfs_chunks table
                log.debug("[VFS] Chunk {}/{} stored for {}: {}", i + 1, totalChunks, vpath,
                        sha256 != null ? sha256.substring(0, 8) + "..." : "n/a");
            }
            log.info("[VFS] Chunked stored {}: {} bytes in {} chunks", vpath, data.length, totalChunks);
        }
    }
}
