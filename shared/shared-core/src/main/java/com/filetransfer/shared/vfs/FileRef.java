package com.filetransfer.shared.vfs;

import java.util.UUID;

/**
 * Universal file reference for VFS-mode FileFlow processing.
 *
 * <p>Replaces local filesystem paths as the unit of exchange between flow pipeline steps.
 * The flow engine passes {@code FileRef}s between steps — it never holds raw file bytes.
 * Each step reads from and writes back to storage-manager independently using the
 * {@code storageKey} (SHA-256 content-addressable key).
 *
 * <h3>Storage buckets</h3>
 * <ul>
 *   <li><b>INLINE</b> (&lt;64 KB): content is in the {@code VirtualEntry} DB row — zero network hop.
 *   <li><b>STANDARD</b> (64 KB–64 MB): single CAS object in storage-manager.
 *   <li><b>CHUNKED</b> (&gt;64 MB): multiple 4 MB CAS objects; reassembled on read.
 * </ul>
 *
 * <h3>Zero-copy routing</h3>
 * Cross-account delivery creates a new {@code VirtualEntry} row pointing to the same
 * {@code storageKey}. No bytes are copied — only a DB row is added. Deduplication is automatic.
 */
public record FileRef(
        /** SHA-256 CAS key — primary reference to content in storage-manager. */
        String storageKey,

        /** Logical VFS path scoped to {@code accountId} (e.g. {@code /inbox/report.csv.enc}). */
        String virtualPath,

        /** Owning account UUID. */
        UUID accountId,

        /** File size in bytes (may be -1 if unknown prior to storage). */
        long sizeBytes,

        /** Platform track ID tied to this transfer leg. */
        String trackId,

        /** MIME content type, or null if unknown. */
        String contentType,

        /** Storage bucket: INLINE | STANDARD | CHUNKED. */
        String storageBucket
) {
    public boolean isInline()  { return "INLINE".equals(storageBucket); }
    public boolean isChunked() { return "CHUNKED".equals(storageBucket); }

    /** Derive a sibling FileRef with a different virtual path and storage key (post-step). */
    public FileRef withStep(String newVirtualPath, String newStorageKey, long newSize) {
        return new FileRef(newStorageKey, newVirtualPath, accountId, newSize,
                trackId, contentType, "STANDARD");
    }
}
