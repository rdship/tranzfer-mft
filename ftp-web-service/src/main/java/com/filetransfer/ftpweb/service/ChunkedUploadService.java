package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.entity.ChunkedUpload;
import com.filetransfer.shared.entity.ChunkedUploadChunk;
import com.filetransfer.shared.repository.ChunkedUploadChunkRepository;
import com.filetransfer.shared.repository.ChunkedUploadRepository;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Service for handling chunked file uploads with resume capability.
 *
 * <p>Flow:
 * <ol>
 *   <li>Client calls init → gets uploadId</li>
 *   <li>Client uploads chunks (in any order, supports parallel uploads)</li>
 *   <li>Client calls complete → service assembles chunks and verifies integrity</li>
 * </ol>
 *
 * <p>Resume: client can call status to see which chunks are missing,
 * then upload only those chunks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkedUploadService {

    private final ChunkedUploadRepository uploadRepository;
    private final ChunkedUploadChunkRepository chunkRepository;

    @Value("${ftpweb.chunked.temp-dir:${FTPWEB_CHUNK_TEMP_DIR:${java.io.tmpdir}/mft-chunks}}")
    private String chunkTempDir;

    @Value("${ftpweb.chunked.default-chunk-size:5242880}")  // 5MB default
    private long defaultChunkSize;

    @Value("${ftpweb.chunked.max-file-size:10737418240}")  // 10GB default max
    private long maxFileSize;

    @Value("${ftpweb.chunked.expiry-hours:24}")
    private int expiryHours;

    @PostConstruct
    void init() {
        log.info("Chunk temp directory: {} (set FTPWEB_CHUNK_TEMP_DIR for shared volume in multi-instance mode)", chunkTempDir);
    }

    /**
     * Initialize a new chunked upload session.
     */
    @Transactional
    public ChunkedUpload initUpload(String filename, long totalSize, int totalChunks,
                                     String checksum, String accountUsername, String contentType) {
        if (totalSize > maxFileSize) {
            throw new IllegalArgumentException("File size " + totalSize
                    + " exceeds maximum allowed " + maxFileSize);
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be positive");
        }

        long chunkSize = totalChunks > 0 ? (totalSize + totalChunks - 1) / totalChunks : defaultChunkSize;

        ChunkedUpload upload = ChunkedUpload.builder()
                .filename(filename)
                .totalSize(totalSize)
                .totalChunks(totalChunks)
                .chunkSize(chunkSize)
                .checksum(checksum)
                .accountUsername(accountUsername)
                .contentType(contentType)
                .status("INITIATED")
                .expiresAt(Instant.now().plusSeconds((long) expiryHours * 3600))
                .build();

        // Create temp directory for this upload's chunks
        Path uploadDir = Paths.get(chunkTempDir, upload.getId().toString());
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create chunk directory: " + e.getMessage(), e);
        }

        log.info("Chunked upload initiated: id={} file={} size={} chunks={}",
                upload.getId(), filename, totalSize, totalChunks);

        return uploadRepository.save(upload);
    }

    /**
     * Receive a single chunk.
     */
    @Transactional
    public ChunkedUploadChunk receiveChunk(UUID uploadId, int chunkNumber,
                                            InputStream data, long size) throws IOException {
        ChunkedUpload upload = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        if ("COMPLETED".equals(upload.getStatus()) || "CANCELLED".equals(upload.getStatus())) {
            throw new IllegalStateException("Upload is " + upload.getStatus());
        }
        if (chunkNumber < 0 || chunkNumber >= upload.getTotalChunks()) {
            throw new IllegalArgumentException("Invalid chunk number " + chunkNumber
                    + " (expected 0-" + (upload.getTotalChunks() - 1) + ")");
        }

        // Check if chunk already exists (idempotent — allows retry)
        Optional<ChunkedUploadChunk> existing = chunkRepository
                .findByUploadIdAndChunkNumber(uploadId, chunkNumber);
        if (existing.isPresent()) {
            log.debug("Chunk {}/{} already received for upload {}", chunkNumber, upload.getTotalChunks(), uploadId);
            return existing.get();
        }

        // Write chunk to temp storage
        Path chunkPath = Paths.get(chunkTempDir, uploadId.toString(),
                "chunk_" + String.format("%06d", chunkNumber));
        Files.createDirectories(chunkPath.getParent());

        String chunkChecksum;
        try (OutputStream out = Files.newOutputStream(chunkPath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = data.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                digest.update(buffer, 0, bytesRead);
            }
            chunkChecksum = HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            Files.deleteIfExists(chunkPath);
            throw new IOException("Failed to write chunk: " + e.getMessage(), e);
        }

        ChunkedUploadChunk chunk = ChunkedUploadChunk.builder()
                .uploadId(uploadId)
                .chunkNumber(chunkNumber)
                .size(Files.size(chunkPath))
                .checksum(chunkChecksum)
                .storagePath(chunkPath.toString())
                .build();
        chunkRepository.save(chunk);

        // Update received count
        upload.setReceivedChunks(upload.getReceivedChunks() + 1);
        if ("INITIATED".equals(upload.getStatus())) {
            upload.setStatus("IN_PROGRESS");
        }
        uploadRepository.save(upload);

        log.debug("Chunk {}/{} received for upload {} ({}B)",
                chunkNumber + 1, upload.getTotalChunks(), uploadId, chunk.getSize());

        return chunk;
    }

    /**
     * Assemble all chunks into the final file.
     *
     * @return the path to the assembled file
     */
    @Transactional
    public Path completeUpload(UUID uploadId) throws IOException {
        ChunkedUpload upload = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        if ("COMPLETED".equals(upload.getStatus())) {
            throw new IllegalStateException("Upload already completed");
        }

        // Verify all chunks received
        long receivedCount = chunkRepository.countByUploadId(uploadId);
        if (receivedCount < upload.getTotalChunks()) {
            throw new IllegalStateException("Missing chunks: received " + receivedCount
                    + " of " + upload.getTotalChunks());
        }

        upload.setStatus("ASSEMBLING");
        uploadRepository.save(upload);

        // Assemble chunks in order
        List<ChunkedUploadChunk> chunks = chunkRepository
                .findByUploadIdOrderByChunkNumberAsc(uploadId);

        Path assembledFile = Paths.get(chunkTempDir, uploadId.toString(), upload.getFilename());
        try (OutputStream out = Files.newOutputStream(assembledFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (ChunkedUploadChunk chunk : chunks) {
                Path chunkPath = Paths.get(chunk.getStoragePath());
                byte[] chunkData = Files.readAllBytes(chunkPath);
                out.write(chunkData);
                digest.update(chunkData);
            }

            // Verify checksum if provided
            if (upload.getChecksum() != null && !upload.getChecksum().isBlank()) {
                String actualChecksum = HexFormat.of().formatHex(digest.digest());
                if (!upload.getChecksum().equalsIgnoreCase(actualChecksum)) {
                    upload.setStatus("FAILED");
                    upload.setErrorMessage("Checksum mismatch: expected " + upload.getChecksum()
                            + " got " + actualChecksum);
                    uploadRepository.save(upload);
                    throw new IOException("Checksum verification failed");
                }
            }

        } catch (Exception e) {
            if (!"FAILED".equals(upload.getStatus())) {
                upload.setStatus("FAILED");
                upload.setErrorMessage("Assembly failed: " + e.getMessage());
                uploadRepository.save(upload);
            }
            throw new IOException("Failed to assemble chunks: " + e.getMessage(), e);
        }

        // Clean up individual chunk files
        for (ChunkedUploadChunk chunk : chunks) {
            try {
                Files.deleteIfExists(Paths.get(chunk.getStoragePath()));
            } catch (IOException ignored) {}
        }

        upload.setStatus("COMPLETED");
        upload.setCompletedAt(Instant.now());
        uploadRepository.save(upload);

        log.info("Chunked upload completed: id={} file={} size={}",
                uploadId, upload.getFilename(), upload.getTotalSize());

        return assembledFile;
    }

    /**
     * Get upload status including which chunks are missing.
     */
    public UploadStatus getStatus(UUID uploadId) {
        ChunkedUpload upload = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        List<ChunkedUploadChunk> received = chunkRepository
                .findByUploadIdOrderByChunkNumberAsc(uploadId);
        Set<Integer> receivedNumbers = received.stream()
                .map(ChunkedUploadChunk::getChunkNumber)
                .collect(Collectors.toSet());

        List<Integer> missingChunks = IntStream.range(0, upload.getTotalChunks())
                .filter(i -> !receivedNumbers.contains(i))
                .boxed()
                .toList();

        double progressPercent = upload.getTotalChunks() > 0
                ? Math.round(receivedNumbers.size() * 1000.0 / upload.getTotalChunks()) / 10.0
                : 0;

        return UploadStatus.builder()
                .uploadId(uploadId)
                .filename(upload.getFilename())
                .status(upload.getStatus())
                .totalChunks(upload.getTotalChunks())
                .receivedChunks(receivedNumbers.size())
                .missingChunks(missingChunks)
                .progressPercent(progressPercent)
                .totalSize(upload.getTotalSize())
                .chunkSize(upload.getChunkSize())
                .createdAt(upload.getCreatedAt())
                .expiresAt(upload.getExpiresAt())
                .errorMessage(upload.getErrorMessage())
                .build();
    }

    /**
     * Cancel/abort an upload — removes all chunks and marks as cancelled.
     */
    @Transactional
    public void cancelUpload(UUID uploadId) {
        ChunkedUpload upload = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found: " + uploadId));

        if ("COMPLETED".equals(upload.getStatus())) {
            throw new IllegalStateException("Cannot cancel a completed upload");
        }

        // Delete chunk files
        List<ChunkedUploadChunk> chunks = chunkRepository
                .findByUploadIdOrderByChunkNumberAsc(uploadId);
        for (ChunkedUploadChunk chunk : chunks) {
            try {
                Files.deleteIfExists(Paths.get(chunk.getStoragePath()));
            } catch (IOException ignored) {}
        }
        chunkRepository.deleteByUploadId(uploadId);

        // Delete upload directory
        try {
            Path uploadDir = Paths.get(chunkTempDir, uploadId.toString());
            if (Files.exists(uploadDir)) {
                Files.walkFileTree(uploadDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException ignored) {}

        upload.setStatus("CANCELLED");
        uploadRepository.save(upload);

        log.info("Chunked upload cancelled: id={} file={}", uploadId, upload.getFilename());
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UploadStatus {
        private UUID uploadId;
        private String filename;
        private String status;
        private int totalChunks;
        private int receivedChunks;
        private List<Integer> missingChunks;
        private double progressPercent;
        private long totalSize;
        private long chunkSize;
        private Instant createdAt;
        private Instant expiresAt;
        private String errorMessage;
    }
}
