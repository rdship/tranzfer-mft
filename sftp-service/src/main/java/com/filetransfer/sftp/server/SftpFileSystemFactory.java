package com.filetransfer.sftp.server;

import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.core.FolderTemplateRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import com.filetransfer.shared.routing.RoutingEngine;
import com.filetransfer.shared.vfs.VirtualSftpFileSystem;
import com.filetransfer.shared.vfs.VirtualSftpPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.session.SessionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dual-mode SFTP FileSystem factory.
 *
 * <p>Accounts with {@code storageMode=VIRTUAL} get the Phantom Folder virtual filesystem
 * (DB-backed catalog + content-addressed storage). All others use the legacy physical
 * {@link RootedFileSystemProvider}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpFileSystemFactory implements FileSystemFactory {

    private final CredentialService credentialService;
    private final ServerInstanceRepository serverInstanceRepository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final VirtualFileSystem virtualFileSystem;
    private final StorageServiceClient storageServiceClient;
    private final RoutingEngine routingEngine;

    @Value("${sftp.home-base:/data/sftp}")
    private String homeBase;

    @Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    @Override
    public Path getUserHomeDir(SessionContext session) throws IOException {
        String username = session.getUsername();
        Optional<TransferAccount> account = credentialService.findAccount(username);

        // Virtual mode: return a virtual root path
        if (account.isPresent() && isVirtualMode(account.get())) {
            VirtualSftpFileSystem vfs = new VirtualSftpFileSystem(
                    account.get().getId(), virtualFileSystem, storageServiceClient);
            return new VirtualSftpPath(vfs, "/");
        }

        return resolvePhysicalHomeDir(username, account.orElse(null));
    }

    @Override
    public FileSystem createFileSystem(SessionContext session) throws IOException {
        String username = session.getUsername();
        Optional<TransferAccount> account = credentialService.findAccount(username);

        // ── Virtual mode: Phantom Folder VFS ────────────────────────────
        if (account.isPresent() && isVirtualMode(account.get())) {
            TransferAccount acct = account.get();
            UUID acctId = acct.getId();

            // Safety net: if no virtual entries exist yet, provision from server's folder template
            ensureVirtualFoldersProvisioned(acctId);

            // Extract session IP for the routing callback
            String sessionIp = null;
            if (session instanceof org.apache.sshd.server.session.ServerSession ss) {
                try {
                    var addr = ss.getClientAddress();
                    if (addr != null) sessionIp = addr.toString().replace("/", "");
                } catch (Exception ignored) {}
            }
            final String sourceIp = sessionIp;

            // Callback: after VFS write completes, trigger file routing
            VirtualSftpFileSystem.WriteCompletionCallback onWritten = (virtualPath, sizeBytes, storageKey) -> {
                log.info("VFS write complete: user={} path={} size={} — triggering routing",
                        username, virtualPath, sizeBytes);
                routingEngine.onFileUploaded(acct, virtualPath, acct.getHomeDir() + virtualPath, sourceIp);
            };

            VirtualSftpFileSystem vfs = new VirtualSftpFileSystem(
                    acctId, virtualFileSystem, storageServiceClient, onWritten);
            log.info("SFTP virtual filesystem ready for user={} (account={})", username, acctId);
            return vfs;
        }

        // ── Physical mode: legacy rooted filesystem ─────────────────────
        Path homeDir = resolvePhysicalHomeDir(username, account.orElse(null));
        // H6 fix: always ensure homeDir exists (even if no folder templates configured)
        Files.createDirectories(homeDir);
        for (String folder : resolveFolderPaths()) {
            Files.createDirectories(homeDir.resolve(folder));
        }
        log.info("SFTP physical filesystem ready for user={} at {}", username, homeDir);
        return new RootedFileSystemProvider().newFileSystem(homeDir, Collections.emptyMap());
    }

    private boolean isVirtualMode(TransferAccount account) {
        return "VIRTUAL".equalsIgnoreCase(account.getStorageMode());
    }

    private Path resolvePhysicalHomeDir(String username, TransferAccount account) {
        if (account != null) return Paths.get(account.getHomeDir());
        return Paths.get(homeBase, username);
    }

    private List<String> resolveFolderPaths() {
        try {
            if (instanceId != null) {
                return serverInstanceRepository.findByInstanceId(instanceId)
                        .filter(si -> si.getFolderTemplate() != null)
                        .map(si -> si.getFolderTemplate().getFolders().stream()
                                .map(FolderDefinition::getPath).toList())
                        .orElse(List.of());
            }
        } catch (Exception e) {
            log.warn("Could not resolve folder template: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Lazy safety net: if a VIRTUAL account connects but has no provisioned folders
     * (e.g. event was lost, pod crashed mid-onboarding), provision from the server's
     * folder template on first connect. Idempotent — skips if folders already exist.
     */
    private void ensureVirtualFoldersProvisioned(UUID accountId) {
        try {
            List<String> folderPaths = resolveFolderPaths();
            if (folderPaths.isEmpty()) return;

            // Quick check: if at least one template folder exists, skip
            if (virtualFileSystem.exists(accountId, "/" + folderPaths.getFirst())) return;

            virtualFileSystem.provisionFolders(accountId, folderPaths);
            log.info("Lazy-provisioned {} virtual folders for account {}", folderPaths.size(), accountId);
        } catch (Exception e) {
            log.warn("Failed to lazy-provision virtual folders for account {}: {}", accountId, e.getMessage());
        }
    }
}
