package com.filetransfer.shared.audit;

import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Value("${platform.security.control-api-key:internal_control_secret}")
    private String hmacSecret;

    @Async
    public void logFileUpload(TransferAccount account, String trackId, String path,
                               String filename, Path filePath, String ipAddress, String sessionId) {
        save(AuditLog.builder().account(account).trackId(trackId).action("FILE_UPLOAD").success(true)
                .path(path).filename(filename).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).ipAddress(ipAddress).sessionId(sessionId)
                .principal(account != null ? account.getUsername() : "system").build());
    }

    @Async
    public void logFileDownload(TransferAccount account, String trackId, String path,
                                 String filename, Path filePath, String ipAddress, String sessionId) {
        save(AuditLog.builder().account(account).trackId(trackId).action("FILE_DOWNLOAD").success(true)
                .path(path).filename(filename).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).ipAddress(ipAddress).sessionId(sessionId)
                .principal(account != null ? account.getUsername() : "system").build());
    }

    @Async
    public void logFileRoute(String trackId, String srcPath, String destPath, Path filePath) {
        save(AuditLog.builder().trackId(trackId).action("FILE_ROUTE").success(true)
                .path(destPath).filename(extractName(destPath)).fileSizeBytes(safeSize(filePath))
                .sha256Checksum(sha256(filePath)).principal("system")
                .metadata(Map.of("source", srcPath, "destination", destPath)).build());
    }

    @Async
    public void logFlowStep(String trackId, String stepType, String inputFile,
                             String outputFile, boolean success, long durationMs, String error) {
        save(AuditLog.builder().trackId(trackId).action("FLOW_" + stepType).success(success)
                .path(outputFile).filename(extractName(inputFile)).principal("system")
                .errorMessage(error).metadata(Map.of("input", str(inputFile), "output", str(outputFile),
                        "durationMs", String.valueOf(durationMs))).build());
    }

    @Async
    public void logFlowComplete(String trackId, String flowName, boolean success, String error) {
        save(AuditLog.builder().trackId(trackId).action(success ? "FLOW_COMPLETE" : "FLOW_FAIL")
                .success(success).principal("system").errorMessage(error)
                .metadata(Map.of("flowName", flowName)).build());
    }

    public void logLogin(String email, String ipAddress, boolean success, String reason) {
        save(AuditLog.builder().action(success ? "LOGIN" : "LOGIN_FAIL").success(success)
                .ipAddress(ipAddress).principal(email).errorMessage(success ? null : reason).build());
    }

    @Async
    public void logFailure(TransferAccount account, String trackId, String action, String path, String error) {
        save(AuditLog.builder().account(account).trackId(trackId).action(action).success(false)
                .path(path).principal(account != null ? account.getUsername() : "system")
                .errorMessage(error).build());
    }

    public static String sha256(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) return null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) d.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) { return null; }
    }

    private void save(AuditLog entry) {
        try {
            String payload = str(entry.getAction()) + "|" + str(entry.getTrackId()) + "|"
                    + str(entry.getPath()) + "|" + str(entry.getSha256Checksum()) + "|" + entry.getTimestamp();
            // Rebuild with integrityHash — AuditLog has no setters on critical fields
            AuditLog signed = AuditLog.builder()
                    .account(entry.getAccount()).trackId(entry.getTrackId()).action(entry.getAction())
                    .success(entry.isSuccess()).path(entry.getPath()).filename(entry.getFilename())
                    .fileSizeBytes(entry.getFileSizeBytes()).sha256Checksum(entry.getSha256Checksum())
                    .ipAddress(entry.getIpAddress()).sessionId(entry.getSessionId())
                    .principal(entry.getPrincipal()).errorMessage(entry.getErrorMessage())
                    .metadata(entry.getMetadata()).integrityHash(hmac(payload)).build();
            auditLogRepository.save(signed);
        } catch (Exception e) {
            log.error("AUDIT SAVE FAILED (CRITICAL): {}", e.getMessage());
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) { return "error"; }
    }

    private Long safeSize(Path p) {
        try { return p != null && Files.exists(p) ? Files.size(p) : null; } catch (IOException e) { return null; }
    }

    private String extractName(String path) {
        if (path == null) return null;
        int i = path.lastIndexOf('/'); return i >= 0 ? path.substring(i + 1) : path;
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }
}
