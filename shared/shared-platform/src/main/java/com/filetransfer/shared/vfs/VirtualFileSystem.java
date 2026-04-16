package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.entity.vfs.VfsChunk;
import com.filetransfer.shared.entity.vfs.VfsIntent;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.repository.vfs.VfsChunkRepository;
import com.filetransfer.shared.repository.vfs.VfsIntentRepository;
import com.filetransfer.shared.repository.vfs.VirtualEntryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Core Virtual Filesystem service with Write-Ahead Intent Protocol (WAIP).
 *
 * <p>All folder/file operations go through this service. Directories are zero-cost DB rows.
 * File content is routed to one of three storage buckets based on size:
 * <ul>
 *   <li><b>INLINE</b> ({@literal <} 64 KB): content stored directly in the DB row — zero CAS hop</li>
 *   <li><b>STANDARD</b> (64 KB – 64 MB): content-addressed storage via Storage Manager</li>
 *   <li><b>CHUNKED</b> ({@literal >} 64 MB): 4 MB chunks streamed to CAS independently</li>
 * </ul>
 *
 * <p>Mutable operations (write, delete, move) are protected by:
 * <ul>
 *   <li>PostgreSQL advisory locks — path-level serialization, zero disk I/O</li>
 *   <li>Write-ahead intents — crash recovery via {@code VfsIntentRecoveryJob}</li>
 *   <li>Optimistic locking (@Version) — concurrent update detection</li>
 * </ul>
 *
 * <p>Read operations (list, stat, exists, readFile) have ZERO WAIP overhead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualFileSystem {

    private final VirtualEntryRepository entryRepository;
    private final StorageServiceClient storageClient;
    private final VfsIntentRepository intentRepository;
    private final VfsChunkRepository chunkRepository;
    private final EntityManager entityManager;
    private final TransferAccountRepository accountRepository;

    /**
     * Optional distributed lock — activates when Redis is available.
     * Falls back to pg_advisory_xact_lock for single-instance deployments.
     */
    @Autowired(required = false)
    @org.springframework.lang.Nullable
    private DistributedVfsLock distributedLock;

    @Value("${vfs.inline-max-bytes:65536}")
    private long inlineMaxBytes;

    @Value("${vfs.chunk-threshold-bytes:67108864}")
    private long chunkThresholdBytes;

    private String podId;

    @PostConstruct
    void initPodId() {
        this.podId = System.getenv().getOrDefault("HOSTNAME",
                "unknown-" + ProcessHandle.current().pid());
        log.info("VFS initialized on pod {}, inline≤{}B, chunk>{}B",
                podId, inlineMaxBytes, chunkThresholdBytes);
    }

    /** Inline content above this threshold is gzip-compressed before DB storage. */
    private static final int INLINE_COMPRESS_THRESHOLD = 4096; // 4 KB

    // ── Inline Compression Helpers ────────────────────────────────────

    /**
     * Gzip-compress raw bytes. Returns compressed byte array.
     */
    static byte[] gzipCompress(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
            try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(data);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip-compress inline content", e);
        }
    }

    /**
     * Gzip-decompress bytes. Returns original uncompressed byte array.
     */
    static byte[] gzipDecompress(byte[] compressed) {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gis.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip-decompress inline content", e);
        }
    }

    // ── Storage Bucket Routing ─────────────────────────────────────────

    public String determineBucket(long sizeBytes) {
        if (sizeBytes <= inlineMaxBytes) return "INLINE";
        if (sizeBytes > chunkThresholdBytes) return "CHUNKED";
        return "STANDARD";
    }

    /**
     * Account-aware bucket routing. Uses per-account threshold overrides if configured,
     * otherwise falls back to system defaults ({@code vfs.inline-max-bytes} / {@code vfs.chunk-threshold-bytes}).
     *
     * @param sizeBytes file size in bytes
     * @param accountId account to check for threshold overrides (null = use system defaults)
     */
    public String determineBucket(long sizeBytes, UUID accountId) {
        if (accountId == null) {
            return determineBucket(sizeBytes);
        }

        long effectiveInline = inlineMaxBytes;
        long effectiveChunk = chunkThresholdBytes;

        TransferAccount account = accountRepository.findById(accountId).orElse(null);
        if (account != null) {
            if (account.getInlineMaxBytes() != null) {
                effectiveInline = account.getInlineMaxBytes();
            }
            if (account.getChunkThresholdBytes() != null) {
                effectiveChunk = account.getChunkThresholdBytes();
            }
        }

        if (sizeBytes <= effectiveInline) return "INLINE";
        if (sizeBytes > effectiveChunk) return "CHUNKED";
        return "STANDARD";
    }

    // ── Directory Operations ───────────────────────────────────────────

    /**
     * Create a single directory. Parent must exist.
     */
    @Transactional
    public VirtualEntry mkdir(UUID accountId, String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("Cannot create root directory");
        }

        if (entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalized)) {
            throw new IllegalStateException("Path already exists: " + normalized);
        }

        String parentPath = parentOf(normalized);
        if (!"/".equals(parentPath) && !entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, parentPath)) {
            throw new IllegalArgumentException("Parent directory does not exist: " + parentPath);
        }

        return entryRepository.save(VirtualEntry.builder()
                .accountId(accountId)
                .path(normalized)
                .parentPath(parentPath)
                .name(nameOf(normalized))
                .type(VirtualEntry.EntryType.DIR)
                .build());
    }

    /**
     * Create a directory and all missing ancestors (like Files.createDirectories).
     */
    @Transactional
    public VirtualEntry mkdirs(UUID accountId, String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) return null;

        Optional<VirtualEntry> existing = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (existing.isPresent()) {
            if (existing.get().isDirectory()) return existing.get();
            throw new IllegalStateException("Path exists as file: " + normalized);
        }

        String[] parts = normalized.substring(1).split("/");
        StringBuilder current = new StringBuilder();
        VirtualEntry last = null;
        for (String part : parts) {
            current.append("/").append(part);
            String currentPath = current.toString();
            if (!entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, currentPath)) {
                last = entryRepository.save(VirtualEntry.builder()
                        .accountId(accountId)
                        .path(currentPath)
                        .parentPath(parentOf(currentPath))
                        .name(part)
                        .type(VirtualEntry.EntryType.DIR)
                        .build());
            }
        }
        return last;
    }

    // ── Listing ────────────────────────────────────────────────────────

    public List<VirtualEntry> list(UUID accountId, String directoryPath) {
        String normalized = normalizePath(directoryPath);
        return entryRepository.findByAccountIdAndParentPathAndDeletedFalse(accountId, normalized);
    }

    // ── File Write (WAIP-protected, bucket-aware) ──────────────────────

    /**
     * Create or overwrite a file entry with WAIP protection.
     *
     * <p>For INLINE bucket: pass content in {@code inlineContent}, storageKey may be null.
     * <p>For STANDARD bucket: pass storageKey from CAS, inlineContent may be null.
     * <p>For CHUNKED bucket: entry is created as a manifest; chunks tracked via vfs_chunks.
     */
    @Transactional
    public VirtualEntry writeFile(UUID accountId, String path, String storageKey,
                                   long sizeBytes, String trackId, String contentType,
                                   byte[] inlineContent) {
        String normalized = normalizePath(path);
        String parentPath = parentOf(normalized);
        String bucket = determineBucket(sizeBytes, accountId);

        // 1. Distributed lock: serialize concurrent writes to same (account, path) across ALL pods.
        //    Released in the finally block below — works for Redis (distributed) and pg_advisory (local).
        DistributedVfsLock.LockHandle lock = lockPath(accountId, normalized);

        // 2. Record intent BEFORE mutation
        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.WRITE, normalized,
                null, storageKey, trackId, sizeBytes, contentType);

        // 3. Auto-create parent directories
        if (!"/".equals(parentPath)) {
            mkdirs(accountId, parentPath);
        }

        // 4. Compress inline content if above threshold
        byte[] storedContent = inlineContent;
        boolean compressed = false;
        if ("INLINE".equals(bucket) && inlineContent != null
                && inlineContent.length > INLINE_COMPRESS_THRESHOLD) {
            storedContent = gzipCompress(inlineContent);
            compressed = true;
            log.debug("Inline content compressed: {} → {} bytes ({}% reduction)",
                    inlineContent.length, storedContent.length,
                    100 - (storedContent.length * 100 / inlineContent.length));
        }

        // 5. Create or update entry (now under advisory lock — no race)
        Optional<VirtualEntry> existing = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        VirtualEntry result;
        if (existing.isPresent()) {
            VirtualEntry entry = existing.get();
            if (entry.isDirectory()) {
                throw new IllegalStateException("Cannot write file: path is a directory: " + normalized);
            }
            entry.setStorageKey(storageKey);
            entry.setSizeBytes(sizeBytes);
            entry.setTrackId(trackId);
            entry.setContentType(contentType);
            entry.setStorageBucket(bucket);
            if ("INLINE".equals(bucket)) {
                entry.setInlineContent(storedContent);
                entry.setCompressed(compressed);
            } else {
                entry.setInlineContent(null);
                entry.setCompressed(false);
            }
            result = entryRepository.save(entry);
        } else {
            result = entryRepository.save(VirtualEntry.builder()
                    .accountId(accountId)
                    .path(normalized)
                    .parentPath(parentPath)
                    .name(nameOf(normalized))
                    .type(VirtualEntry.EntryType.FILE)
                    .storageKey(storageKey)
                    .sizeBytes(sizeBytes)
                    .trackId(trackId)
                    .contentType(contentType)
                    .storageBucket(bucket)
                    .inlineContent("INLINE".equals(bucket) ? storedContent : null)
                    .compressed(compressed)
                    .build());
        }

        // 6. Mark intent COMMITTED (same transaction — single DB commit)
        try {
            commitIntent(intent);
            return result;
        } finally {
            lock.close(); // Releases Redis SETNX key (no-op for pg_advisory — auto-released on tx commit)
        }
    }

    /**
     * Backward-compatible overload — delegates to bucket-aware writeFile.
     */
    @Transactional
    public VirtualEntry writeFile(UUID accountId, String path, String storageKey,
                                   long sizeBytes, String trackId, String contentType) {
        return writeFile(accountId, path, storageKey, sizeBytes, trackId, contentType, null);
    }

    // ── File Read (bucket-aware, ZERO WAIP overhead) ───────────────────

    /**
     * Get file content bytes. Routes to the correct storage bucket:
     * <ul>
     *   <li>INLINE: returns content directly from DB row (zero network)</li>
     *   <li>STANDARD: retrieves from CAS via Storage Manager</li>
     *   <li>CHUNKED: reassembles from chunk manifests</li>
     * </ul>
     */
    @Transactional
    public byte[] readFile(UUID accountId, String path) {
        String normalized = normalizePath(path);
        VirtualEntry entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized)
                .orElseThrow(() -> new NoSuchElementException("File not found: " + normalized));

        if (entry.isDirectory()) {
            throw new IllegalStateException("Cannot read directory as file: " + normalized);
        }

        // Update access counters
        entry.setAccessCount(entry.getAccessCount() + 1);
        entry.setLastAccessedAt(Instant.now());
        entryRepository.save(entry);

        String bucket = entry.getStorageBucket() != null ? entry.getStorageBucket() : "STANDARD";

        return switch (bucket) {
            case "INLINE" -> {
                if (entry.getInlineContent() != null) {
                    yield entry.isCompressed()
                            ? gzipDecompress(entry.getInlineContent())
                            : entry.getInlineContent();
                }
                throw new NoSuchElementException("Inline content missing for: " + normalized);
            }
            case "CHUNKED" -> readChunked(entry);
            default -> {
                // STANDARD path — retrieve from CAS by SHA-256 key
                if (entry.getStorageKey() != null) {
                    yield storageClient.retrieveBySha256(entry.getStorageKey());
                }
                throw new NoSuchElementException("File has no storage key: " + normalized);
            }
        };
    }

    /**
     * Read only the first {@code maxBytes} of a file — for header detection (EDI, magic bytes).
     * INLINE: slices the inline content. STANDARD: streams from CAS with early close.
     * Never loads the full file into heap.
     */
    public byte[] readHeader(UUID accountId, String path, int maxBytes) {
        String normalized = normalizePath(path);
        VirtualEntry entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized)
                .orElse(null);
        if (entry == null || entry.isDirectory()) return null;

        String bucket = entry.getStorageBucket() != null ? entry.getStorageBucket() : "STANDARD";
        try {
            return switch (bucket) {
                case "INLINE" -> {
                    byte[] full = entry.getInlineContent();
                    if (full == null) yield null;
                    if (entry.isCompressed()) full = gzipDecompress(full);
                    yield full.length <= maxBytes ? full : java.util.Arrays.copyOf(full, maxBytes);
                }
                default -> {
                    // STANDARD/CHUNKED: stream from CAS, read only maxBytes
                    if (entry.getStorageKey() != null && storageClient != null) {
                        yield storageClient.retrieveHeader(entry.getStorageKey(), maxBytes);
                    }
                    yield null;
                }
            };
        } catch (Exception e) {
            return null; // Best-effort header detection — never block routing
        }
    }

    private byte[] readChunked(VirtualEntry entry) {
        List<VfsChunk> chunks = chunkRepository.findByEntryIdOrderByChunkIndex(entry.getId());
        if (chunks.isEmpty()) {
            throw new NoSuchElementException("No chunks found for: " + entry.getPath());
        }

        // Single chunk — skip executor overhead
        if (chunks.size() == 1) {
            return storageClient.retrieve(chunks.getFirst().getStorageKey());
        }

        // Parallel retrieval using virtual threads — each chunk fetch is I/O-bound,
        // so virtual threads are ideal (no platform thread pinning on HTTP calls)
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Launch all chunk fetches concurrently, preserving index order in the futures list
            List<CompletableFuture<byte[]>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(
                            () -> storageClient.retrieve(chunk.getStorageKey()), executor))
                    .toList();

            // Wait for all to complete — if any chunk fails, propagate immediately
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // Reassemble in chunk-index order (futures list matches chunks list order)
            ByteArrayOutputStream assembled = new ByteArrayOutputStream((int) entry.getSizeBytes());
            for (int i = 0; i < futures.size(); i++) {
                byte[] chunkData = futures.get(i).join();
                if (chunkData == null) {
                    throw new NoSuchElementException(
                            "Chunk " + i + " returned null for: " + entry.getPath());
                }
                assembled.writeBytes(chunkData);
            }
            return assembled.toByteArray();
        } catch (java.util.concurrent.CompletionException e) {
            // Unwrap the real cause for clear error reporting
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Failed to read chunked file " + entry.getPath()
                    + " (" + chunks.size() + " chunks): " + cause.getMessage(), cause);
        }
    }

    // ── Chunk Registration (for CHUNKED bucket) ──────────────────────────

    /**
     * Register a chunk that was onboarded to Storage Manager.
     * Called by protocol handlers (SFTP/FTP/FTP-Web) after each chunk is stored in CAS.
     *
     * <p>Follows "never create, always onboard": content lives in Storage Manager,
     * VFS only tracks the reference in the chunk manifest.
     */
    @Transactional
    public VfsChunk registerChunk(UUID entryId, int chunkIndex, String storageKey,
                                   long sizeBytes, String sha256) {
        return chunkRepository.save(VfsChunk.builder()
                .entryId(entryId)
                .chunkIndex(chunkIndex)
                .storageKey(storageKey)
                .sizeBytes(sizeBytes)
                .sha256(sha256)
                .status(VfsChunk.ChunkStatus.STORED)
                .build());
    }

    // ── Stat ───────────────────────────────────────────────────────────

    public Optional<VirtualEntry> stat(UUID accountId, String path) {
        return entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalizePath(path));
    }

    public boolean exists(UUID accountId, String path) {
        return entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizePath(path));
    }

    // ── Delete (WAIP-protected) ────────────────────────────────────────

    @Transactional
    public int delete(UUID accountId, String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("Cannot delete root directory");
        }

        DistributedVfsLock.LockHandle lock = lockPath(accountId, normalized);

        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.DELETE, normalized,
                null, null, null, 0, null);

        Optional<VirtualEntry> entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (entry.isEmpty()) {
            abortIntent(intent);
            lock.close();
            return 0;
        }

        int deleted;
        try {
            if (entry.get().isDirectory()) {
                deleted = entryRepository.softDeleteByPrefix(accountId, normalized + "%");
            } else {
                entry.get().setDeleted(true);
                entryRepository.save(entry.get());
                deleted = 1;
            }
            commitIntent(intent);
            return deleted;
        } finally {
            lock.close();
        }
    }

    // ── Move / Rename (WAIP-protected) ─────────────────────────────────

    @Transactional
    public void move(UUID accountId, String fromPath, String toPath) {
        String normalizedFrom = normalizePath(fromPath);
        String normalizedTo = normalizePath(toPath);

        if (normalizedFrom.equals(normalizedTo)) return;

        // Lock BOTH paths in lexicographic order to prevent deadlocks across pods
        String first = normalizedFrom.compareTo(normalizedTo) < 0 ? normalizedFrom : normalizedTo;
        String second = normalizedFrom.compareTo(normalizedTo) < 0 ? normalizedTo : normalizedFrom;
        DistributedVfsLock.LockHandle lock1 = lockPath(accountId, first);
        DistributedVfsLock.LockHandle lock2 = lockPath(accountId, second);

        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.MOVE, normalizedFrom,
                normalizedTo, null, null, 0, null);

        try {
            if (!entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizedFrom)) {
                throw new NoSuchElementException("Source not found: " + normalizedFrom);
            }
            if (entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizedTo)) {
                throw new IllegalStateException("Destination already exists: " + normalizedTo);
            }

            String destParent = parentOf(normalizedTo);
            if (!"/".equals(destParent)) {
                mkdirs(accountId, destParent);
            }

            VirtualEntry entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalizedFrom)
                    .orElseThrow();

            if (entry.isFile()) {
                entry.setPath(normalizedTo);
                entry.setParentPath(destParent);
                entry.setName(nameOf(normalizedTo));
                entryRepository.save(entry);
            } else {
                entryRepository.renamePrefixBulk(accountId, normalizedFrom, normalizedTo, destParent);
                entry.setPath(normalizedTo);
                entry.setParentPath(destParent);
                entry.setName(nameOf(normalizedTo));
                entryRepository.save(entry);
            }

            commitIntent(intent);
        } finally {
            lock1.close();
            lock2.close();
        }
    }

    // ── Bulk Operations (Onboarding) ───────────────────────────────────

    @Transactional
    public void provisionFolders(UUID accountId, List<String> folderPaths) {
        if (folderPaths == null || folderPaths.isEmpty()) return;
        for (String folder : folderPaths) {
            String normalized = normalizePath("/" + folder);
            if (!entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalized)) {
                mkdirs(accountId, normalized);
            }
        }
        log.info("Provisioned {} virtual folders for account {}", folderPaths.size(), accountId);
    }

    // ── Metrics ────────────────────────────────────────────────────────

    public Map<String, Object> getUsage(UUID accountId) {
        return Map.of(
                "fileCount", entryRepository.countFilesByAccount(accountId),
                "dirCount", entryRepository.countDirsByAccount(accountId),
                "totalSizeBytes", entryRepository.sumSizeByAccount(accountId)
        );
    }

    public long getRefCount(String storageKey) {
        return entryRepository.countByStorageKey(storageKey);
    }

    // ── WAIP Internal ──────────────────────────────────────────────────

    /**
     * Acquire path-level lock for VFS write operations.
     *
     * <p><b>Distributed mode (Redis available):</b> uses {@link DistributedVfsLock} —
     * a Redis SET NX EX mutex that works across all pods. This closes the multi-replica
     * race condition where two pods could bypass each other's pg_advisory locks.
     *
     * <p><b>Single-instance fallback (no Redis):</b> falls back to {@code pg_advisory_xact_lock}
     * which auto-releases on COMMIT/ROLLBACK and is correct for single-pod deployments.
     *
     * <p><strong>CALLER MUST close the returned handle in a finally block.</strong>
     */
    private DistributedVfsLock.LockHandle lockPath(UUID accountId, String path) {
        if (distributedLock != null) {
            // Cross-pod distributed lock — works for N replicas
            return distributedLock.acquire(accountId, path);
        }

        // Single-instance fallback: pg_advisory_xact_lock (session-scoped, auto-releases)
        long hash = accountId.getMostSignificantBits()
                ^ (accountId.getLeastSignificantBits() >>> 17)
                ^ ((long) path.hashCode() << 31);
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:hash)")
                    .setParameter("hash", hash)
                    .getSingleResult();
        } catch (Exception e) {
            log.warn("[VFS] lockPath contention account={} path={}: {}", accountId, path, e.getMessage());
            throw new IllegalStateException(
                    "Concurrent write contention on VFS path [" + path + "] — retry the operation", e);
        }
        return DistributedVfsLock.LockHandle.NOOP; // pg_advisory released on tx commit
    }

    private VfsIntent createIntent(UUID accountId, VfsIntent.OpType op, String path,
                                    String destPath, String storageKey, String trackId,
                                    long sizeBytes, String contentType) {
        return intentRepository.save(VfsIntent.builder()
                .accountId(accountId)
                .op(op)
                .path(path)
                .destPath(destPath)
                .storageKey(storageKey)
                .trackId(trackId)
                .sizeBytes(sizeBytes)
                .contentType(contentType)
                .status(VfsIntent.IntentStatus.PENDING)
                .podId(podId)
                .build());
    }

    private void commitIntent(VfsIntent intent) {
        intent.setStatus(VfsIntent.IntentStatus.COMMITTED);
        intent.setResolvedAt(Instant.now());
        intentRepository.save(intent);
    }

    private void abortIntent(VfsIntent intent) {
        intent.setStatus(VfsIntent.IntentStatus.ABORTED);
        intent.setResolvedAt(Instant.now());
        intentRepository.save(intent);
    }

    // ── Path Utilities ─────────────────────────────────────────────────

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static String parentOf(String normalizedPath) {
        if ("/".equals(normalizedPath)) return "/";
        int lastSlash = normalizedPath.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : normalizedPath.substring(0, lastSlash);
    }

    public static String nameOf(String normalizedPath) {
        if ("/".equals(normalizedPath)) return "";
        int lastSlash = normalizedPath.lastIndexOf('/');
        return normalizedPath.substring(lastSlash + 1);
    }
}
