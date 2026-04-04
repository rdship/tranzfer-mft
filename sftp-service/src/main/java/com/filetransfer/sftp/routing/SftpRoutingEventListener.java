package com.filetransfer.sftp.routing;

import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.TransferAccountRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to SFTP file events (open/close) to detect uploads and downloads,
 * then delegates to the shared RoutingEngine.
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

    @Override
    public void opening(ServerSession session, String remoteHandle, Handle localHandle) {
        if (!(localHandle instanceof FileHandle fh)) return;
        Path path = fh.getFile();
        boolean isWrite = fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.WRITE)
                || fh.getOpenOptions().contains(java.nio.file.StandardOpenOption.CREATE);
        openHandles.put(remoteHandle, isWrite);
        handlePaths.put(remoteHandle, path);
        log.debug("SFTP handle opened: user={} path={} write={}", session.getUsername(), path, isWrite);
    }

    @Override
    public void closing(ServerSession session, String remoteHandle, Handle localHandle) {
        Boolean wasWrite = openHandles.remove(remoteHandle);
        Path filePath = handlePaths.remove(remoteHandle);
        if (wasWrite == null || filePath == null) return;

        String username = session.getUsername();
        Optional<TransferAccount> accountOpt = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);

        if (accountOpt.isEmpty()) return;

        TransferAccount account = accountOpt.get();
        // filePath from rooted FS is relative (e.g. "/inbox/invoice.csv")
        // Real absolute path = homeDir + rooted path
        String rootedPath = filePath.toAbsolutePath().toString();
        String realAbsolutePath = account.getHomeDir() + rootedPath;
        String relativePath = rootedPath;

        if (wasWrite) {
            log.info("SFTP upload detected: user={} relative={} absolute={}", username, relativePath, realAbsolutePath);
            routingEngine.onFileUploaded(account, relativePath, realAbsolutePath);
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

    // SftpEventListener has default no-op implementations; only opening/closing are overridden above.
}
