package com.filetransfer.ftp.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces file-level operation controls:
 * <ul>
 *   <li><strong>Max upload file size</strong> -- rejects uploads that exceed a configurable limit.</li>
 *   <li><strong>Disk quota per user</strong> -- rejects uploads if the user's home directory exceeds a size limit.</li>
 *   <li><strong>Extension allow/deny lists</strong> -- restricts which file types may be uploaded.</li>
 * </ul>
 *
 * <p>All checks run <em>before</em> the upload starts ({@code onUploadStart}).
 * The actual file size is not known at that point for streamed uploads,
 * so the size check is also performed in a post-upload hook that logs a warning
 * (the file is already written by then, but the audit trail is preserved).
 */
@Slf4j
@Component
public class FileOperationFilter extends DefaultFtplet {

    /** Maximum upload file size in bytes (0 = unlimited). */
    @Value("${ftp.file.max-upload-size:0}")
    private long maxUploadSize;

    /** Per-user disk quota in bytes (0 = unlimited). */
    @Value("${ftp.file.disk-quota:0}")
    private long diskQuota;

    /** Comma-separated allowed extensions (empty = all allowed). */
    @Value("${ftp.file.allowed-extensions:}")
    private String allowedExtensionsRaw;

    /** Comma-separated denied extensions (empty = none denied). */
    @Value("${ftp.file.denied-extensions:}")
    private String deniedExtensionsRaw;

    private Set<String> allowedExtensions = Collections.emptySet();
    private Set<String> deniedExtensions = Collections.emptySet();

    @jakarta.annotation.PostConstruct
    void init() {
        allowedExtensions = parseExtensions(allowedExtensionsRaw);
        deniedExtensions = parseExtensions(deniedExtensionsRaw);
        if (!allowedExtensions.isEmpty()) {
            log.info("FTP allowed extensions: {}", allowedExtensions);
        }
        if (!deniedExtensions.isEmpty()) {
            log.info("FTP denied extensions: {}", deniedExtensions);
        }
        if (maxUploadSize > 0) {
            log.info("FTP max upload size: {} bytes", maxUploadSize);
        }
        if (diskQuota > 0) {
            log.info("FTP per-user disk quota: {} bytes", diskQuota);
        }
    }

    /**
     * Validate the upload before it starts.
     * Checks extension restrictions and disk quota.
     */
    @Override
    public FtpletResult onUploadStart(FtpSession session, FtpRequest request)
            throws FtpException {
        String filename = request.getArgument();
        if (filename == null) {
            return FtpletResult.DEFAULT;
        }

        // Extension check
        String ext = extractExtension(filename);
        if (!deniedExtensions.isEmpty() && deniedExtensions.contains(ext)) {
            log.warn("Upload denied (extension blocked): user={} file={} ext={}",
                    getUsername(session), filename, ext);
            session.write(new DefaultFtpReply(553, "File type not allowed: ." + ext));
            return FtpletResult.SKIP;
        }
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(ext)) {
            log.warn("Upload denied (extension not in allowlist): user={} file={} ext={}",
                    getUsername(session), filename, ext);
            session.write(new DefaultFtpReply(553, "File type not allowed: ." + ext));
            return FtpletResult.SKIP;
        }

        // Disk quota check
        if (diskQuota > 0) {
            String homeDir = session.getUser().getHomeDirectory();
            long used = directorySize(new File(homeDir));
            if (used >= diskQuota) {
                log.warn("Upload denied (disk quota exceeded): user={} used={} quota={}",
                        getUsername(session), used, diskQuota);
                session.write(new DefaultFtpReply(552, "Disk quota exceeded."));
                return FtpletResult.SKIP;
            }
        }

        return FtpletResult.DEFAULT;
    }

    /**
     * After upload completes, verify the file size against the limit.
     * The file is already written at this point, so we log a warning and
     * optionally delete oversized files.
     */
    @Override
    public FtpletResult onUploadEnd(FtpSession session, FtpRequest request)
            throws FtpException {
        if (maxUploadSize <= 0) {
            return FtpletResult.DEFAULT;
        }

        String filename = request.getArgument();
        if (filename == null) {
            return FtpletResult.DEFAULT;
        }

        String homeDir = session.getUser().getHomeDirectory();
        File uploaded = new File(homeDir, filename);
        if (uploaded.exists() && uploaded.length() > maxUploadSize) {
            log.warn("Uploaded file exceeds max size: user={} file={} size={} max={}",
                    getUsername(session), filename, uploaded.length(), maxUploadSize);
            // Delete the oversized file
            if (uploaded.delete()) {
                log.info("Oversized file deleted: {}", uploaded.getAbsolutePath());
            }
            session.write(new DefaultFtpReply(552, "File exceeds maximum allowed size."));
            return FtpletResult.DISCONNECT;
        }

        return FtpletResult.DEFAULT;
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    private String getUsername(FtpSession session) {
        try {
            return session.getUser() != null ? session.getUser().getName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private long directorySize(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    size += f.length();
                } else if (f.isDirectory()) {
                    size += directorySize(f);
                }
            }
        }
        return size;
    }

    private Set<String> parseExtensions(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
