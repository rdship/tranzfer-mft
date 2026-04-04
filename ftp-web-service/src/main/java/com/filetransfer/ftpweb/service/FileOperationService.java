package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        Path dir = resolveAndValidate(username, relativeDirPath);
        Files.createDirectories(dir);
        Path dest = dir.resolve(Objects.requireNonNull(file.getOriginalFilename()));
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
        return accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP_WEB)
                .orElseThrow(() -> new NoSuchElementException("FTP_WEB account not found: " + username));
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
