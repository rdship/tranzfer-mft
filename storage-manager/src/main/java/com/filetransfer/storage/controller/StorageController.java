package com.filetransfer.storage.controller;

import com.filetransfer.shared.security.Roles;
import com.filetransfer.storage.engine.ParallelIOEngine;
import com.filetransfer.storage.entity.StorageObject;
import com.filetransfer.storage.lifecycle.StorageLifecycleManager;
import com.filetransfer.storage.repository.StorageObjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1/storage") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class StorageController {

    private final ParallelIOEngine ioEngine;
    private final StorageLifecycleManager lifecycle;
    private final StorageObjectRepository objectRepo;

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

    /** Retrieve file by SHA-256 key (used by VFS read channels). */
    @GetMapping("/retrieve-by-key/{sha256}")
    public ResponseEntity<byte[]> retrieveByKey(@PathVariable String sha256) throws Exception {
        StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
                .orElseThrow(() -> new RuntimeException("File not found for key: " + sha256));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(java.time.Instant.now());
        objectRepo.save(obj);

        ParallelIOEngine.ReadResult result = ioEngine.read(Paths.get(obj.getPhysicalPath()));

        return ResponseEntity.ok()
                .contentType(obj.getContentType() != null ? MediaType.parseMediaType(obj.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
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
