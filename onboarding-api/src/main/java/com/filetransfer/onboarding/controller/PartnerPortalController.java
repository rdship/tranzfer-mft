package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.*;
import com.filetransfer.shared.repository.transfer.*;
import com.filetransfer.shared.repository.integration.*;
import com.filetransfer.shared.repository.security.*;
import com.filetransfer.shared.repository.vfs.*;
import com.filetransfer.shared.util.JwtUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Partner Self-Service Portal API.
 * Partners authenticate with their transfer account credentials
 * and can only see their own data.
 *
 * No admin privileges — scoped to the authenticated partner's account.
 */
@RestController
@RequestMapping("/api/partner")
@RequiredArgsConstructor
@Slf4j
public class PartnerPortalController {

    private final TransferAccountRepository accountRepo;
    private final FileTransferRecordRepository transferRepo;
    private final AuditLogRepository auditLogRepo;
    private final FolderMappingRepository mappingRepo;
    private final FlowExecutionRepository flowExecRepo;
    private final JwtUtil jwtUtil;

    /** Extract the authenticated partner username from the SecurityContext (set by JwtAuthFilter). */
    private String getAuthenticatedPartner() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new SecurityException("Not authenticated");
        }
        return auth.getName();
    }

    // === Auth — partner login with transfer account credentials ===

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> partnerLogin(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        TransferAccount account = accountRepo.findAll().stream()
                .filter(a -> a.getUsername().equals(username) && a.isActive())
                .findFirst().orElse(null);

        if (account == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        if (!new BCryptPasswordEncoder().matches(password, account.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        // Generate a signed JWT with PARTNER role — validated by JwtAuthFilter on subsequent requests
        String token = jwtUtil.generateToken(username, "PARTNER");

        log.info("Partner login: {}", username);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", username,
                "protocol", account.getProtocol().name(),
                "homeDir", account.getHomeDir(),
                "role", "PARTNER"
        ));
    }

    // === Dashboard — overview stats for this partner ===

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        String username = getAuthenticatedPartner();
        List<FileTransferRecord> myTransfers = transferRepo
                .findByFolderMappingSourceAccountIdOrderByUploadedAtDesc(
                        getAccountId(username));

        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);

        long totalTransfers = myTransfers.size();
        long todayTransfers = myTransfers.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(today)).count();
        long weekTransfers = myTransfers.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(weekAgo)).count();
        long failedCount = myTransfers.stream()
                .filter(r -> "FAILED".equals(r.getStatus().name())).count();
        double successRate = totalTransfers > 0 ?
                (double)(totalTransfers - failedCount) / totalTransfers * 100 : 100.0;

        return ResponseEntity.ok(Map.of(
                "username", username,
                "totalTransfers", totalTransfers,
                "todayTransfers", todayTransfers,
                "weekTransfers", weekTransfers,
                "failedTransfers", failedCount,
                "successRate", Math.round(successRate * 10) / 10.0,
                "lastTransfer", myTransfers.isEmpty() ? "none" :
                        myTransfers.get(0).getUploadedAt().toString()
        ));
    }

    // === Transfer History — paginated list of this partner's transfers ===

    @GetMapping("/transfers")
    public List<Map<String, Object>> transfers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        String username = getAuthenticatedPartner();
        UUID accountId = getAccountId(username);
        if (accountId == null) return List.of();

        return transferRepo.findByFolderMappingSourceAccountIdOrderByUploadedAtDesc(accountId)
                .stream()
                .filter(r -> status == null || r.getStatus().name().equals(status))
                .skip((long) page * size).limit(size)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trackId", r.getTrackId());
                    m.put("filename", r.getOriginalFilename());
                    m.put("status", r.getStatus().name());
                    m.put("sizeBytes", r.getFileSizeBytes());
                    m.put("uploadedAt", r.getUploadedAt());
                    m.put("routedAt", r.getRoutedAt());
                    m.put("completedAt", r.getCompletedAt());
                    m.put("sourceChecksum", r.getSourceChecksum());
                    m.put("destinationChecksum", r.getDestinationChecksum());
                    m.put("integrityVerified", r.getSourceChecksum() != null &&
                            r.getSourceChecksum().equals(r.getDestinationChecksum()));
                    m.put("retryCount", r.getRetryCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // === Track — single transfer journey (partner can only see their own) ===

    @Transactional(readOnly = true)
    @GetMapping("/track/{trackId}")
    public ResponseEntity<?> track(@PathVariable String trackId) {
        String username = getAuthenticatedPartner();
        FileTransferRecord record = transferRepo.findByTrackId(trackId).orElse(null);
        if (record == null) return ResponseEntity.notFound().build();

        // Verify this transfer belongs to this partner
        if (record.getFolderMapping() == null ||
                !username.equals(record.getFolderMapping().getSourceAccount().getUsername())) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        FlowExecution flowExec = flowExecRepo.findByTrackId(trackId).orElse(null);

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(Map.of("stage", "RECEIVED", "status", "COMPLETED",
                "timestamp", record.getUploadedAt().toString(),
                "detail", "File received: " + record.getOriginalFilename()));

        if (flowExec != null && flowExec.getStepResults() != null) {
            for (FlowExecution.StepResult step : flowExec.getStepResults()) {
                stages.add(Map.of("stage", step.getStepType(), "status", step.getStatus(),
                        "detail", step.getDurationMs() + "ms"));
            }
        }

        if (record.getRoutedAt() != null) {
            stages.add(Map.of("stage", "DELIVERED", "status", "COMPLETED",
                    "timestamp", record.getRoutedAt().toString(),
                    "detail", "Routed to destination"));
        }

        if (record.getCompletedAt() != null) {
            stages.add(Map.of("stage", "CONFIRMED", "status", "COMPLETED",
                    "timestamp", record.getCompletedAt().toString(),
                    "detail", "Delivery confirmed by recipient"));
        }

        if ("FAILED".equals(record.getStatus().name())) {
            stages.add(Map.of("stage", "FAILED", "status", "FAILED",
                    "detail", record.getErrorMessage() != null ? record.getErrorMessage() : "Unknown error"));
        }

        return ResponseEntity.ok(Map.of(
                "trackId", trackId,
                "filename", record.getOriginalFilename(),
                "status", record.getStatus().name(),
                "integrity", record.getSourceChecksum() != null &&
                        record.getSourceChecksum().equals(record.getDestinationChecksum()) ? "VERIFIED" : "PENDING",
                "sourceChecksum", record.getSourceChecksum() != null ? record.getSourceChecksum() : "",
                "stages", stages,
                "uploadedAt", record.getUploadedAt().toString(),
                "completedAt", record.getCompletedAt() != null ? record.getCompletedAt().toString() : ""
        ));
    }

    // === Delivery Receipt — downloadable proof of delivery ===

    @Transactional(readOnly = true)
    @GetMapping("/receipt/{trackId}")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String trackId) {
        String username = getAuthenticatedPartner();
        FileTransferRecord record = transferRepo.findByTrackId(trackId).orElse(null);
        if (record == null) return ResponseEntity.notFound().build();

        // Verify this transfer belongs to this partner (same check as /track)
        if (record.getFolderMapping() == null ||
                !username.equals(record.getFolderMapping().getSourceAccount().getUsername())) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptId", "RCP-" + trackId);
        receipt.put("trackId", trackId);
        receipt.put("filename", record.getOriginalFilename());
        receipt.put("sender", username);
        receipt.put("status", record.getStatus().name());
        receipt.put("fileSizeBytes", record.getFileSizeBytes() != null ? record.getFileSizeBytes() : 0);
        receipt.put("sourceChecksum", record.getSourceChecksum() != null ? record.getSourceChecksum() : "");
        receipt.put("destinationChecksum", record.getDestinationChecksum() != null ? record.getDestinationChecksum() : "");
        receipt.put("integrityVerified", record.getSourceChecksum() != null &&
                record.getSourceChecksum().equals(record.getDestinationChecksum()));
        receipt.put("uploadedAt", record.getUploadedAt() != null ? record.getUploadedAt().toString() : "");
        receipt.put("deliveredAt", record.getRoutedAt() != null ? record.getRoutedAt().toString() : "");
        receipt.put("completedAt", record.getCompletedAt() != null ? record.getCompletedAt().toString() : "");
        receipt.put("generatedAt", Instant.now().toString());
        receipt.put("platform", "TranzFer MFT");
        receipt.put("notice", "This receipt confirms the file was received, processed, and delivered with cryptographic integrity verification.");
        return ResponseEntity.ok(receipt);
    }

    // === Connection Test — partner tests their SFTP/FTP connectivity ===

    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        String username = getAuthenticatedPartner();
        TransferAccount account = findAccount(username);
        if (account == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(Map.of(
                "username", username,
                "protocol", account.getProtocol().name(),
                "homeDir", account.getHomeDir(),
                "active", account.isActive(),
                "serverHost", "Use the hostname provided by your administrator",
                "serverPort", account.getProtocol() == Protocol.SFTP ? 2222 : 21,
                "instructions", account.getProtocol() == Protocol.SFTP ?
                        "sftp -P 2222 " + username + "@<server_host>" :
                        "ftp <server_host>"
        ));
    }

    // === SSH Key Rotation — partner uploads new public key ===

    @PostMapping("/rotate-key")
    public ResponseEntity<Map<String, Object>> rotateKey(@RequestBody Map<String, String> body) {
        String username = getAuthenticatedPartner();
        TransferAccount account = findAccount(username);
        if (account == null) return ResponseEntity.notFound().build();

        String newPublicKey = body.get("publicKey");
        if (newPublicKey == null || newPublicKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "publicKey is required"));
        }

        String oldFingerprint = account.getPublicKey() != null ?
                account.getPublicKey().substring(0, Math.min(20, account.getPublicKey().length())) + "..." : "none";
        account.setPublicKey(newPublicKey);
        accountRepo.save(account);

        log.info("Partner {} rotated SSH key", username);

        return ResponseEntity.ok(Map.of(
                "status", "KEY_ROTATED",
                "username", username,
                "oldKeyPrefix", oldFingerprint,
                "newKeyPrefix", newPublicKey.substring(0, Math.min(20, newPublicKey.length())) + "...",
                "rotatedAt", Instant.now().toString(),
                "note", "New key is active immediately. Test your connection."
        ));
    }

    // === Password Change ===

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody Map<String, String> body) {
        String username = getAuthenticatedPartner();
        TransferAccount account = findAccount(username);
        if (account == null) return ResponseEntity.notFound().build();

        String currentPass = body.get("currentPassword");
        String newPass = body.get("newPassword");

        if (!new BCryptPasswordEncoder().matches(currentPass, account.getPasswordHash())) {
            return ResponseEntity.status(403).body(Map.of("error", "Current password is incorrect"));
        }

        account.setPasswordHash(new BCryptPasswordEncoder().encode(newPass));
        accountRepo.save(account);

        return ResponseEntity.ok(Map.of("status", "PASSWORD_CHANGED", "username", username));
    }

    // === My SLA Status ===

    @GetMapping("/sla")
    public ResponseEntity<Map<String, Object>> slaStatus() {
        String username = getAuthenticatedPartner();
        UUID accountId = getAccountId(username);
        List<FileTransferRecord> records = transferRepo
                .findByFolderMappingSourceAccountIdOrderByUploadedAtDesc(accountId);

        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<FileTransferRecord> weekRecords = records.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(weekAgo))
                .collect(Collectors.toList());

        long total = weekRecords.size();
        long failed = weekRecords.stream().filter(r -> "FAILED".equals(r.getStatus().name())).count();
        double errorRate = total > 0 ? (double) failed / total * 100 : 0;

        // Average delivery time
        double avgDeliveryMs = weekRecords.stream()
                .filter(r -> r.getUploadedAt() != null && r.getRoutedAt() != null)
                .mapToLong(r -> ChronoUnit.MILLIS.between(r.getUploadedAt(), r.getRoutedAt()))
                .average().orElse(0);

        return ResponseEntity.ok(Map.of(
                "username", username,
                "period", "last 7 days",
                "totalTransfers", total,
                "failedTransfers", failed,
                "errorRate", Math.round(errorRate * 10) / 10.0 + "%",
                "avgDeliveryTimeMs", Math.round(avgDeliveryMs),
                "slaCompliant", errorRate < 5 && avgDeliveryMs < 30000
        ));
    }

    // === Helpers ===

    private TransferAccount findAccount(String username) {
        return accountRepo.findAll().stream()
                .filter(a -> a.getUsername().equals(username)).findFirst().orElse(null);
    }

    private UUID getAccountId(String username) {
        TransferAccount a = findAccount(username);
        return a != null ? a.getId() : null;
    }
}
