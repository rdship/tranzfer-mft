package com.filetransfer.ftpweb.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured audit for FTP_WEB file operations. Events are tagged with the
 * serving {@code instanceId} so downstream log aggregation can partition
 * activity per listener — matches the pattern used by SFTP's
 * {@code AuditEventLogger} and FTP's audit chain.
 *
 * <p>Emitted to a dedicated {@code FTP_WEB_AUDIT} logger so log routing
 * configs can ship it to the platform audit sink without noise from the
 * general service logger.
 */
@Component
public class FtpWebAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("FTP_WEB_AUDIT");

    public void logUpload(String username, String listenerInstanceId, String sourceIp,
                          String path, long sizeBytes, String storageMode) {
        log("UPLOAD", username, listenerInstanceId, sourceIp, Map.of(
                "path", path,
                "size", sizeBytes,
                "storageMode", storageMode));
    }

    public void logDownload(String username, String listenerInstanceId, String sourceIp, String path) {
        log("DOWNLOAD", username, listenerInstanceId, sourceIp, Map.of("path", path));
    }

    public void logDelete(String username, String listenerInstanceId, String sourceIp, String path) {
        log("DELETE", username, listenerInstanceId, sourceIp, Map.of("path", path));
    }

    public void logRename(String username, String listenerInstanceId, String sourceIp,
                           String fromPath, String toPath) {
        log("RENAME", username, listenerInstanceId, sourceIp, Map.of(
                "from", fromPath, "to", toPath));
    }

    public void logList(String username, String listenerInstanceId, String sourceIp, String path) {
        log("LIST", username, listenerInstanceId, sourceIp, Map.of("path", path));
    }

    public void logMkdir(String username, String listenerInstanceId, String sourceIp, String path) {
        log("MKDIR", username, listenerInstanceId, sourceIp, Map.of("path", path));
    }

    private void log(String event, String username, String listenerInstanceId,
                      String sourceIp, Map<String, Object> extras) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("event", event);
        line.put("username", username);
        line.put("instanceId", listenerInstanceId);
        line.put("ip", sourceIp);
        line.putAll(extras);
        LOG.info("{}", line);
    }
}
