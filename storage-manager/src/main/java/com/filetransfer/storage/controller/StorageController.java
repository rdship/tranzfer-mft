package com.filetransfer.storage.controller;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.security.Roles;
import com.filetransfer.storage.backend.StorageBackend;
import com.filetransfer.storage.entity.StorageObject;
import com.filetransfer.storage.lifecycle.StorageLifecycleManager;
import com.filetransfer.storage.repository.StorageObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Storage REST API.
 *
 * <p>All byte I/O is delegated to the injected {@link StorageBackend}:
 * <ul>
 *   <li>{@code storage.backend=local} → {@link com.filetransfer.storage.backend.LocalStorageBackend}
 *       (filesystem, single-instance or NFS)
 *   <li>{@code storage.backend=s3}    → {@link com.filetransfer.storage.backend.S3StorageBackend}
 *       (MinIO internal or AWS S3 via DMZ proxy — recommended for multi-replica)
 * </ul>
 */
@Slf4j
@RestController @RequestMapping("/api/v1/storage") @RequiredArgsConstructor
// R122: accept ROLE_INTERNAL (SPIFFE JWT-SVID from S2S callers) in addition
// to ADMIN/OPERATOR. Flow-engine services (sftp / ftp / ftp-web / forwarder
// / onboarding-api) call store-stream, retrieve, etc. with their SPIFFE
// identity; PlatformJwtAuthFilter Path 1 maps that to ROLE_INTERNAL.
// Without this, R120's verified SPIFFE auth cleared the authentication
// layer but hit 403 AccessDenied at the authorization layer.
@PreAuthorize(Roles.INTERNAL_OR_OPERATOR)
public class StorageController {

    /** Injected implementation is selected by storage.backend property. */
    private final StorageBackend storageBackend;
    private final StorageLifecycleManager lifecycle;
    private final StorageObjectRepository objectRepo;

    /** Audit trail for all storage operations (PCI-DSS 10.x compliance). */
    @Autowired(required = false)
    @Nullable
    private AuditService auditService;

    /**
     * Store a file via the configured {@link StorageBackend}.
     * Works identically for local and S3 backends — the backend handles dedup,
     * physical path, and location registry transparently.
     */
    @PostMapping("/store")
    public ResponseEntity<Map<String, Object>> store(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String account) throws Exception {

        String filename = file.getOriginalFilename();

        // Dedup check: if sha256 already exists in DB, return existing record
        // (backend.write() also does dedup internally, but DB check is faster)
        StorageBackend.WriteResult result = storageBackend.write(
                file.getInputStream(), file.getSize(), filename);

        if (result.deduplicated()) {
            StorageObject dup = objectRepo.findBySha256AndDeletedFalse(result.sha256()).orElse(null);
            if (dup != null) {
                dup.setAccessCount(dup.getAccessCount() + 1);
                dup.setLastAccessedAt(Instant.now());
                objectRepo.save(dup);
                return ResponseEntity.ok(Map.of(
                        "status", "DEDUPLICATED",
                        "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : "",
                        "sha256", result.sha256(),
                        "savedBytes", result.sizeBytes(),
                        "backend", storageBackend.type()));
            }
        }

        // storageKey: sha256 for S3 backend, sha256 for new local CAS, legacy path for old records
        StorageObject obj = StorageObject.builder()
                .trackId(trackId)
                .filename(filename)
                .physicalPath(result.storageKey())   // sha256 (S3) or {hotPath}/{sha256} (local)
                .logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT")
                .sizeBytes(result.sizeBytes())
                .sha256(result.sha256())
                .accountUsername(account)
                .contentType(file.getContentType())
                .build();
        objectRepo.save(obj);

        log.info("[Store] {} bytes → backend={} sha256={} track={} dedup={}",
                result.sizeBytes(), storageBackend.type(), result.sha256().substring(0, 12),
                trackId, result.deduplicated());

        // Audit trail: storage write operation
        if (auditService != null && trackId != null) {
            auditService.logAction("storage-manager", "STORAGE_WRITE", true, trackId,
                    Map.of("sha256", result.sha256(), "sizeBytes", result.sizeBytes(),
                            "backend", storageBackend.type(), "deduplicated", result.deduplicated()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "STORED",
                "tier", "HOT",
                "backend", storageBackend.type(),
                "trackId", trackId != null ? trackId : "",
                "sha256", result.sha256(),
                "sizeBytes", result.sizeBytes(),
                "throughputMbps", Math.round(result.throughputMbps() * 10) / 10.0,
                "durationMs", result.durationMs()));
    }

    /** Retrieve a file by trackId via streaming — no heap load. */
    @GetMapping("/retrieve/{trackId}")
    public void retrieve(@PathVariable String trackId, HttpServletResponse response) throws Exception {
        // R125: a completed flow has multiple storage objects for the same
        // trackId (input snapshot + per-step outputs). Resolve to the newest
        // one — that is what the UI "Download current" button expects. The
        // old unique-expecting finder threw IncorrectResultSizeDataAccessException
        // on any 2+ rows match, which the platform exception handler mapped
        // to a 500 "An unexpected error occurred".
        StorageObject obj = objectRepo.findFirstByTrackIdAndDeletedFalseOrderByCreatedAtDesc(trackId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No storage object for trackId=" + trackId));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        String storageKey = obj.getSha256() != null ? obj.getSha256() : obj.getPhysicalPath();
        if (storageKey == null || storageKey.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Storage object " + trackId + " has no backing key (sha256/physicalPath both null)");
        }

        // Set headers before opening the backend stream so a subsequent
        // readTo() failure is still mapped to a proper HTTP error by the
        // PlatformExceptionHandler (we have not written any body yet).
        response.setContentType(obj.getContentType() != null
                ? obj.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (obj.getSizeBytes() > 0) response.setContentLengthLong(obj.getSizeBytes());
        response.setHeader("Content-Disposition", "attachment; filename=\"" + obj.getFilename() + "\"");
        response.setHeader("X-Track-Id", trackId);
        response.setHeader("X-Storage-Tier", obj.getTier());
        response.setHeader("X-SHA256", obj.getSha256() != null ? obj.getSha256() : "");
        response.setHeader("X-Storage-Backend", storageBackend.type());

        try {
            storageBackend.readTo(storageKey, response.getOutputStream());
        } catch (java.nio.file.NoSuchFileException nsf) {
            // DB row exists but the backing object was evicted / never written.
            // Common in virtual-testing mode when flow completed but MinIO was skipped.
            log.warn("[retrieve] storage row present but backing object missing: trackId={} key={}", trackId, storageKey);
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "File content not available in storage backend (key=" + storageKey + ")");
        }
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

        java.io.InputStream input = new java.io.ByteArrayInputStream(fileBytes);
        StorageBackend.WriteResult result = storageBackend.write(input, fileBytes.length, filename);

        if (result.deduplicated()) {
            StorageObject dup = objectRepo.findBySha256AndDeletedFalse(result.sha256()).orElse(null);
            if (dup != null) {
                dup.setAccessCount(dup.getAccessCount() + 1);
                dup.setLastAccessedAt(Instant.now());
                objectRepo.save(dup);
                return ResponseEntity.ok(Map.of(
                        "status", "DEDUPLICATED",
                        "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : "",
                        "sha256", result.sha256(), "savedBytes", result.sizeBytes()));
            }
        }

        StorageObject obj = StorageObject.builder()
                .trackId(trackId).filename(filename)
                .physicalPath(result.storageKey())
                .logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT").sizeBytes(result.sizeBytes())
                .sha256(result.sha256()).accountUsername(account)
                .build();
        objectRepo.save(obj);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "STORED", "tier", "HOT",
                "backend", storageBackend.type(),
                "trackId", trackId != null ? trackId : "",
                "sha256", result.sha256(),
                "sizeBytes", result.sizeBytes()));
    }

    /**
     * Retrieve file by SHA-256 key (used by VFS read channels).
     *
     * <p>Multi-replica correctness relies on the configured {@link StorageBackend}:
     * S3/MinIO backends are globally shared and all pods see identical bytes; local
     * backends are correct for single-instance / NFS / ReadWriteMany PVC topologies.
     * The Redis location-registry routing was retired at R134AG — local-only
     * multi-replica deployments should migrate to the S3 backend.
     */
    @GetMapping("/retrieve-by-key/{sha256}")
    public void retrieveByKey(@PathVariable String sha256, HttpServletResponse response) throws Exception {
        // ── Backend streaming read (works for both local and S3) ──────────────
        StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No storage object for sha256=" + sha256));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        response.setContentType(obj.getContentType() != null
                ? obj.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (obj.getSizeBytes() > 0) response.setContentLengthLong(obj.getSizeBytes());
        response.setHeader("X-Storage-Tier", obj.getTier());
        response.setHeader("X-SHA256", obj.getSha256() != null ? obj.getSha256() : "");
        response.setHeader("X-Storage-Backend", storageBackend.type());

        try {
            storageBackend.readTo(sha256, response.getOutputStream());
        } catch (java.nio.file.NoSuchFileException nsf) {
            log.warn("[retrieve-by-key] storage row present but backing object missing: sha256={}", sha256);
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "File content not available in storage backend (key=" + sha256 + ")");
        }
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
     * Stream a CAS object by SHA-256 key with true zero-copy streaming.
     *
     * <p>Pipes file content directly from the storage backend to the HTTP response
     * output stream using {@link StorageBackend#readTo(String, java.io.OutputStream)}.
     * No JVM heap allocation for the file payload — a 1 GB file uses only the
     * backend's internal transfer buffer (e.g. kernel sendfile for local, 256 KB for S3).
     */
    @GetMapping("/stream/{sha256}")
    public void stream(@PathVariable String sha256, HttpServletResponse response) throws Exception {
        StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No storage object for sha256=" + sha256));

        obj.setAccessCount(obj.getAccessCount() + 1);
        obj.setLastAccessedAt(Instant.now());
        objectRepo.save(obj);

        // Set headers before streaming — once bytes flow, headers are committed
        response.setContentType(obj.getContentType() != null
                ? obj.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (obj.getSizeBytes() > 0) {
            response.setContentLengthLong(obj.getSizeBytes());
        }
        response.setHeader("X-SHA256", sha256);
        response.setHeader("X-Storage-Tier", obj.getTier());
        response.setHeader("X-Storage-Backend", storageBackend.type());

        // Stream directly from backend to response — no heap buffering
        String storageKey = obj.getSha256() != null ? obj.getSha256() : obj.getPhysicalPath();
        try {
            storageBackend.readTo(storageKey, response.getOutputStream());
        } catch (java.nio.file.NoSuchFileException nsf) {
            log.warn("[stream] storage row present but backing object missing: sha256={}", sha256);
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "File content not available in storage backend (key=" + storageKey + ")");
        }
        response.flushBuffer();
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

        long contentLength = request.getContentLengthLong(); // -1 if unknown — backend handles it
        StorageBackend.WriteResult result = storageBackend.write(
                request.getInputStream(), contentLength, filename);

        if (result.deduplicated()) {
            StorageObject dup = objectRepo.findBySha256AndDeletedFalse(result.sha256()).orElse(null);
            if (dup != null) {
                dup.setAccessCount(dup.getAccessCount() + 1);
                dup.setLastAccessedAt(Instant.now());
                objectRepo.save(dup);
                return ResponseEntity.ok(Map.of(
                        "status", "DEDUPLICATED",
                        "sha256", result.sha256(),
                        "sizeBytes", result.sizeBytes(),
                        "existingTrackId", dup.getTrackId() != null ? dup.getTrackId() : ""));
            }
        }

        StorageObject obj = StorageObject.builder()
                .trackId(trackId).filename(filename)
                .physicalPath(result.storageKey())
                .logicalPath("/" + (account != null ? account : "default") + "/" + filename)
                .tier("HOT").sizeBytes(result.sizeBytes())
                .sha256(result.sha256()).accountUsername(account)
                .build();
        objectRepo.save(obj);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "STORED", "tier", "HOT",
                "backend", storageBackend.type(),
                "sha256", result.sha256(),
                "sizeBytes", result.sizeBytes(),
                "trackId", trackId != null ? trackId : "",
                "throughputMbps", Math.round(result.throughputMbps() * 10) / 10.0,
                "durationMs", result.durationMs()));
    }

    /** Encryption-at-rest status — visible from admin UI */
    @GetMapping("/encryption-status")
    public Map<String, Object> encryptionStatus() {
        boolean encrypted = storageBackend.type().contains("encrypted");
        return Map.of(
                "encryptionAtRest", encrypted,
                "backend", storageBackend.type(),
                "algorithm", encrypted ? "AES-256-GCM" : "NONE",
                "keySource", encrypted ? "Vault KMS / env var" : "N/A");
    }

    /**
     * Register an existing CAS object with a trackId — no re-upload needed.
     * Used by VIRTUAL-mode routing: file is already in CAS, just needs metadata.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestParam String trackId,
            @RequestParam String sha256,
            @RequestParam String filename,
            @RequestParam(required = false) String account,
            @RequestParam(required = false, defaultValue = "0") long sizeBytes) {
        // Check if already registered — use the ordered finder since a
        // multi-step flow may have stored several objects for this trackId.
        if (objectRepo.findFirstByTrackIdAndDeletedFalseOrderByCreatedAtDesc(trackId).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "ALREADY_REGISTERED", "trackId", trackId));
        }
        StorageObject obj = StorageObject.builder()
                .trackId(trackId)
                .filename(filename)
                .sha256(sha256)
                .physicalPath(sha256) // CAS key = SHA-256
                .sizeBytes(sizeBytes)
                .accountUsername(account)
                .tier("HOT")
                .build();
        objectRepo.save(obj);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(Map.of("status", "REGISTERED", "trackId", trackId, "sha256", sha256));
    }

    // R131: /health must be reachable by the UI's anonymous liveness probe
    // (ServiceContext.detectServices fans these out without credentials).
    // Class-level @PreAuthorize(INTERNAL_OR_OPERATOR) would otherwise 403
    // the unauthenticated probe — admin UI gets no "storage up" signal.
    @GetMapping("/health")
    @PreAuthorize("permitAll()")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>(lifecycle.getStorageMetrics());
        h.put("status", "UP");
        h.put("service", "storage-manager");
        h.put("backend", storageBackend.type());
        h.put("features", List.of("parallel-io", "tiered-storage", "deduplication",
                "incremental-backup", "ai-lifecycle", "predictive-prestage",
                "s3-compatible-backend", "streaming-reads", "zero-copy", "wail"));
        return h;
    }

    /** DRP engine stats: I/O lanes, SEDA stages, write intents. */
    @GetMapping("/drp-stats")
    public Map<String, Object> drpStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        if (ioLaneManager != null) stats.put("ioLanes", ioLaneManager.getStats());
        if (flowStageManager != null) stats.put("sedaStages", flowStageManager.getStats());
        return stats;
    }

    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.flow.IOLaneManager ioLaneManager;

    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.flow.FlowStageManager flowStageManager;
}
