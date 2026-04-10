package com.filetransfer.storage.backend;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction over the physical byte store for the storage-manager.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link LocalStorageBackend} — default, writes to the local filesystem
 *       ({@code storage.hot.path}). Correct for single-instance and NFS-mounted deployments.
 *       Activated when {@code storage.backend=local} (or property absent).
 *
 *   <li>{@link S3StorageBackend} — stores every file in an S3-compatible bucket (MinIO
 *       or AWS S3). All storage-manager replicas share the same bucket, closing all
 *       multi-replica data-location gaps. Activated when {@code storage.backend=s3}.
 *       Outbound S3 calls to the public internet are automatically routed through the
 *       platform's DMZ proxy ({@code PROXY_ENABLED/PROXY_HOST/PROXY_PORT}).
 *       Calls to internal MinIO ({@code PROXY_NO_PROXY_HOSTS}) bypass the proxy.
 * </ul>
 *
 * <p>Switching backends requires only setting {@code storage.backend} — no code changes.
 */
public interface StorageBackend {

    /**
     * Write bytes to the backend.
     *
     * @param data      content stream (caller is responsible for closing)
     * @param sizeBytes content length in bytes (required for efficient S3 uploads)
     * @param filename  hint for logging/metadata (not used as storage key)
     * @return result containing SHA-256 key, size, throughput, and dedup status
     */
    WriteResult write(InputStream data, long sizeBytes, String filename) throws Exception;

    /**
     * Read bytes by content-addressed key (SHA-256).
     *
     * @param storageKey SHA-256 hex string, or for backward-compat, a legacy absolute path
     * @deprecated Use {@link #readTo(String, OutputStream)} or {@link #readStream(String)}
     *             to avoid loading the entire file into JVM heap.
     */
    @Deprecated
    ReadResult read(String storageKey) throws Exception;

    /**
     * Stream file content directly to the given {@link OutputStream} without
     * buffering the entire payload in JVM heap.
     *
     * <p>Implementations should use zero-copy or chunked-pipe strategies:
     * <ul>
     *   <li>Local: {@code FileChannel.transferTo()} (kernel-level zero-copy)
     *   <li>S3: 256 KB chunked pipe from the S3 response stream
     * </ul>
     *
     * <p>The default implementation delegates to the deprecated {@link #read(String)}
     * for backward compatibility with custom backends that have not yet migrated.
     *
     * @param storageKey SHA-256 hex string, or for backward-compat, a legacy absolute path
     * @param target     destination stream (e.g. {@code HttpServletResponse.getOutputStream()})
     */
    default void readTo(String storageKey, OutputStream target) throws Exception {
        ReadResult r = read(storageKey);
        target.write(r.data());
    }

    /**
     * Open a streaming {@link InputStream} for the given storage key.
     *
     * <p><b>Caller is responsible for closing the returned stream.</b> Unlike
     * {@link #read(String)}, this never loads the full file into heap — the bytes
     * flow from the physical store on demand.
     *
     * @param storageKey SHA-256 hex string, or for backward-compat, a legacy absolute path
     * @return an open input stream positioned at the start of the file
     */
    default InputStream readStream(String storageKey) throws Exception {
        ReadResult r = read(storageKey);
        return new ByteArrayInputStream(r.data());
    }

    /**
     * Check whether the given SHA-256 key exists in the backend.
     * Used for deduplication and WAIP intent recovery.
     */
    boolean exists(String storageKey);

    /**
     * Soft-delete or permanently remove the given key from the backend.
     * For S3, this removes the object; for local, it deletes the file.
     */
    void delete(String storageKey);

    /** Human-readable backend type for logging and the health endpoint. */
    String type();

    // ── Result records ────────────────────────────────────────────────────────

    record WriteResult(
            /** SHA-256 hex — the canonical storage key for subsequent reads. */
            String storageKey,
            long   sizeBytes,
            String sha256,
            long   durationMs,
            double throughputMbps,
            /** True if content already existed (dedup hit) — no bytes were written. */
            boolean deduplicated
    ) {}

    record ReadResult(
            byte[] data,
            long   sizeBytes,
            String sha256,
            String contentType
    ) {}
}
