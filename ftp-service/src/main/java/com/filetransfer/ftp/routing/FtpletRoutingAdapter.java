package com.filetransfer.ftp.routing;

import com.filetransfer.ftp.audit.AuditEventLogger;
import com.filetransfer.ftp.connection.ConnectionTracker;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Apache FTPServer Ftplet that hooks into upload/download/delete/mkdir/rename
 * and disconnect events to trigger the shared RoutingEngine and emit
 * structured audit log events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpletRoutingAdapter extends DefaultFtplet {

    private final RoutingEngine routingEngine;
    private final TransferAccountRepository accountRepository;
    private final AuditEventLogger auditEventLogger;
    private final ConnectionTracker connectionTracker;

    @Value("${ftp.home-base:/data/ftp}")
    private String homeBase;

    /**
     * Track the start time of an upload for duration calculation.
     */
    @Override
    public FtpletResult onUploadStart(FtpSession session, FtpRequest request)
            throws FtpException, IOException {
        session.setAttribute("upload_start_ms", System.currentTimeMillis());
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onUploadEnd(FtpSession session, FtpRequest request) throws FtpException, IOException {
        String username = session.getUser().getName();
        String filename = request.getArgument();

        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        if (accountOpt.isEmpty()) {
            log.warn("FTP upload by user '{}' ignored — no active TransferAccount found for file: {}", username, filename);
            return FtpletResult.DEFAULT;
        }

        TransferAccount account = accountOpt.get();
        String absolutePath = account.getHomeDir() + "/" + filename;
        String relativePath = "/" + filename;

        // Calculate file size and duration
        long bytes = 0;
        File uploaded = new File(absolutePath);
        if (uploaded.exists()) {
            bytes = uploaded.length();
        }
        long durationMs = calculateDuration(session, "upload_start_ms");

        String sourceIp = extractClientIp(session);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filename", filename);
        extras.put("bytes", bytes);
        extras.put("duration_ms", durationMs);
        auditEventLogger.logEvent("UPLOAD", username, sourceIp, extras);

        log.info("FTP upload detected: user={} path={} bytes={} ip={}", username, relativePath, bytes, sourceIp);
        routingEngine.onFileUploaded(account, relativePath, absolutePath, sourceIp);
        return FtpletResult.DEFAULT;
    }

    /**
     * Track the start time of a download for duration calculation.
     */
    @Override
    public FtpletResult onDownloadStart(FtpSession session, FtpRequest request)
            throws FtpException, IOException {
        session.setAttribute("download_start_ms", System.currentTimeMillis());
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onDownloadEnd(FtpSession session, FtpRequest request) throws FtpException, IOException {
        String username = session.getUser().getName();
        String filename = request.getArgument();

        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        if (accountOpt.isEmpty()) return FtpletResult.DEFAULT;

        TransferAccount account = accountOpt.get();
        String absolutePath = account.getHomeDir() + "/" + filename;

        File downloaded = new File(absolutePath);
        long bytes = downloaded.exists() ? downloaded.length() : 0;
        long durationMs = calculateDuration(session, "download_start_ms");

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filename", filename);
        extras.put("bytes", bytes);
        extras.put("duration_ms", durationMs);
        auditEventLogger.logEvent("DOWNLOAD", username, extractClientIp(session), extras);

        log.info("FTP download detected: user={} path={} bytes={}", username, absolutePath, bytes);
        routingEngine.onFileDownloaded(account, absolutePath);
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onDeleteEnd(FtpSession session, FtpRequest request)
            throws FtpException, IOException {
        String username = session.getUser().getName();
        String filename = request.getArgument();
        auditEventLogger.logEvent("DELETE", username, extractClientIp(session),
                Map.of("filename", filename != null ? filename : ""));
        log.info("FTP delete detected: user={} file={}", username, filename);
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onMkdirEnd(FtpSession session, FtpRequest request)
            throws FtpException, IOException {
        String username = session.getUser().getName();
        String dirname = request.getArgument();
        auditEventLogger.logEvent("MKDIR", username, extractClientIp(session),
                Map.of("directory", dirname != null ? dirname : ""));
        log.info("FTP mkdir detected: user={} dir={}", username, dirname);
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onRenameEnd(FtpSession session, FtpRequest request)
            throws FtpException, IOException {
        String username = session.getUser().getName();
        String arg = request.getArgument();
        auditEventLogger.logEvent("RENAME", username, extractClientIp(session),
                Map.of("argument", arg != null ? arg : ""));
        log.info("FTP rename detected: user={} arg={}", username, arg);
        return FtpletResult.DEFAULT;
    }

    @Override
    public FtpletResult onDisconnect(FtpSession session) throws FtpException, IOException {
        String username = session.getUser() != null ? session.getUser().getName() : null;
        String ip = extractClientIp(session);

        auditEventLogger.logEvent("DISCONNECT", username, ip);
        connectionTracker.release(username, ip);

        log.debug("FTP disconnect: user={} ip={}", username, ip);
        return FtpletResult.DEFAULT;
    }

    private String extractClientIp(FtpSession session) {
        try {
            if (session.getClientAddress() instanceof InetSocketAddress isa) {
                return isa.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    private long calculateDuration(FtpSession session, String attributeKey) {
        try {
            Object startObj = session.getAttribute(attributeKey);
            if (startObj instanceof Long startMs) {
                return System.currentTimeMillis() - startMs;
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }
}
