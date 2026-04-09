package com.filetransfer.storage.controller;

import com.filetransfer.shared.security.Roles;
import com.filetransfer.storage.engine.ParallelIOEngine;
import com.filetransfer.storage.entity.StorageObject;
import com.filetransfer.storage.lifecycle.StorageLifecycleManager;
import com.filetransfer.storage.registry.StorageLocationRegistry;
import com.filetransfer.storage.repository.StorageObjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1/storage") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class StorageController {

    private final ParallelIOEngine ioEngine;
    private final StorageLifecycleManager lifecycle;
    private final StorageObjectRepository objectRepo;

    /** Optional — present when Redis is configured. Null → routing disabled, single-instance only. */
    @Autowired(required = false)
    @Nullable
    private StorageLocationRegistry locationRegistry;

    @Value("${storage.hot.path:/data/storage/hot}")
    private String hotPath;

    /** Store a file — parallel striped write to HOT tier */
    @PostMapping("/store")
    public ResponseEntity<Map<String, Object>> store(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account) throws Exception {

        String filename = file.getOriginalFilename();
        Path dest = Paths.get(hotPath, account != null ? account : "default", filename);

        ParallelIOEngine.WriteResult result = ioEngine.write(
                file.getInputStream(), dest, file.getSize());

        // Check dedup
        Optional<StorageObject> existing = lifecycle.findDuplicate(result.getSha256());
        if (existing.isPresent()) {
            Files.deleteIfExists(dest); // Already have this file
            StorageObject dup = existing.get();
            dup.setAccessCount(dup.getAccessCount() + 1);
            dup.setLastAccessedAt(Instant.now());
            objectRepo.save(dup);
            return ResponseEntity.ok(Map.of(
                    "status", "DEDUPLICATED", "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : "",
                    "sha256", result.getSha256(), "savedBytes", result.getSizeBytes()));
        }

        StorageObject obj = StorageObject.builder()
                .trackId(trackId).filename(filename)
                .physicalPath(dest.toString()).logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT").sizeBytes(result.getSizeBytes())
                .sha256(result.getSha256()).accountUsername(account)
                .striped(result.isStriped()).stripeCount(result.getStripeCount())
                .contentType(file.getContentType())
                .build();
        objectRepo.save(obj);
        // Register that THIS instance holds the bytes — enables cross-replica routing
        if (locationRegistry != null) locationRegistry.register(result.getSha256());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "STORED", "tier", "HOT",
                "trackId", trackId != null ? trackId : "",
                "sha256", result.getSha256(),
                "sizeBytes", result.getSizeBytes(),
                "striped", result.isStriped(),
                "throughputMbps", Math.round(result.getThroughputMbps() * 10) / 10.0,
                "durationMs", result.getDurationMs()));
    }

    /** Retrieve a file — records access for AI tiering decisions */
    @GetMapping("/retrieve/{trackId}")
    public ResponseEntity<byte[]> retrieve(@PathVariable String trackId) throws Exception {
        StorageObject obj = objectRepo.findByTrackIdAndDeletedFalse(trackId)
                .orElseThrow(() -> new RuntimeException("File not found: " + trackId));

        // Record access (AI uses this for tiering decisions)
        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        ParallelIOEngine.ReadResult result = ioEngine.read(Paths.get(obj.getPhysicalPath()));

        return ResponseEntity.ok()
                .contentType(obj.getContentType() != null ? MediaType.parseMediaType(obj.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + obj.getFilename() + "\"")
                .header("X-Track-Id", trackId)
                .header("X-Storage-Tier", obj.getTier())
                .header("X-SHA256", obj.getSha256())
                .body(result.getData());
    }

    /** List files by account or tier */
    @GetMapping("/objects")
    public List<StorageObject> listObjects(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String tier) {
        if (account != null) return objectRepo.findByAccountUsernameAndDeletedFalseOrderByCreatedAtDesc(account);
        if (tier != null) return objectRepo.findByTierAndDeletedFalse(tier);
        return objectRepo.findAll();
    }

    /** Storage metrics and tier distribution */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() { return lifecycle.getStorageMetrics(); }

    /** Recent lifecycle actions */
    @GetMapping("/lifecycle/actions")
    public List<StorageLifecycleManager.LifecycleAction> lifecycleActions() { return lifecycle.getRecentActions(); }

    /** Trigger manual tiering cycle */
    @PostMapping("/lifecycle/tier")
    public Map<String, String> triggerTiering() {
        lifecycle.runTieringCycle();
        return Map.of("status", "completed");
    }

    /** Trigger manual backup */
    @PostMapping("/lifecycle/backup")
    public Map<String, String> triggerBackup() {
        lifecycle.runBackup();
        return Map.of("status", "completed");
    }

    /** Store raw bytes (used by VFS write channels — no multipart overhead). */
    @PostMapping("/store-bytes")
    public ResponseEntity<Map<String, Object>> storeBytes(
            @RequestParam String filename,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account,
            @org.springframework.web.bind.annotation.RequestBody byte[] fileBytes) throws Exception {

        Path dest = Paths.get(hotPath, account != null ? account : "default", filename);
        java.io.InputStream input = new java.io.ByteArrayInputStream(fileBytes);

        ParallelIOEngine.WriteResult result = ioEngine.write(input, dest, fileBytes.length);

        // Dedup check
        Optional<StorageObject> existing = lifecycle.findDuplicate(result.getSha256());
        if (existing.isPresent()) {
            Files.deleteIfExists(dest);
            StorageObject dup = existing.get();
            dup.setAccessCount(dup.getAccessCount() + 1);
            dup.setLastAccessedAt(java.time.Instant.now());
            objectRepo.save(dup);
            return ResponseEntity.ok(Map.of(
                    "status", "DEDUPLICATED", "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : "",
                    "sha256", result.getSha256(), "savedBytes", result.getSizeBytes()));
        }

        StorageObject obj = StorageObject.builder()
                .trackId(trackId).filename(filename)
                .physicalPath(dest.toString())
                .logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT").sizeBytes(result.getSizeBytes())
                .sha256(result.getSha256()).accountUsername(account)
                .striped(result.isStriped()).stripeCount(result.getStripeCount())
                .build();
        objectRepo.save(obj);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "STORED", "tier", "HOT",
                "trackId", trackId != null ? trackId : "",
                "sha256", result.getSha256(),
                "sizeBytes", result.getSizeBytes(),
                "striped", result.isStriped()));
    }

    /**
     * Retrieve file by SHA-256 key (used by VFS read channels).
     *
     * <p><b>Multi-replica routing:</b> if the file was written to a different storage-manager
     * instance, the location registry returns that instance's URL and this pod proxies the
     * request. The caller gets the bytes transparently regardless of which pod stored them.
     *
     * <p>Fallback: if Redis is unavailable or no location entry exists, attempt local read
     * (works correctly in single-instance deployments and shared-storage deployments).
     */
    @GetMapping("/retrieve-by-key/{sha256}")
    public ResponseEntity<byte[]> retrieveByKey(@PathVariable String sha256) throws Exception {
        // ── Cross-replica routing check ───────────────────────────────────────
        if (locationRegistry != null && !locationRegistry.isLocal(sha256)) {
            byte[] proxied = locationRegistry.proxyRetrieve(sha256);
            if (proxied != null) {
                return ResponseEntity.ok()
                        .header("X-SHA256", sha256)
                        .header("X-Routed-From", locationRegistry.getOwnerUrl(sha256))
                        .body(proxied);
            }
            // Proxy failed — fall through to local attempt (defensive)
        }

        // ── Local read ────────────────────────────────────────────────────────
        StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
                .orElseThrow(() -> new RuntimeException("File not found for key: " + sha256));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        ParallelIOEngine.ReadResult result = ioEngine.read(Paths.get(obj.getPhysicalPath()));

        return ResponseEntity.ok()
                .contentType(obj.getContentType() != null
                        ? MediaType.parseMediaType(obj.getContentType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Storage-Tier", obj.getTier())
                .header("X-SHA256", obj.getSha256())
                .body(result.getData());
    }

    /** Get reference count for a storage key (CAS garbage collection). */
    @GetMapping("/ref-count/{sha256}")
    public Map<String, Object> refCount(@PathVariable String sha256) {
        Optional<StorageObject> obj = objectRepo.findBySha256AndDeletedFalse(sha256);
        return Map.of("sha256", sha256, "exists", obj.isPresent(),
                "physicalSize", obj.map(StorageObject::getSizeBytes).orElse(0L));
    }

    /** Soft-delete a CAS object by SHA-256 key (used by CasOrphanReaper GC). */
    @DeleteMapping("/objects/{sha256}")
    public ResponseEntity<Map<String, Object>> deleteBySha256(@PathVariable String sha256) {
        Optional<StorageObject> opt = objectRepo.findBySha256AndDeletedFalse(sha256);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        StorageObject obj = opt.get();
        obj.setDeleted(true);
        objectRepo.save(obj);
        return ResponseEntity.ok(Map.of(
                "status", "DELETED", "sha256", sha256,
                "sizeBytes", obj.getSizeBytes()));
    }

    /**
     * Stream a CAS object by SHA-256 key.
     *
     * <p>Returns a truly streaming response via {@link StreamingResponseBody} — the file
     * is piped from storage-manager's local disk directly to the HTTP response without
     * loading it into the JVM heap. Callers (flow step handlers, forwarder-service, etc.)
     * can pipe this response into their own transform without an intermediate byte buffer.
     */
    @GetMapping("/stream/{sha256}")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String sha256) throws Exception {
        StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
                .orElseThrow(() -> new RuntimeException("File not found for key: " + sha256));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        Path filePath = Paths.get(obj.getPhysicalPath());
        StreamingResponseBody body = out -> {
            try (java.io.InputStream in = Files.newInputStream(filePath)) {
                in.transferTo(out);
            }
        };

        return ResponseEntity.ok()
                .contentType(obj.getContentType() != null
                        ? org.springframework.http.MediaType.parseMediaType(obj.getContentType())
                        : org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Length", String.valueOf(obj.getSizeBytes()))
                .header("X-SHA256", sha256)
                .header("X-Storage-Tier", obj.getTier())
                .body(body);
    }

    /**
     * Accept a raw octet-stream and store it — truly streaming, no multipart overhead.
     *
     * <p>The request body is piped directly from the network socket into the
     * {@link ParallelIOEngine} write path. No intermediate byte buffer is allocated
     * beyond the engine's internal 64 KB read buffer. {@code Content-Length} is optional;
     * if absent the engine streams until EOF.
     *
     * <p>Query params: {@code filename} (required), {@code trackId} (optional), {@code account} (optional).
     */
    @PostMapping(value = "/store-stream",
                 consumes = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, Object>> storeStream(
            HttpServletRequest request,
            @RequestParam String filename,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account) throws Exception {

        long contentLength = request.getContentLengthLong(); // -1 if unknown — that's fine
        Path dest = Paths.get(hotPath, account != null ? account : "default", filename);

        ParallelIOEngine.WriteResult result = ioEngine.write(
                request.getInputStream(), dest, contentLength);

        // Dedup check
        Optional<StorageObject> existing = lifecycle.findDuplicate(result.getSha256());
        if (existing.isPresent()) {
            Files.deleteIfExists(dest);
            StorageObject dup = existing.get();
            dup.setAccessCount(dup.getAccessCount() + 1);
            dup.setLastAccessedAt(Instant.now());
            objectRepo.save(dup);
            return ResponseEntity.ok(Map.of(
                    "status", "DEDUPLICATED",
                    "sha256", result.getSha256(),
                    "sizeBytes", result.getSizeBytes(),
                    "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : ""));
        }

        StorageObject obj = StorageObject.builder()
                .trackId(trackId).filename(filename)
                .physicalPath(dest.toString())
                .logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT").sizeBytes(result.getSizeBytes())
                .sha256(result.getSha256()).accountUsername(account)
                .striped(result.isStriped()).stripeCount(result.getStripeCount())
                .build();
        objectRepo.save(obj);

        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(Map.of(
                "status", "STORED", "tier", "HOT",
                "sha256", result.getSha256(),
                "sizeBytes", result.getSizeBytes(),
                "trackId", trackId != null ? trackId : "",
                "throughputMbps", Math.round(result.getThroughputMbps() * 10) / 10.0,
                "durationMs", result.getDurationMs()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>(lifecycle.getStorageMetrics());
        h.put("status", "UP");
        h.put("service", "storage-manager");
        h.put("features", List.of("parallel-io", "tiered-storage", "deduplication",
                "incremental-backup", "ai-lifecycle", "predictive-prestage"));
        return h;
    }
}
