package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Core Virtual Filesystem service.
 *
 * <p>All folder/file operations go through this service. Directories are zero-cost DB rows.
 * File content is stored in content-addressed storage (CAS) via the Storage Manager.
 * Protocol layers (SFTP, FTP, FTP-web) delegate to this service.
 *
 * <p>Thread-safe: all mutable operations are transactional.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualFileSystem {

    private final VirtualEntryRepository entryRepository;
    private final StorageServiceClient storageClient;

    // ── Directory Operations ────────────────────────────────────────────

    /**
     * Create a single directory. Parent must exist.
     *
     * @throws IllegalArgumentException if parent doesn't exist
     * @throws IllegalStateException if path already exists
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

        // Check if already exists
        Optional<VirtualEntry> existing = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (existing.isPresent()) {
            if (existing.get().isDirectory()) return existing.get();
            throw new IllegalStateException("Path exists as file: " + normalized);
        }

        // Ensure all ancestors exist
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

    // ── Listing ─────────────────────────────────────────────────────────

    /**
     * List immediate children of a directory.
     */
    public List<VirtualEntry> list(UUID accountId, String directoryPath) {
        String normalized = normalizePath(directoryPath);
        return entryRepository.findByAccountIdAndParentPathAndDeletedFalse(accountId, normalized);
    }

    // ── File Write ──────────────────────────────────────────────────────

    /**
     * Create or overwrite a file entry. Content is stored via Storage Manager.
     *
     * @param accountId  account that owns the file
     * @param path       full virtual path, e.g. "/inbox/invoice.edi"
     * @param storageKey SHA-256 key returned by Storage Manager after CAS write
     * @param sizeBytes  file size
     * @param trackId    routing track ID (optional)
     * @param contentType MIME type (optional)
     * @return the created or updated virtual entry
     */
    @Transactional
    public VirtualEntry writeFile(UUID accountId, String path, String storageKey,
                                   long sizeBytes, String trackId, String contentType) {
        String normalized = normalizePath(path);
        String parentPath = parentOf(normalized);

        // Auto-create parent directories
        if (!"/".equals(parentPath)) {
            mkdirs(accountId, parentPath);
        }

        // Check for existing entry
        Optional<VirtualEntry> existing = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (existing.isPresent()) {
            VirtualEntry entry = existing.get();
            if (entry.isDirectory()) {
                throw new IllegalStateException("Cannot write file: path is a directory: " + normalized);
            }
            // Overwrite: update storage key
            entry.setStorageKey(storageKey);
            entry.setSizeBytes(sizeBytes);
            entry.setTrackId(trackId);
            entry.setContentType(contentType);
            return entryRepository.save(entry);
        }

        return entryRepository.save(VirtualEntry.builder()
                .accountId(accountId)
                .path(normalized)
                .parentPath(parentPath)
                .name(nameOf(normalized))
                .type(VirtualEntry.EntryType.FILE)
                .storageKey(storageKey)
                .sizeBytes(sizeBytes)
                .trackId(trackId)
                .contentType(contentType)
                .build());
    }

    // ── File Read ───────────────────────────────────────────────────────

    /**
     * Get file content bytes from CAS. Updates access counters.
     *
     * @return raw bytes, or empty if entry not found or is a directory
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

        // Retrieve from CAS via Storage Manager
        if (entry.getTrackId() != null) {
            return storageClient.retrieve(entry.getTrackId());
        }
        throw new NoSuchElementException("File has no storage reference: " + normalized);
    }

    // ── Stat ────────────────────────────────────────────────────────────

    /**
     * Get metadata for a single entry (like stat()).
     */
    public Optional<VirtualEntry> stat(UUID accountId, String path) {
        return entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalizePath(path));
    }

    /**
     * Check if a path exists.
     */
    public boolean exists(UUID accountId, String path) {
        return entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizePath(path));
    }

    // ── Delete ──────────────────────────────────────────────────────────

    /**
     * Soft-delete an entry. If directory, recursively soft-deletes all children.
     *
     * @return number of entries soft-deleted
     */
    @Transactional
    public int delete(UUID accountId, String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("Cannot delete root directory");
        }

        Optional<VirtualEntry> entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalized);
        if (entry.isEmpty()) return 0;

        if (entry.get().isDirectory()) {
            // Recursive soft-delete: path and everything under it
            return entryRepository.softDeleteByPrefix(accountId, normalized + "%");
        }

        entry.get().setDeleted(true);
        entryRepository.save(entry.get());
        return 1;
    }

    // ── Move / Rename ───────────────────────────────────────────────────

    /**
     * Move or rename an entry (and all descendants if directory).
     */
    @Transactional
    public void move(UUID accountId, String fromPath, String toPath) {
        String normalizedFrom = normalizePath(fromPath);
        String normalizedTo = normalizePath(toPath);

        if (normalizedFrom.equals(normalizedTo)) return;

        if (!entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizedFrom)) {
            throw new NoSuchElementException("Source not found: " + normalizedFrom);
        }
        if (entryRepository.existsByAccountIdAndPathAndDeletedFalse(accountId, normalizedTo)) {
            throw new IllegalStateException("Destination already exists: " + normalizedTo);
        }

        // Ensure destination parent exists
        String destParent = parentOf(normalizedTo);
        if (!"/".equals(destParent)) {
            mkdirs(accountId, destParent);
        }

        VirtualEntry entry = entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, normalizedFrom)
                .orElseThrow();

        if (entry.isFile()) {
            // Simple file rename
            entry.setPath(normalizedTo);
            entry.setParentPath(destParent);
            entry.setName(nameOf(normalizedTo));
            entryRepository.save(entry);
        } else {
            // Directory: bulk rename all entries under the old prefix
            entryRepository.renamePrefixBulk(accountId, normalizedFrom, normalizedTo, destParent);
            // Update the directory entry itself
            entry.setPath(normalizedTo);
            entry.setParentPath(destParent);
            entry.setName(nameOf(normalizedTo));
            entryRepository.save(entry);
        }
    }

    // ── Bulk Operations (Onboarding) ────────────────────────────────────

    /**
     * Create folder template entries for a new account.
     * Zero disk I/O — just DB inserts.
     *
     * @param accountId   the account UUID
     * @param folderPaths list of relative paths, e.g. ["inbox", "outbox", "archive"]
     */
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

    // ── Metrics ─────────────────────────────────────────────────────────

    /**
     * Get storage usage for an account.
     */
    public Map<String, Object> getUsage(UUID accountId) {
        return Map.of(
                "fileCount", entryRepository.countFilesByAccount(accountId),
                "dirCount", entryRepository.countDirsByAccount(accountId),
                "totalSizeBytes", entryRepository.sumSizeByAccount(accountId)
        );
    }

    /**
     * Count references to a storage key (for CAS garbage collection).
     */
    public long getRefCount(String storageKey) {
        return entryRepository.countByStorageKey(storageKey);
    }

    // ── Path Utilities ──────────────────────────────────────────────────

    /** Normalize a path: ensure leading /, no trailing /, no double slashes. */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.replace('\\', '/');
        // Remove double slashes
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        // Ensure leading /
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        // Remove trailing / (except root)
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** Get parent path: "/inbox/file.edi" → "/inbox", "/inbox" → "/". */
    public static String parentOf(String normalizedPath) {
        if ("/".equals(normalizedPath)) return "/";
        int lastSlash = normalizedPath.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : normalizedPath.substring(0, lastSlash);
    }

    /** Get entry name: "/inbox/file.edi" → "file.edi", "/inbox" → "inbox". */
    public static String nameOf(String normalizedPath) {
        if ("/".equals(normalizedPath)) return "";
        int lastSlash = normalizedPath.lastIndexOf('/');
        return normalizedPath.substring(lastSlash + 1);
    }
}
