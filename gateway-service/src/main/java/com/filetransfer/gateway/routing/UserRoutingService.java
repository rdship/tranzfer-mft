package com.filetransfer.gateway.routing;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Decides where to route a connecting user:
 *  - Known user  → internal sftp-service / ftp-service
 *  - Unknown user → configured legacy server
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoutingService {

    private final TransferAccountRepository accountRepository;
    private final LegacyServerConfigRepository legacyServerConfigRepository;

    @Value("${gateway.internal-sftp-host:sftp-service}")
    private String internalSftpHost;

    @Value("${gateway.internal-sftp-port:2222}")
    private int internalSftpPort;

    @Value("${gateway.internal-ftp-host:ftp-service}")
    private String internalFtpHost;

    @Value("${gateway.internal-ftp-port:21}")
    private int internalFtpPort;

    public RouteDecision routeSftp(String username) {
        boolean isKnown = accountRepository.existsByUsernameAndProtocol(username, Protocol.SFTP);
        if (isKnown) {
            log.info("SFTP gateway: routing {} → internal ({}:{})", username, internalSftpHost, internalSftpPort);
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
