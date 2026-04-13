package com.filetransfer.ftpweb.controller;

import com.filetransfer.ftpweb.service.ChunkedUploadService;
import com.filetransfer.shared.entity.vfs.ChunkedUpload;
import com.filetransfer.shared.entity.vfs.ChunkedUploadChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

/**
 * REST API for chunked file uploads.
 *
 * <p>Supports large files via chunked transfer with resume capability:
 * <ol>
 *   <li>POST /init — start a new chunked upload</li>
 *   <li>PUT /{uploadId}/chunks/{chunkNumber} — upload individual chunks (any order)</li>
 *   <li>POST /{uploadId}/complete — assemble all chunks into final file</li>
 *   <li>GET /{uploadId}/status — check progress, find missing chunks for resume</li>
 *   <li>DELETE /{uploadId} — cancel/abort upload</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/transfers/chunked")
@RequiredArgsConstructor
public class ChunkedUploadController {

    private final ChunkedUploadService uploadService;

    /**
     * Initialize a new chunked upload.
     *
     * @param body JSON with: filename (required), totalSize, totalChunks, checksum (optional), contentType (optional)
     */
    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initUpload(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {

        String filename = (String) body.get("filename");
        if (filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filename is required"));
        }

        long totalSize = body.containsKey("totalSize")
                ? ((Number) body.get("totalSize")).longValue() : 0;
        int totalChunks = body.containsKey("totalChunks")
                ? ((Number) body.get("totalChunks")).intValue() : 0;
        String checksum = (String) body.get("checksum");
        String contentType = (String) body.get("contentType");

        if (totalChunks <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "totalChunks must be positive"));
        }

        ChunkedUpload upload = uploadService.initUpload(
                filename, totalSize, totalChunks, checksum, email, contentType);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "uploadId", upload.getId().toString(),
                "chunkSize", upload.getChunkSize(),
                "uploadUrl", "/api/transfers/chunked/" + upload.getId() + "/chunks/{chunkNumber}",
                "expiresAt", upload.getExpiresAt().toString()
        ));
    }

    /**
     * Upload a single chunk. Chunks can be uploaded in any order and in parallel.
     * Idempotent — uploading the same chunk number again is a no-op.
     */
    @PutMapping("/{uploadId}/chunks/{chunkNumber}")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @PathVariable UUID uploadId,
            @PathVariable int chunkNumber,
            @RequestBody byte[] data) throws Exception {

        ChunkedUploadChunk chunk = uploadService.receiveChunk(
                uploadId, chunkNumber,
                new java.io.ByteArrayInputStream(data), data.length);

        return ResponseEntity.ok(Map.of(
                "chunkNumber", chunk.getChunkNumber(),
                "size", chunk.getSize(),
                "checksum", chunk.getChecksum() != null ? chunk.getChecksum() : ""
        ));
    }

    /**
     * Complete the chunked upload — assembles all chunks into the final file.
     * Fails if any chunks are missing. Verifies checksum if provided during init.
     */
    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<Map<String, Object>> completeUpload(
            @PathVariable UUID uploadId) throws Exception {

        Path assembledFile = uploadService.completeUpload(uploadId);
        ChunkedUploadService.UploadStatus status = uploadService.getStatus(uploadId);

        return ResponseEntity.ok(Map.of(
                "status", "COMPLETED",
                "filename", status.getFilename(),
                "totalSize", status.getTotalSize(),
                "assembledPath", assembledFile.toString()
        ));
    }

    /**
     * Check upload progress. Returns which chunks are received and which are missing.
     * Use this for resume after connection failure.
     */
    @GetMapping("/{uploadId}/status")
    public ResponseEntity<ChunkedUploadService.UploadStatus> getStatus(
            @PathVariable UUID uploadId) {
        return ResponseEntity.ok(uploadService.getStatus(uploadId));
    }

    /**
     * Cancel/abort an upload. Removes all chunks and marks as cancelled.
     */
    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Map<String, String>> cancelUpload(@PathVariable UUID uploadId) {
        uploadService.cancelUpload(uploadId);
        return ResponseEntity.ok(Map.of("status", "CANCELLED", "uploadId", uploadId.toString()));
    }
}
