package com.filetransfer.sftp.server;

import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class SftpFileSystemFactory implements FileSystemFactory {

    private final CredentialService credentialService;
    private final ServerInstanceRepository serverInstanceRepository;
    private final FolderTemplateRepository folderTemplateRepository;

    @Value("${sftp.home-base:/data/sftp}")
    private String homeBase;

    @Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    @Override
    public Path getUserHomeDir(SessionContext session) throws IOException {
        String username = session.getUsername();
        return resolveHomeDir(username);
    }

    @Override
    public FileSystem createFileSystem(SessionContext session) throws IOException {
        String username = session.getUsername();
        Path homeDir = resolveHomeDir(username);
        // Create home + template subdirectories on login (idempotent)
        for (String folder : resolveFolderPaths()) {
            Files.createDirectories(homeDir.resolve(folder));
        }
        log.info("SFTP filesystem ready for user={} at {}", username, homeDir);
        return new RootedFileSystemProvider().newFileSystem(homeDir, Collections.emptyMap());
    }

    private Path resolveHomeDir(String username) {
        return credentialService.findAccount(username)
                .map(a -> Paths.get(a.getHomeDir()))
                .orElse(Paths.get(homeBase, username));
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
}
