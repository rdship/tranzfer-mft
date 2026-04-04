package com.filetransfer.gateway.server;

import com.filetransfer.gateway.routing.UserRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpGatewayConfig {

    private final UserRoutingService routingService;

    @Value("${gateway.sftp.port:2220}")
    private int gatewayPort;

    @Value("${gateway.sftp.host-key-path:./gateway_host_key}")
    private String hostKeyPath;

    static final org.apache.sshd.common.AttributeRepository.AttributeKey<ClientSession> BACKEND_SESSION
            = new org.apache.sshd.common.AttributeRepository.AttributeKey<>();

    @Bean(destroyMethod = "stop")
    public SshServer sftpGatewayServer() throws IOException {
        SshClient backendClient = SshClient.setUpDefaultClient();
        backendClient.start();

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(gatewayPort);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get(hostKeyPath)));

        // --- Password authenticator: route and create backend session ---
        sshd.setPasswordAuthenticator((PasswordAuthenticator) (username, password, session) -> {
            UserRoutingService.RouteDecision route = routingService.routeSftp(username);
            if (route == null) {
                log.warn("SFTP gateway: no route for user {}, rejecting", username);
                return false;
            }
            try {
                ClientSession backend = backendClient
                        .connect(username, route.host(), route.port())
                        .verify(10, TimeUnit.SECONDS)
                        .getSession();
                backend.addPasswordIdentity(password);
                backend.auth().verify(15, TimeUnit.SECONDS);
                // Store the authenticated backend session in the gateway server session
                session.setAttribute(BACKEND_SESSION, backend);
                log.info("SFTP gateway: {} authenticated via {}:{} (legacy={})",
                        username, route.host(), route.port(), route.isLegacy());
                return true;
            } catch (Exception e) {
                log.warn("SFTP gateway: backend auth failed for {}: {}", username, e.getMessage());
                return false;
            }
        });

        // --- FileSystemFactory: return a filesystem backed by the backend SFTP session ---
        FileSystemFactory proxyFsFactory = new FileSystemFactory() {
            @Override
            public java.nio.file.Path getUserHomeDir(org.apache.sshd.common.session.SessionContext session) {
                return Paths.get("/");
            }
            @Override
            public FileSystem createFileSystem(org.apache.sshd.common.session.SessionContext session) throws IOException {
                ClientSession backend = ((ServerSession) session).getAttribute(BACKEND_SESSION);
                if (backend == null) throw new IOException("No backend session for " + session.getUsername());
                return SftpClientFactory.instance().createSftpFileSystem(backend);
            }
        };

        sshd.setFileSystemFactory(proxyFsFactory);
        sshd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        return sshd;
    }

    @Bean
    public ApplicationRunner sftpGatewayRunner(SshServer sftpGatewayServer) {
        return args -> {
            sftpGatewayServer.start();
            log.info("SFTP gateway started on port {}", gatewayPort);
        };
    }
}
