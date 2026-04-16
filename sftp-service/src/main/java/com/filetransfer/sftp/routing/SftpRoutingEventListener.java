package com.filetransfer.sftp.routing;

import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.CopyOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to SFTP file events (open/close) to detect uploads and downloads,
 * then delegates to the shared RoutingEngine.
 *
 * <p><b>Session cleanup (N34 fix):</b> When an SFTP client disconnects without
 * explicitly closing file handles (abrupt disconnect, network failure, script
 * that exits before FXP_CLOSE), the {@code closing()} callback never fires.
 * To catch these orphaned uploads, this listener also tracks which session owns
 * each handle. {@link #flushSession(ServerSession)} iterates over all open
 * handles for that session and emits events for any that were write handles.
 * This is called by {@link com.filetransfer.sftp.session.SftpSessionListener}
 * on session close.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpRoutingEventListener implements SftpEventListener {

    private final RoutingEngine routingEngine;
    private final TransferAccountRepository accountRepository;

    @Value("${sftp.home-base:/data/sftp}")
    private String homeBase;

    // handle → was opened for WRITE (true) or READ (false)
    private final ConcurrentHashMap<String, Boolean> openHandles = new ConcurrentHashMap<>();
    // handle → absolute path
    private final ConcurrentHashMap<String, Path> handlePaths = new ConcurrentHashMap<>();
    // handle → session that opened it
    private final ConcurrentHashMap<String, ServerSession> handleSessions = new ConcurrentHashMap<>();
    // handle → MINA Handle object (for forced close on abrupt disconnect)
    private final ConcurrentHashMap<String, Handle> handleObjects = new ConcurrentHashMap<>();

    @Override
    public void opening(ServerSession session, String remoteHandle, Handle localHandle) {
        if (!(localHandle instanceof FileHandle fh)) return;
        Path path = fh.getFile();
        boolean isWrite = fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.WRITE)
                || fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.CREATE);
        openHandles.put(remoteHandle, isWrite);
        handlePaths.put(remoteHandle, path);
        handleSessions.put(remoteHandle, session);
        handleObjects.put(remoteHandle, localHandle);
        log.debug("SFTP handle opened: user={} path={} write={}", session.getUsername(), path, isWrite);
    }

    @Override
    public void closing(ServerSession session, String remoteHandle, Handle localHandle) {
        // Normal path: MINA already called Handle.close() → VirtualWriteChannel.close()
        // completed → VFS entry exists → callback fired routing. Clean up tracking.
        handleSessions.remove(remoteHandle);
        handleObjects.remove(remoteHandle);
        Boolean wasWrite = openHandles.remove(remoteHandle);
        Path filePath = handlePaths.remove(remoteHandle);
        if (wasWrite == null || filePath == null) return;

        processHandle(session, wasWrite, filePath);
    }

    /**
     * Holds session teardown until all orphaned write handles are closed.
     * Called by {@link com.filetransfer.sftp.session.SftpSessionListener#sessionClosed}
     * when the client disconnects without sending FXP_CLOSE.
     *
     * <p><b>N50 fix:</b> Explicitly calls {@code Handle.close()} on each orphaned
     * write handle. This is synchronous — blocks until the underlying channel
     * completes its flush:
     * <ul>
     *   <li>VIRTUAL: {@code VirtualWriteChannel.close()} stores temp file to MinIO,
     *       creates VFS entry, fires VfsWriteCallback → routing engine.
     *   <li>PHYSICAL: file bytes flushed to disk, then {@code processHandle()} routes.
     * </ul>
     * Session teardown waits for all writes to commit. No data loss.
     */
    public void flushSession(ServerSession session) {
        int flushed = 0;
        Iterator<Map.Entry<String, ServerSession>> it = handleSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ServerSession> entry = it.next();
            if (entry.getValue() == session) {
                String handleId = entry.getKey();
                it.remove();
                Boolean wasWrite = openHandles.remove(handleId);
                Path filePath = handlePaths.remove(handleId);
                Handle handle = handleObjects.remove(handleId);

                if (wasWrite == null || !wasWrite || filePath == null) continue;

                // Force-close the handle — this synchronously flushes the write channel.
                // For VIRTUAL: VirtualWriteChannel.close() → MinIO + VFS + callback.
                // For PHYSICAL: bytes flushed to disk.
                if (handle != null) {
                    try {
                        handle.close();
                        log.info("SFTP session hold: Handle.close() completed for user={} path={}",
                                session.getUsername(), filePath);
                    } catch (Exception e) {
                        log.warn("SFTP session hold: Handle.close() failed for user={} path={}: {}",
                                session.getUsername(), filePath, e.getMessage());
                    }
                }

                // VIRTUAL: routing already triggered by VfsWriteCallback inside Handle.close().
                // PHYSICAL: route now that file is flushed to disk.
                processHandle(session, true, filePath);
                flushed++;
            }
        }
        if (flushed > 0) {
            log.info("SFTP session cleanup: committed {} write handle(s) for user={}",
                    flushed, session.getUsername());
        }
    }

    private void processHandle(ServerSession session, boolean wasWrite, Path filePath) {
        String username = session.getUsername();
        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);

        if (accountOpt.isEmpty()) {
            log.warn("SFTP upload by user '{}' ignored — no active TransferAccount found for path: {}",
                    username, filePath.toAbsolutePath());
            return;
        }

        TransferAccount account = accountOpt.get();
        // filePath from rooted FS is relative (e.g. "/inbox/invoice.csv")
        // Real absolute path = homeDir + rooted path
        String rootedPath = filePath.toAbsolutePath().toString();
        String realAbsolutePath = account.getHomeDir() + rootedPath;
        String relativePath = rootedPath;

        if (wasWrite) {
            // VIRTUAL accounts: routing is triggered by VirtualWriteChannel.close()
            // after the VFS entry is created. Skip here to avoid race condition.
            if ("VIRTUAL".equalsIgnoreCase(account.getStorageMode())) {
                log.debug("SFTP upload (VIRTUAL): user={} file={} — routing deferred to VFS close",
                        username, relativePath);
                return;
            }

            // PHYSICAL accounts: file is on disk, route immediately
            String sourceIp = null;
            try {
                var clientAddr = session.getClientAddress();
                if (clientAddr != null) sourceIp = clientAddr.toString().replace("/", "");
            } catch (Exception e) { /* ignore */ }

            log.info("SFTP upload detected (PHYSICAL): user={} relative={} absolute={} ip={}",
                    username, relativePath, realAbsolutePath, sourceIp);
            routingEngine.onFileUploaded(account, relativePath, realAbsolutePath, sourceIp);
        } else {
            log.info("SFTP download detected: user={} path={}", username, realAbsolutePath);
            routingEngine.onFileDownloaded(account, realAbsolutePath);
        }
    }

    private String toRelativePath(String absolutePath, String homeDir) {
        if (absolutePath.startsWith(homeDir)) {
            return absolutePath.substring(homeDir.length());
        }
        return absolutePath;
    }
}
