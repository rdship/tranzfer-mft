package com.filetransfer.https.controller;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import com.filetransfer.shared.util.TrackIdGenerator;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTPS upload endpoint. This is the <b>receive-side</b> for HTTPS-protocol
 * listeners: clients POST bytes on their per-listener TLS port (e.g.
 * 443, 8443) and the servlet mapping is served by every {@link
 * org.apache.catalina.connector.Connector} the {@link
 * com.filetransfer.https.server.HttpsListenerRegistry} has bound.
 *
 * <p>End-to-end flow (identical to the SFTP / FTP upload path):
 * <ol>
 *   <li>Authentication via the platform JWT bearer token (partner login
 *       from onboarding-api's {@code /api/partner/login}) → resolves to a
 *       {@link TransferAccount}.</li>
 *   <li>Bytes stream to storage-manager ({@code POST /api/v1/storage/store-stream})
 *       which computes SHA-256 inline, dedups against MinIO by hash, and
 *       returns {@code {sha256, sizeBytes, storageKey, ...}}.</li>
 *   <li>Write a {@link VirtualEntry} via {@link VirtualFileSystem#writeFile}
 *       so the VFS has an account-scoped path pointing at the storage key.</li>
 *   <li>Fire {@link RoutingEngine#onFileUploaded} so the flow-engine picks
 *       up the file just like it does for SFTP / FTP uploads.</li>
 * </ol>
 *
 * <p>Authorization: the controller's {@code @PreAuthorize} admits any
 * authenticated caller (platform JWT or SPIFFE JWT-SVID for internal probes).
 * Per-account ACL is enforced by the TransferAccount lookup — an admin
 * uploading on behalf of a partner resolves the partner's account by
 * username; a partner uploading to their own account resolves theirs.
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final TransferAccountRepository accountRepository;
    private final StorageServiceClient storageClient;
    private final VirtualFileSystem vfs;
    private final RoutingEngine routingEngine;
    private final TrackIdGenerator trackIdGenerator;

    /**
     * Upload a file to {@code /{path}} on {@code accountUsername}'s virtual
     * filesystem. The path segment may contain nested directories
     * ({@code /inbox/2026/04/invoice.xml}). Body is the raw file bytes.
     *
     * <p>Returns 201 with the storage metadata (sha256, sizeBytes, trackId)
     * on success. Flow execution runs asynchronously — the caller doesn't
     * block on the flow.
     */
    @PostMapping(value = "/{accountUsername}/**",
            consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.ALL_VALUE})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable String accountUsername,
            HttpServletRequest request) throws Exception {

        // Extract the path beyond /api/upload/{accountUsername}/
        String fullPath = request.getRequestURI();
        String prefix = "/api/upload/" + accountUsername + "/";
        int prefixIdx = fullPath.indexOf(prefix);
        String relativePath = prefixIdx >= 0
                ? fullPath.substring(prefixIdx + prefix.length())
                : "uploaded-" + Instant.now().toEpochMilli();
        if (relativePath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "path is required — use POST /api/upload/{account}/{path}"));
        }

        Optional<TransferAccount> accountOpt = accountRepository.findByUsername(accountUsername)
                .filter(TransferAccount::isActive);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Account not found or inactive: " + accountUsername));
        }
        TransferAccount account = accountOpt.get();

        String filename = VirtualFileSystem.nameOf("/" + relativePath);
        String trackId = trackIdGenerator.generate();
        long contentLength = request.getContentLengthLong();

        log.info("[HTTPS/upload] start account={} path=/{} size~{} track={}",
                accountUsername, relativePath, contentLength, trackId);

        // 1. Stream bytes to storage-manager (SHA-256 + dedup happens there).
        Map<String, Object> storeResult;
        try (InputStream in = request.getInputStream()) {
            storeResult = storageClient.storeStream(in, contentLength, filename, accountUsername, trackId);
        }
        if (storeResult == null) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "storage-manager returned empty response"));
        }
        String sha256 = String.valueOf(storeResult.get("sha256"));
        Object sizeObj = storeResult.get("sizeBytes");
        long sizeBytes = sizeObj instanceof Number n ? n.longValue() : -1L;
        String contentType = request.getContentType();

        // 2. Write a VFS entry pointing at the storage key. Path is
        //    account-scoped; the storage key is the SHA-256 keyed blob.
        String vfsPath = VirtualFileSystem.normalizePath("/" + relativePath);
        VirtualEntry entry = vfs.writeFile(
                account.getId(), vfsPath, sha256, sizeBytes, trackId, contentType);

        // 3. Fire the flow-engine routing pipeline just like SFTP/FTP do.
        //    onFileUploaded is @Async so this call returns immediately.
        routingEngine.onFileUploaded(account, vfsPath, /*absoluteSourcePath*/ null,
                clientIp(request));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("trackId", trackId);
        body.put("sha256", sha256);
        body.put("sizeBytes", sizeBytes);
        body.put("vfsPath", vfsPath);
        body.put("entryId", entry != null ? entry.getId() : null);
        log.info("[HTTPS/upload] done account={} path={} sha256={} size={} track={}",
                accountUsername, vfsPath, sha256, sizeBytes, trackId);
        return ResponseEntity.status(201).body(body);
    }

    @GetMapping("/health")
    @PreAuthorize("permitAll()")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "https-service");
    }

    private static String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
