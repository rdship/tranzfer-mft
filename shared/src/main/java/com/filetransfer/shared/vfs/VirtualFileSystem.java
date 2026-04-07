package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VfsChunk;
import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.repository.VfsChunkRepository;
import com.filetransfer.shared.repository.VfsIntentRepository;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;

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

    // ── Storage Bucket Routing ─────────────────────────────────────────

    public String determineBucket(long sizeBytes) {
        if (sizeBytes <= inlineMaxBytes) return "INLINE";
        if (sizeBytes > chunkThresholdBytes) return "CHUNKED";
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
        String bucket = determineBucket(sizeBytes);

        // 1. Advisory lock: serialize concurrent writes to same (account, path)
        lockPath(accountId, normalized);

        // 2. Record intent BEFORE mutation
        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.WRITE, normalized,
                null, storageKey, trackId, sizeBytes, contentType);

        // 3. Auto-create parent directories
        if (!"/".equals(parentPath)) {
            mkdirs(accountId, parentPath);
        }

        // 4. Create or update entry (now under advisory lock — no race)
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
                entry.setInlineContent(inlineContent);
            } else {
                entry.setInlineContent(null);
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
                    .inlineContent("INLINE".equals(bucket) ? inlineContent : null)
                    .build());
        }

        // 5. Mark intent COMMITTED (same transaction — single DB commit)
        commitIntent(intent);
        return result;
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
                    yield entry.getInlineContent();
                }
                throw new NoSuchElementException("Inline content missing for: " + normalized);
            }
            case "CHUNKED" -> readChunked(entry);
            default -> {
                // STANDARD path — retrieve from CAS
                if (entry.getTrackId() != null) {
                    yield storageClient.retrieve(entry.getTrackId());
                }
                throw new NoSuchElementException("File has no storage reference: " + normalized);
            }
        };
    }

    private byte[] readChunked(VirtualEntry entry) {
        List<VfsChunk> chunks = chunkRepository.findByEntryIdOrderByChunkIndex(entry.getId());
        if (chunks.isEmpty()) {
            throw new NoSuchElementException("No chunks found for: " + entry.getPath());
        }

        ByteArrayOutputStream assembled = new ByteArrayOutputStream((int) entry.getSizeBytes());
        for (VfsChunk chunk : chunks) {
            // Retrieve each chunk from CAS by its storage key
            byte[] chunkData = storageClient.retrieve(chunk.getStorageKey());
            assembled.writeBytes(chunkData);
        }
        return assembled.toByteArray();
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

        lockPath(accountId, normalized);

        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.DELETE, normalized,
                null, null, null, 0, null);

        Optional<VirtualEntry> entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (entry.isEmpty()) {
            abortIntent(intent);
            return 0;
        }

        int deleted;
        if (entry.get().isDirectory()) {
            deleted = entryRepository.softDeleteByPrefix(accountId, normalized + "%");
        } else {
            entry.get().setDeleted(true);
            entryRepository.save(entry.get());
            deleted = 1;
        }

        commitIntent(intent);
        return deleted;
    }

    // ── Move / Rename (WAIP-protected) ─────────────────────────────────

    @Transactional
    public void move(UUID accountId, String fromPath, String toPath) {
        String normalizedFrom = normalizePath(fromPath);
        String normalizedTo = normalizePath(toPath);

        if (normalizedFrom.equals(normalizedTo)) return;

        // Lock BOTH paths in lexicographic order to prevent deadlocks
        String first = normalizedFrom.compareTo(normalizedTo) < 0 ? normalizedFrom : normalizedTo;
        String second = normalizedFrom.compareTo(normalizedTo) < 0 ? normalizedTo : normalizedFrom;
        lockPath(accountId, first);
        lockPath(accountId, second);

        VfsIntent intent = createIntent(accountId, VfsIntent.OpType.MOVE, normalizedFrom,
                normalizedTo, null, null, 0, null);

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
     * Acquire path-level advisory lock within the current transaction.
     * Uses pg_advisory_xact_lock which auto-releases on COMMIT/ROLLBACK.
     * Zero disk I/O — purely in-memory PostgreSQL lock manager.
     */
    private void lockPath(UUID accountId, String path) {
        long hash = (long) accountId.hashCode() * 31 + path.hashCode();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:hash)")
                .setParameter("hash", hash)
                .getSingleResult();
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
