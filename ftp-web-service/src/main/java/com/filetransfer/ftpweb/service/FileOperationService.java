package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileOperationService {

    private final TransferAccountRepository accountRepository;
    private final RoutingEngine routingEngine;
    private final ServerInstanceRepository serverInstanceRepository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final VirtualFileSystem virtualFileSystem;
    private final StorageServiceClient storageServiceClient;

    @Value("${ftpweb.instance-id:#{null}}")
    private String instanceId;

    /** Blocked file extensions — executable or server-side script types that could be weaponized */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".jsp", ".jspx", ".exe", ".bat", ".cmd", ".sh", ".ps1", ".com",
            ".msi", ".scr", ".pif", ".vbs", ".vbe", ".wsf", ".wsh", ".jar",
            ".war", ".class", ".dll", ".so", ".dylib", ".cgi", ".php", ".asp",
            ".aspx", ".py", ".rb", ".pl");

    public record FileEntry(String name, String path, boolean directory, long size, Instant lastModified) {}

    public List<FileEntry> list(String username, String relativePath) throws IOException {
        TransferAccount account = findAccount(username);
        if (isVirtualMode(account)) {
            String vpath = VirtualFileSystem.normalizePath("/" + relativePath);
            return virtualFileSystem.list(account.getId(), vpath).stream()
                    .map(e -> new FileEntry(e.getName(), e.getPath(), e.isDirectory(),
                            e.getSizeBytes(), e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.now()))
                    .toList();
        }

        Path dir = resolvePathForAccount(account, relativePath);
        List<FileEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                entries.add(new FileEntry(
                        entry.getFileName().toString(),
                        "/" + dir.relativize(entry),
                        attrs.isDirectory(),
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()
                ));
            }
        }
        return entries;
    }

    public void upload(String username, String relativeDirPath, MultipartFile file) throws IOException {
        String originalFilename = Objects.requireNonNull(file.getOriginalFilename(), "filename is required");

        // Reject dangerous file extensions
        String lowerName = originalFilename.toLowerCase();
        for (String ext : BLOCKED_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                throw new SecurityException("File extension not allowed: " + ext);
            }
        }
        // Reject filenames with path separators or special names
        if (originalFilename.contains("/") || originalFilename.contains("\\")
                || ".".equals(originalFilename) || "..".equals(originalFilename)) {
            throw new SecurityException("Invalid filename: " + originalFilename);
        }

        TransferAccount account = findAccount(username);

        // ── Virtual mode: store via CAS ─────────────────────────────────
        if (isVirtualMode(account)) {
            String vpath = VirtualFileSystem.normalizePath("/" + relativeDirPath + "/" + originalFilename);
            Map<String, Object> result = storageServiceClient.store(
                    originalFilename, file.getBytes(), null, account.getId().toString());
            String sha256 = (String) result.get("sha256");
            String trackId = result.get("trackId") != null ? result.get("trackId").toString() : null;
            virtualFileSystem.writeFile(account.getId(), vpath, sha256, file.getSize(), trackId, file.getContentType());
            log.info("FTP-Web virtual upload: user={} path={} sha256={}", username, vpath,
                    sha256 != null ? sha256.substring(0, 8) + "..." : "n/a");
            return;
        }

        // ── Physical mode: legacy filesystem ────────────────────────────
        Path dir = resolvePathForAccount(account, relativeDirPath);
        Files.createDirectories(dir);
        Path dest = dir.resolve(originalFilename);
        file.transferTo(dest);

        String relativeFilePath = relativeDirPath + "/" + file.getOriginalFilename();
        routingEngine.onFileUploaded(account, relativeFilePath, dest.toAbsolutePath().toString());
        log.info("FTP-Web upload: user={} path={}", username, dest);
    }

    public Resource download(String username, String relativeFilePath) throws MalformedURLException {
        TransferAccount account = findAccount(username);

        if (isVirtualMode(account)) {
            String vpath = VirtualFileSystem.normalizePath("/" + relativeFilePath);
            byte[] data = virtualFileSystem.readFile(account.getId(), vpath);
            String filename = VirtualFileSystem.nameOf(vpath);
            return new ByteArrayResource(data) {
                @Override public String getFilename() { return filename; }
            };
        }

        Path file = resolvePathForAccount(account, relativeFilePath);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            throw new NoSuchElementException("File not found: " + relativeFilePath);
        }
        routingEngine.onFileDownloaded(account, file.toAbsolutePath().toString());
        return new UrlResource(file.toUri());
    }

    public void mkdir(String username, String relativeDirPath) throws IOException {
        TransferAccount account = findAccount(username);
        if (isVirtualMode(account)) {
            virtualFileSystem.mkdirs(account.getId(), VirtualFileSystem.normalizePath("/" + relativeDirPath));
            return;
        }
        Path dir = resolvePathForAccount(account, relativeDirPath);
        Files.createDirectories(dir);
    }

    public void delete(String username, String relativeFilePath) throws IOException {
        TransferAccount account = findAccount(username);
        if (isVirtualMode(account)) {
            virtualFileSystem.delete(account.getId(), VirtualFileSystem.normalizePath("/" + relativeFilePath));
            return;
        }
        Path file = resolvePathForAccount(account, relativeFilePath);
        if (Files.isDirectory(file)) {
            deleteRecursively(file);
        } else {
            Files.deleteIfExists(file);
        }
    }

    public void rename(String username, String fromPath, String toPath) throws IOException {
        TransferAccount account = findAccount(username);
        if (isVirtualMode(account)) {
            virtualFileSystem.move(account.getId(),
                    VirtualFileSystem.normalizePath("/" + fromPath),
                    VirtualFileSystem.normalizePath("/" + toPath));
            return;
        }
        Path from = resolvePathForAccount(account, fromPath);
        Path to = resolvePathForAccount(account, toPath);
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveAndValidate(String username, String relativePath) {
        return resolvePathForAccount(findAccount(username), relativePath);
    }

    private Path resolvePathForAccount(TransferAccount account, String relativePath) {
        Path homeDir = Paths.get(account.getHomeDir());
        Path resolved = homeDir.resolve(relativePath.startsWith("/")
                ? relativePath.substring(1) : relativePath).normalize();
        if (!resolved.startsWith(homeDir)) {
            throw new SecurityException("Path traversal attempt detected");
        }
        return resolved;
    }

    private TransferAccount findAccount(String username) {
        Optional<TransferAccount> account;
        if (instanceId != null) {
            account = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.FTP_WEB, instanceId);
        } else {
            account = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP_WEB);
        }
        TransferAccount acct = account.orElseThrow(() -> new NoSuchElementException("FTP_WEB account not found: " + username));
        // Ensure template folders exist on first access (idempotent safety net)
        ensureTemplateFolders(acct.getHomeDir());
        return acct;
    }

    private void ensureTemplateFolders(String homeDir) {
        if (homeDir == null) return;
        try {
            for (String folder : resolveFolderPaths()) {
                Files.createDirectories(Paths.get(homeDir, folder));
            }
        } catch (Exception e) {
            log.warn("Could not create template folders in {}: {}", homeDir, e.getMessage());
        }
    }

    private List<String> resolveFolderPaths() {
        try {
            if (instanceId != null) {
                return serverInstanceRepository.findByInstanceId(instanceId)
                        .filter(si -> si.getFolderTemplate() != null)
                        .map(si -> si.getFolderTemplate().getFolders().stream()
                                .map(FolderDefinition::getPath).toList())
                        .orElse(List.of());
            }
        } catch (Exception e) {
            log.warn("Could not resolve folder template: {}", e.getMessage());
        }
        return List.of();
    }

    private boolean isVirtualMode(TransferAccount account) {
        return "VIRTUAL".equalsIgnoreCase(account.getStorageMode());
    }

    private void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
