package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.vfs.VirtualFileSystem;
// VirtualFtpFileSystemView is in this package (ftp.server)
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
 * <p>VIRTUAL accounts → Phantom Folder VFS (DB + CAS).
 * <p>PHYSICAL accounts → default native filesystem (legacy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VirtualFtpFileSystemFactory implements FileSystemFactory {

    private final TransferAccountRepository accountRepository;
    private final VirtualFileSystem virtualFileSystem;
    private final StorageServiceClient storageServiceClient;

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        String username = user.getName();

        Optional<TransferAccount> account = accountRepository.findByUsername(username);
        if (account.isPresent() && "VIRTUAL".equalsIgnoreCase(account.get().getStorageMode())) {
            log.info("FTP virtual filesystem for user={} (account={})", username, account.get().getId());
            return new VirtualFtpFileSystemView(account.get().getId(), virtualFileSystem, storageServiceClient);
        }

        // Legacy: use default native filesystem backed by homeDirectory
        log.debug("FTP physical filesystem for user={} at {}", username, user.getHomeDirectory());
        return new NativeFileSystemView(user, false);
    }
}
