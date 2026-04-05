package com.filetransfer.ftp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits structured JSON audit log events to a dedicated logger.
 *
 * <p>Events are written to the {@code FTP_AUDIT} logger at INFO level.
 * In production, a Logback/Log4j2 appender can route these to a file,
 * syslog, or log aggregation system (ELK, Splunk, CloudWatch, etc.).
 *
 * <p>Every event includes: timestamp, event type, username, IP, and
 * optional fields (filename, bytes transferred, duration, reason).
 */
@Slf4j
@Service
public class AuditEventLogger {

    private static final org.slf4j.Logger AUDIT_LOG =
            org.slf4j.LoggerFactory.getLogger("FTP_AUDIT");

    private final ObjectMapper mapper;

    @Value("${ftp.instance-id:#{null}}")
    private String instanceId;

    @Value("${ftp.audit.enabled:true}")
    private boolean enabled;

    /**
     * Construct the audit logger using Spring's auto-configured ObjectMapper
     * (which already includes the JSR310 JavaTimeModule).
     *
     * @param mapper the Spring-managed Jackson ObjectMapper
     */
    public AuditEventLogger(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Log a structured audit event.
     *
     * @param event  the event type (LOGIN, LOGIN_FAILED, UPLOAD, DOWNLOAD, DELETE, MKDIR, RENAME, DISCONNECT)
     * @param username the FTP username (may be null for pre-auth events)
     * @param ip     the client IP address
     * @param extras additional key-value pairs (filename, bytes, duration_ms, reason, etc.)
     */
    public void logEvent(String event, String username, String ip, Map<String, Object> extras) {
        if (!enabled) {
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("event", event);
        entry.put("username", username != null ? username : "anonymous");
        entry.put("ip", ip);
        if (instanceId != null) {
            entry.put("instance", instanceId);
        }
        if (extras != null) {
            entry.putAll(extras);
        }

        try {
            String json = mapper.writeValueAsString(entry);
            AUDIT_LOG.info(json);
        } catch (Exception e) {
            log.warn("Failed to serialize audit event: {}", e.getMessage());
        }
    }

    /**
     * Convenience overload with no extras.
     */
    public void logEvent(String event, String username, String ip) {
        logEvent(event, username, ip, null);
    }
}
