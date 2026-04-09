package com.filetransfer.storage.backend;

import java.io.InputStream;

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
     */
    ReadResult read(String storageKey) throws Exception;

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
