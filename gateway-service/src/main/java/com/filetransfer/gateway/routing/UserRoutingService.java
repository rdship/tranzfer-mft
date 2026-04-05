package com.filetransfer.gateway.routing;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.entity.SftpServerInstance;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.SftpServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Decides where to route a connecting user:
 *  - Known user with server assignment → assigned sftp-service instance
 *  - Known user without assignment     → default internal sftp-service
 *  - Unknown user                      → configured legacy server
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoutingService {

    private final TransferAccountRepository accountRepository;
    private final LegacyServerConfigRepository legacyServerConfigRepository;
    private final SftpServerInstanceRepository serverInstanceRepository;

    @Value("${gateway.internal-sftp-host:sftp-service}")
    private String internalSftpHost;

    @Value("${gateway.internal-sftp-port:2222}")
    private int internalSftpPort;

    @Value("${gateway.internal-ftp-host:ftp-service}")
    private String internalFtpHost;

    @Value("${gateway.internal-ftp-port:21}")
    private int internalFtpPort;

    public RouteDecision routeSftp(String username) {
        Optional<TransferAccount> account = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);

        if (account.isPresent()) {
            String assignedInstance = account.get().getServerInstance();

            if (assignedInstance != null) {
                // Route to the assigned server instance
                Optional<SftpServerInstance> server = serverInstanceRepository
                        .findByInstanceId(assignedInstance);
                if (server.isPresent() && server.get().isActive()) {
                    SftpServerInstance s = server.get();
                    log.info("SFTP gateway: routing {} → instance {} ({}:{})",
                            username, assignedInstance, s.getInternalHost(), s.getInternalPort());
                    return new RouteDecision(s.getInternalHost(), s.getInternalPort(), false);
                }
                log.warn("SFTP gateway: user {} assigned to instance {} but instance not found/inactive, using default",
                        username, assignedInstance);
            }

            // No assignment or instance not found — use default
            log.info("SFTP gateway: routing {} → default ({}:{})", username, internalSftpHost, internalSftpPort);
            return new RouteDecision(internalSftpHost, internalSftpPort, false);
        }

        return routeToLegacy(username, Protocol.SFTP);
    }

    public RouteDecision routeFtp(String username) {
        boolean isKnown = accountRepository.existsByUsernameAndProtocol(username, Protocol.FTP);
        if (isKnown) {
            log.info("FTP gateway: routing {} → internal ({}:{})", username, internalFtpHost, internalFtpPort);
            return new RouteDecision(internalFtpHost, internalFtpPort, false);
        }
        return routeToLegacy(username, Protocol.FTP);
    }

    private RouteDecision routeToLegacy(String username, Protocol protocol) {
        Optional<LegacyServerConfig> legacy = legacyServerConfigRepository
                .findByProtocolAndActiveTrue(protocol)
                .stream().findFirst();

        if (legacy.isPresent()) {
            log.info("{} gateway: routing unknown user {} → legacy ({}:{})",
                    protocol, username, legacy.get().getHost(), legacy.get().getPort());
            return new RouteDecision(legacy.get().getHost(), legacy.get().getPort(), true);
        }

        log.warn("{} gateway: no legacy server configured for unknown user {}", protocol, username);
        return null; // caller must reject connection
    }

    public record RouteDecision(String host, int port, boolean isLegacy) {}
}
