package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.routing.RoutingEngine;
import com.filetransfer.shared.vfs.VfsWriteCallback;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.filesystem.nativefs.impl.NativeFileSystemView;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Dual-mode FTP FileSystem factory.
 *
 * <p>VIRTUAL accounts → Phantom Folder VFS (DB + CAS) + write callback for routing.
 * <p>PHYSICAL accounts → default native filesystem (legacy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualFtpFileSystemFactory implements FileSystemFactory {

    private final TransferAccountRepository accountRepository;
    private final VirtualFileSystem virtualFileSystem;
    private final StorageServiceClient storageServiceClient;
    private final RoutingEngine routingEngine;

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        String username = user.getName();

        Optional<TransferAccount> accountOpt = accountRepository.findByUsername(username);
        if (accountOpt.isPresent() && isVirtualMode(accountOpt.get())) {
            TransferAccount acct = accountOpt.get();
            log.info("FTP virtual filesystem for user={} (account={})", username, acct.getId());

            VfsWriteCallback onWritten = (virtualPath, sizeBytes, storageKey) -> {
                log.info("VFS-FTP write complete: user={} path={} size={} — triggering routing",
                        username, virtualPath, sizeBytes);
                routingEngine.onFileUploaded(acct, virtualPath, acct.getHomeDir() + virtualPath, null);
            };

            return new VirtualFtpFileSystemView(acct.getId(), virtualFileSystem, storageServiceClient, onWritten);
        }

        // Legacy: use default native filesystem backed by homeDirectory
        log.debug("FTP physical filesystem for user={} at {}", username, user.getHomeDirectory());
        return new NativeFileSystemView(user, false);
    }

    /**
     * Decide VIRTUAL vs PHYSICAL with a listener-aware fallback. Priority:
     *   1) account.storageMode (if set)
     *   2) arriving listener's defaultStorageMode (via FtpListenerContext ThreadLocal)
     *   3) PHYSICAL
     */
    private boolean isVirtualMode(TransferAccount account) {
        String accountMode = account.getStorageMode();
        if (accountMode != null && !accountMode.isBlank()) {
            return "VIRTUAL".equalsIgnoreCase(accountMode);
        }
        String listenerMode = FtpListenerContext.storageMode();
        return "VIRTUAL".equalsIgnoreCase(listenerMode);
    }
}
