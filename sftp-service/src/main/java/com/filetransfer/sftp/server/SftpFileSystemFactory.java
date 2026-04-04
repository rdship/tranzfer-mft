package com.filetransfer.sftp.server;

import com.filetransfer.sftp.service.CredentialService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class SftpFileSystemFactory implements FileSystemFactory {

    private final CredentialService credentialService;

    @Value("${sftp.home-base:/data/sftp}")
    private String homeBase;

    @Override
    public Path getUserHomeDir(SessionContext session) throws IOException {
        String username = session.getUsername();
        return resolveHomeDir(username);
    }

    @Override
    public FileSystem createFileSystem(SessionContext session) throws IOException {
        String username = session.getUsername();
        Path homeDir = resolveHomeDir(username);
        Files.createDirectories(homeDir);
        log.debug("Creating rooted filesystem for user={} at {}", username, homeDir);
        return new RootedFileSystemProvider().newFileSystem(homeDir, Collections.emptyMap());
    }

    private Path resolveHomeDir(String username) {
        return credentialService.findAccount(username)
                .map(a -> Paths.get(a.getHomeDir()))
                .orElse(Paths.get(homeBase, username));
    }
}
