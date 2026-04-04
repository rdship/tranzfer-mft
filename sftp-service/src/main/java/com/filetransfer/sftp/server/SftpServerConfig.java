package com.filetransfer.sftp.server;

import com.filetransfer.sftp.routing.SftpRoutingEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.Collections;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpServerConfig {

    private final SftpPasswordAuthenticator passwordAuthenticator;
    private final SftpPublicKeyAuthenticator publicKeyAuthenticator;
    private final SftpFileSystemFactory fileSystemFactory;
    private final SftpRoutingEventListener routingEventListener;

    @Value("${sftp.port:2222}")
    private int sftpPort;

    @Value("${sftp.host-key-path:./sftp_host_key}")
    private String hostKeyPath;

    @Bean
    public SshServer sshServer() {
        SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory();
        sftpFactory.addSftpEventListener(routingEventListener);

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(sftpPort);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get(hostKeyPath)));
        sshd.setPasswordAuthenticator(passwordAuthenticator);
        sshd.setPublickeyAuthenticator(publicKeyAuthenticator);
        sshd.setSubsystemFactories(Collections.singletonList(sftpFactory));
        sshd.setFileSystemFactory(fileSystemFactory);
        return sshd;
    }

    @Bean
    public ApplicationRunner sftpServerRunner(SshServer sshServer) {
        return args -> {
            sshServer.start();
            log.info("SFTP server started on port {}", sftpPort);
        };
    }
}
