package com.filetransfer.sftp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces file operation policies including maximum upload size,
 * disk quota per user, and file type restrictions.
 *
 * <p>All limits default to 0 (disabled) for backward compatibility.</p>
 */
@Slf4j
@Component
public class FileOperationPolicy {

    /** Maximum upload file size in bytes (0 = unlimited). */
    @Value("${sftp.files.max-upload-size-bytes:0}")
    private long maxUploadSizeBytes;

    /** Maximum total disk usage per user in bytes (0 = unlimited). */
    @Value("${sftp.files.disk-quota-bytes:0}")
    private long diskQuotaBytes;

    /** Comma-separated list of allowed file extensions (empty = all allowed). */
    @Value("${sftp.files.allowed-extensions:}")
    private List<String> allowedExtensions;

    /** Comma-separated list of denied file extensions (empty = none denied). */
    @Value("${sftp.files.denied-extensions:}")
    private List<String> deniedExtensions;

    /** Whether to prevent symlink traversal. */
    @Value("${sftp.files.prevent-symlink-traversal:true}")
    private boolean preventSymlinkTraversal;

    private Set<String> normalizedAllowed = Collections.emptySet();
    private Set<String> normalizedDenied = Collections.emptySet();

    @PostConstruct
    void init() {
        if (allowedExtensions != null) {
            normalizedAllowed = allowedExtensions.stream()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.startsWith(".") ? s : "." + s)
                    .collect(Collectors.toSet());
        }
        if (deniedExtensions != null) {
            normalizedDenied = deniedExtensions.stream()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.startsWith(".") ? s : "." + s)
                    .collect(Collectors.toSet());
        }

        if (!normalizedAllowed.isEmpty()) {
            log.info("File type allowlist enabled: {}", normalizedAllowed);
        }
        if (!normalizedDenied.isEmpty()) {
            log.info("File type denylist enabled: {}", normalizedDenied);
        }
        if (maxUploadSizeBytes > 0) {
            log.info("Max upload size: {} bytes ({} MB)", maxUploadSizeBytes, maxUploadSizeBytes / (1024 * 1024));
        }
        if (diskQuotaBytes > 0) {
            log.info("Disk quota per user: {} bytes ({} MB)", diskQuotaBytes, diskQuotaBytes / (1024 * 1024));
        }
    }

    /**
     * Checks whether a file with the given name is allowed based on extension rules.
     *
     * @param filename the filename to check
     * @return true if the file type is permitted
     */
    public boolean isFileTypeAllowed(String filename) {
        if (filename == null) return true;
        String ext = getExtension(filename);

        // Denylist takes precedence
        if (!normalizedDenied.isEmpty() && normalizedDenied.contains(ext)) {
            log.warn("File type denied: filename={} extension={}", filename, ext);
            return false;
        }

        // If allowlist is set, extension must be on it
        if (!normalizedAllowed.isEmpty() && !normalizedAllowed.contains(ext)) {
            log.warn("File type not on allowlist: filename={} extension={}", filename, ext);
            return false;
        }

        return true;
    }

    /**
     * Checks whether the given file size exceeds the maximum upload limit.
     *
     * @param sizeBytes the file size in bytes
     * @return true if the size is within limits (or limits are disabled)
     */
    public boolean isUploadSizeAllowed(long sizeBytes) {
        if (maxUploadSizeBytes <= 0) return true;
        return sizeBytes <= maxUploadSizeBytes;
    }

    /**
     * Checks whether writing additional bytes to a user's home directory
     * would exceed the disk quota.
     *
     * @param userHomeDir      the user's home directory
     * @param additionalBytes  the number of bytes to be written
     * @return true if the write is within quota (or quota is disabled)
     */
    public boolean isDiskQuotaAllowed(Path userHomeDir, long additionalBytes) {
        if (diskQuotaBytes <= 0) return true;
        try {
            long currentUsage = calculateDirectorySize(userHomeDir);
            return (currentUsage + additionalBytes) <= diskQuotaBytes;
        } catch (IOException e) {
            log.warn("Failed to calculate disk usage for {}: {}", userHomeDir, e.getMessage());
            // Fail open to avoid blocking transfers on transient errors
            return true;
        }
    }

    /**
     * Checks whether the given path is a symlink, returning false if symlink
     * traversal prevention is enabled and the path is a symlink.
     *
     * @param path the path to check
     * @return true if the path is safe to access
     */
    public boolean isSymlinkSafe(Path path) {
        if (!preventSymlinkTraversal) return true;
        if (Files.isSymbolicLink(path)) {
            log.warn("Symlink traversal blocked: {}", path);
            return false;
        }
        return true;
    }

    /**
     * Returns the configured max upload size in bytes.
     */
    public long getMaxUploadSizeBytes() {
        return maxUploadSizeBytes;
    }

    /**
     * Returns the configured disk quota in bytes.
     */
    public long getDiskQuotaBytes() {
        return diskQuotaBytes;
    }

    /**
     * Returns whether symlink traversal prevention is enabled.
     */
    public boolean isPreventSymlinkTraversal() {
        return preventSymlinkTraversal;
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return "";
        return filename.substring(dotIndex).toLowerCase();
    }

    private long calculateDirectorySize(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0;
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        }
    }
}
