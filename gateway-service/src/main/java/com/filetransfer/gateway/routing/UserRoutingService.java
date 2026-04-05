package com.filetransfer.gateway.routing;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Decides where to route a connecting user based on their server instance assignment.
 * Works for all protocols: SFTP, FTP, FTP_WEB.
 *
 *  - Known user with server assignment -> assigned server instance
 *  - Known user without assignment     -> default internal service
 *  - Unknown user                      -> configured legacy server
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoutingService {

    private final TransferAccountRepository accountRepository;
    private final LegacyServerConfigRepository legacyServerConfigRepository;
    private final ServerInstanceRepository serverInstanceRepository;

    @Value("${gateway.internal-sftp-host:sftp-service}")
    private String internalSftpHost;

    @Value("${gateway.internal-sftp-port:2222}")
    private int internalSftpPort;

    @Value("${gateway.internal-ftp-host:ftp-service}")
    private String internalFtpHost;

    @Value("${gateway.internal-ftp-port:21}")
    private int internalFtpPort;

    @Value("${gateway.internal-ftpweb-host:ftp-web-service}")
    private String internalFtpWebHost;

    @Value("${gateway.internal-ftpweb-port:8083}")
    private int internalFtpWebPort;

    public RouteDecision routeSftp(String username) {
        return routeByProtocol(username, Protocol.SFTP, internalSftpHost, internalSftpPort);
    }

    public RouteDecision routeFtp(String username) {
        return routeByProtocol(username, Protocol.FTP, internalFtpHost, internalFtpPort);
    }

    public RouteDecision routeFtpWeb(String username) {
        return routeByProtocol(username, Protocol.FTP_WEB, internalFtpWebHost, internalFtpWebPort);
    }

    private RouteDecision routeByProtocol(String username, Protocol protocol,
                                           String defaultHost, int defaultPort) {
        Optional<TransferAccount> account = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, protocol);

        if (account.isPresent()) {
            String assignedInstance = account.get().getServerInstance();

            if (assignedInstance != null) {
                Optional<ServerInstance> server = serverInstanceRepository
                        .findByInstanceId(assignedInstance);
                if (server.isPresent() && server.get().isActive()) {
                    ServerInstance s = server.get();
                    log.info("{} gateway: routing {} -> instance {} ({}:{})",
                            protocol, username, assignedInstance,
                            s.getInternalHost(), s.getInternalPort());
                    return new RouteDecision(s.getInternalHost(), s.getInternalPort(), false);
                }
                log.warn("{} gateway: user {} assigned to instance {} but instance not found/inactive, using default",
                        protocol, username, assignedInstance);
            }

            log.info("{} gateway: routing {} -> default ({}:{})",
                    protocol, username, defaultHost, defaultPort);
            return new RouteDecision(defaultHost, defaultPort, false);
        }

        return routeToLegacy(username, protocol);
    }

    private RouteDecision routeToLegacy(String username, Protocol protocol) {
        Optional<LegacyServerConfig> legacy = legacyServerConfigRepository
                .findByProtocolAndActiveTrue(protocol)
                .stream().findFirst();

        if (legacy.isPresent()) {
            log.info("{} gateway: routing unknown user {} -> legacy ({}:{})",
                    protocol, username, legacy.get().getHost(), legacy.get().getPort());
            return new RouteDecision(legacy.get().getHost(), legacy.get().getPort(), true);
        }

        log.warn("{} gateway: no legacy server configured for unknown user {}", protocol, username);
        return null; // caller must reject connection
    }

    public record RouteDecision(String host, int port, boolean isLegacy) {}
}
