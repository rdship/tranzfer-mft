package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VirtualEntry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Map;
import java.util.UUID;

/**
 * Bridge between the VFS catalog and the FileFlow pipeline engine.
 *
 * <p>Provides two operations used by {@link com.filetransfer.shared.routing.FlowProcessingEngine}:
 * <ol>
 *   <li><b>openInputStream</b> — returns an {@link InputStream} for any {@link FileRef}:
 *       <ul>
 *         <li>INLINE: content from DB row — zero network, zero disk.
 *         <li>STANDARD: single {@code byte[]} fetch from storage-manager, wrapped as stream.
 *         <li>CHUNKED: parallel chunk fetch via {@link VirtualFileSystem#readFile}, wrapped as stream.
 *       </ul>
 *   <li><b>storeAndRegister</b> — stores an {@link InputStream} to storage-manager and creates
 *       a VFS entry pointing at the new CAS object. Used by transform steps (compress, decompress).
 * </ol>
 *
 * <p><b>registerRef</b> is a lighter variant for steps where another service does the actual
 * storage (e.g. encryption-service). The flow engine just needs to create the VFS catalog entry
 * for the already-stored CAS object.
 *
 * <p><b>linkToAccount</b> enables zero-copy cross-account delivery: a new VFS row is created
 * in the destination account pointing to the same {@code storageKey}. No bytes are copied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VfsFlowBridge {

    @Getter
    private final VirtualFileSystem vfs;
    private final StorageServiceClient storageClient;

    // ── Factory ─────────────────────────────────────────────────────────────

    /**
     * Build a {@link FileRef} from a live {@link VirtualEntry}.
     * Called by protocol listeners (SFTP/FTP/AS2) when an upload completes.
     */
    public static FileRef fromEntry(VirtualEntry entry) {
        return new FileRef(
                entry.getStorageKey(),
                entry.getPath(),
                entry.getAccountId(),
                entry.getSizeBytes(),
                entry.getTrackId(),
                entry.getContentType(),
                entry.getStorageBucket() != null ? entry.getStorageBucket() : "STANDARD");
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Open an {@link InputStream} for the given {@link FileRef}.
     *
     * <ul>
     *   <li>INLINE: bytes from the VFS DB row — zero network I/O.
     *   <li>STANDARD / CHUNKED: retrieved from storage-manager, wrapped as
     *       {@link ByteArrayInputStream}. One HTTP call, no local disk writes.
     * </ul>
     *
     * @throws IOException if the content cannot be retrieved
     */
    public InputStream openInputStream(FileRef ref) throws IOException {
        if (ref.isInline()) {
            // Hot path: content already in DB row — no network call
            byte[] content = vfs.readFile(ref.accountId(), ref.virtualPath());
            return new ByteArrayInputStream(content);
        }
        // STANDARD or CHUNKED: one fetch from storage-manager
        byte[] data = storageClient.retrieveBySha256(ref.storageKey());
        if (data == null) {
            throw new IOException("File not found in storage-manager: storageKey="
                    + abbrev(ref.storageKey()) + " path=" + ref.virtualPath());
        }
        return new ByteArrayInputStream(data);
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Stream {@code data} to storage-manager and register a VFS entry at {@code newVirtualPath}.
     *
     * <p>Used by local-transform steps (compress, decompress) that produce a new byte stream.
     * The storage-manager call is streaming — no intermediate temp file or heap buffer is created
     * beyond the pipe buffer that feeds this method.
     *
     * @param data            transformed byte stream to store
     * @param estimatedSize   byte count hint for HTTP {@code Content-Length}; pass {@code -1} if unknown
     * @param origin          source FileRef (provides accountId, contentType)
     * @param newVirtualPath  VFS path for the output entry
     * @param trackId         platform track ID
     * @param contentType     MIME type for the output, or {@code null} to inherit from origin
     * @return FileRef pointing to the newly stored CAS object
     * @throws IOException if storage fails
     */
    public FileRef storeAndRegister(InputStream data, long estimatedSize,
                                    FileRef origin, String newVirtualPath,
                                    String trackId, String contentType) throws IOException {
        String filename = VirtualFileSystem.nameOf(newVirtualPath);
        String effectiveCt = contentType != null ? contentType : origin.contentType();

        Map<String, Object> result = storageClient.storeStream(
                data, estimatedSize, filename, origin.accountId().toString(), trackId);

        if (result == null) {
            throw new IOException("storage-manager returned null storing to: " + newVirtualPath);
        }

        String newKey  = (String) result.get("sha256");
        long   newSize = result.get("sizeBytes") instanceof Number n ? n.longValue() : estimatedSize;

        vfs.writeFile(origin.accountId(), newVirtualPath, newKey, newSize, trackId, effectiveCt);

        log.debug("[{}] stored {} bytes → key={}… path={}",
                trackId, newSize, abbrev(newKey), newVirtualPath);

        return new FileRef(newKey, newVirtualPath, origin.accountId(), newSize,
                trackId, effectiveCt, "STANDARD");
    }

    /**
     * Register an existing CAS object as a VFS entry — zero storage I/O.
     *
     * <p>Used after a remote service (e.g. encryption-service) has already stored the result
     * in storage-manager and returned the new {@code storageKey}. The flow engine just needs to
     * create the VFS catalog entry.
     */
    public FileRef registerRef(UUID accountId, String virtualPath, String storageKey,
                               long sizeBytes, String trackId, String contentType) {
        vfs.writeFile(accountId, virtualPath, storageKey, sizeBytes, trackId, contentType);
        return new FileRef(storageKey, virtualPath, accountId, sizeBytes,
                trackId, contentType, "STANDARD");
    }

    /**
     * Zero-copy cross-account link.
     *
     * <p>Creates a new VFS entry in {@code destAccountId} at {@code destVirtualPath} pointing
     * to the same {@code storageKey} as {@code source}. No bytes are copied — only a DB row
     * is inserted. Reference counting in storage-manager ensures the CAS object is retained
     * until all VFS entries referencing it are deleted.
     */
    public FileRef linkToAccount(FileRef source, UUID destAccountId,
                                 String destVirtualPath, String trackId) {
        vfs.writeFile(destAccountId, destVirtualPath,
                source.storageKey(), source.sizeBytes(), trackId, source.contentType());
        log.debug("[{}] zero-copy link: {} → account={} path={}",
                trackId, abbrev(source.storageKey()), destAccountId, destVirtualPath);
        return new FileRef(source.storageKey(), destVirtualPath, destAccountId,
                source.sizeBytes(), trackId, source.contentType(), source.storageBucket());
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    private static String abbrev(String key) {
        if (key == null) return "null";
        return key.length() > 8 ? key.substring(0, 8) + "…" : key;
    }
}
