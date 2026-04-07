package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
        Path dir = resolveAndValidate(username, relativePath);
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

        Path dir = resolveAndValidate(username, relativeDirPath);
        Files.createDirectories(dir);
        Path dest = dir.resolve(originalFilename);
        file.transferTo(dest);

        TransferAccount account = findAccount(username);
        String relativeFilePath = relativeDirPath + "/" + file.getOriginalFilename();
        routingEngine.onFileUploaded(account, relativeFilePath, dest.toAbsolutePath().toString());
        log.info("FTP-Web upload: user={} path={}", username, dest);
    }

    public Resource download(String username, String relativeFilePath) throws MalformedURLException {
        Path file = resolveAndValidate(username, relativeFilePath);
        if (!Files.exists(file) || Files.isDirectory(file)) {
            throw new NoSuchElementException("File not found: " + relativeFilePath);
        }

        TransferAccount account = findAccount(username);
        routingEngine.onFileDownloaded(account, file.toAbsolutePath().toString());

        return new UrlResource(file.toUri());
    }

    public void mkdir(String username, String relativeDirPath) throws IOException {
        Path dir = resolveAndValidate(username, relativeDirPath);
        Files.createDirectories(dir);
    }

    public void delete(String username, String relativeFilePath) throws IOException {
        Path file = resolveAndValidate(username, relativeFilePath);
        if (Files.isDirectory(file)) {
            deleteRecursively(file);
        } else {
            Files.deleteIfExists(file);
        }
    }

    public void rename(String username, String fromPath, String toPath) throws IOException {
        Path from = resolveAndValidate(username, fromPath);
        Path to = resolveAndValidate(username, toPath);
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolveAndValidate(String username, String relativePath) {
        TransferAccount account = findAccount(username);
        Path homeDir = Paths.get(account.getHomeDir());
        // Prevent path traversal: normalize and ensure path stays within homeDir
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
