package com.filetransfer.sftp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits structured JSON audit log entries for all SFTP operations.
 *
 * <p>Each event is logged at INFO level with a consistent JSON structure
 * containing: timestamp, event type, username, IP address, filename, bytes
 * transferred, duration, and a success/failure indicator.</p>
 *
 * <p>Events are logged to the "sftp.audit" logger, allowing them to be
 * routed to a dedicated file or log aggregation system via logback configuration.</p>
 */
@Slf4j
@Component
public class AuditEventLogger {

    private static final org.slf4j.Logger AUDIT_LOG =
            org.slf4j.LoggerFactory.getLogger("sftp.audit");

    private final ObjectMapper objectMapper;

    private final AtomicLong loginSuccessCount = new AtomicLong(0);
    private final AtomicLong loginFailureCount = new AtomicLong(0);
    private final AtomicLong uploadCount = new AtomicLong(0);
    private final AtomicLong downloadCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);

    @Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    public AuditEventLogger() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── Preferred entry points: pass the arriving Session so audit records the
    //    correct listener (primary OR dynamic). Old overloads delegate here
    //    with null, falling back to the env-var primary for backward compat. ──

    public void logLogin(String username, String ipAddress, String authMethod,
                         org.apache.sshd.common.session.Session session) {
        loginSuccessCount.incrementAndGet();
        logEvent("LOGIN", username, ipAddress, null, 0, 0, true,
                Map.of("authMethod", authMethod), resolveListenerInstance(session));
    }

    public void logLogin(String username, String ipAddress, String authMethod) {
        logLogin(username, ipAddress, authMethod, null);
    }

    public void logLoginFailed(String username, String ipAddress, String reason,
                                org.apache.sshd.common.session.Session session) {
        loginFailureCount.incrementAndGet();
        logEvent("LOGIN_FAILED", username, ipAddress, null, 0, 0, false,
                Map.of("reason", reason), resolveListenerInstance(session));
    }

    public void logLoginFailed(String username, String ipAddress, String reason) {
        logLoginFailed(username, ipAddress, reason, null);
    }

    public void logUpload(String username, String ipAddress, String filename, long bytes, long durationMs,
                          org.apache.sshd.common.session.Session session) {
        uploadCount.incrementAndGet();
        totalBytesUploaded.addAndGet(bytes);
        logEvent("UPLOAD", username, ipAddress, filename, bytes, durationMs, true, null,
                resolveListenerInstance(session));
    }

    public void logUpload(String username, String ipAddress, String filename, long bytes, long durationMs) {
        logUpload(username, ipAddress, filename, bytes, durationMs, null);
    }

    public void logDownload(String username, String ipAddress, String filename, long bytes, long durationMs,
                             org.apache.sshd.common.session.Session session) {
        downloadCount.incrementAndGet();
        totalBytesDownloaded.addAndGet(bytes);
        logEvent("DOWNLOAD", username, ipAddress, filename, bytes, durationMs, true, null,
                resolveListenerInstance(session));
    }

    public void logDownload(String username, String ipAddress, String filename, long bytes, long durationMs) {
        logDownload(username, ipAddress, filename, bytes, durationMs, null);
    }

    public void logDelete(String username, String ipAddress, String filename,
                          org.apache.sshd.common.session.Session session) {
        deleteCount.incrementAndGet();
        logEvent("DELETE", username, ipAddress, filename, 0, 0, true, null,
                resolveListenerInstance(session));
    }

    public void logDelete(String username, String ipAddress, String filename) {
        logDelete(username, ipAddress, filename, null);
    }

    public void logMkdir(String username, String ipAddress, String path,
                         org.apache.sshd.common.session.Session session) {
        logEvent("MKDIR", username, ipAddress, path, 0, 0, true, null,
                resolveListenerInstance(session));
    }

    public void logMkdir(String username, String ipAddress, String path) {
        logMkdir(username, ipAddress, path, null);
    }

    public void logRename(String username, String ipAddress, String oldPath, String newPath,
                          org.apache.sshd.common.session.Session session) {
        logEvent("RENAME", username, ipAddress, oldPath, 0, 0, true,
                Map.of("newPath", newPath), resolveListenerInstance(session));
    }

    public void logRename(String username, String ipAddress, String oldPath, String newPath) {
        logRename(username, ipAddress, oldPath, newPath, null);
    }

    public void logDisconnect(String username, String ipAddress, long sessionDurationMs,
                              org.apache.sshd.common.session.Session session) {
        logEvent("DISCONNECT", username, ipAddress, null, 0, sessionDurationMs, true, null,
                resolveListenerInstance(session));
    }

    public void logDisconnect(String username, String ipAddress, long sessionDurationMs) {
        logDisconnect(username, ipAddress, sessionDurationMs, null);
    }

    public void logConnectionRejected(String username, String ipAddress, String reason,
                                       org.apache.sshd.common.session.Session session) {
        logEvent("CONNECTION_REJECTED", username, ipAddress, null, 0, 0, false,
                Map.of("reason", reason), resolveListenerInstance(session));
    }

    public void logConnectionRejected(String username, String ipAddress, String reason) {
        logConnectionRejected(username, ipAddress, reason, null);
    }

    public void logAccountLocked(String username, String ipAddress, int failureCount) {
        logEvent("ACCOUNT_LOCKED", username, ipAddress, null, 0, 0, false,
                Map.of("failureCount", failureCount), null);
    }

    /**
     * Use ListenerContext attribute if the session was tagged (per-listener);
     * otherwise fall back to the env-var primary for backward compat.
     */
    private String resolveListenerInstance(org.apache.sshd.common.session.Session session) {
        String tagged = com.filetransfer.sftp.server.ListenerContext.instanceId(session);
        return tagged != null ? tagged : instanceId;
    }

    /**
     * Returns aggregate statistics for the health endpoint.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("loginSuccessCount", loginSuccessCount.get());
        stats.put("loginFailureCount", loginFailureCount.get());
        stats.put("uploadCount", uploadCount.get());
        stats.put("downloadCount", downloadCount.get());
        stats.put("deleteCount", deleteCount.get());
        stats.put("totalBytesUploaded", totalBytesUploaded.get());
        stats.put("totalBytesDownloaded", totalBytesDownloaded.get());
        return stats;
    }

    private void logEvent(String event, String username, String ipAddress,
                          String filename, long bytes, long durationMs,
                          boolean success, Map<String, Object> extra,
                          String resolvedInstanceId) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("event", event);
            entry.put("username", username != null ? username : "unknown");
            entry.put("ipAddress", ipAddress != null ? ipAddress : "unknown");
            entry.put("success", success);
            if (filename != null) entry.put("filename", filename);
            if (bytes > 0) entry.put("bytes", bytes);
            if (durationMs > 0) entry.put("durationMs", durationMs);
            String effectiveInstance = resolvedInstanceId != null ? resolvedInstanceId : instanceId;
            if (effectiveInstance != null) entry.put("instanceId", effectiveInstance);
            if (extra != null) entry.putAll(extra);

            String json = objectMapper.writeValueAsString(entry);
            AUDIT_LOG.info(json);
        } catch (Exception e) {
            log.warn("Failed to write audit log entry: {}", e.getMessage());
        }
    }
}
