package com.filetransfer.gateway.controller;

import com.filetransfer.shared.entity.core.LegacyServerConfig;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.LegacyServerConfigRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Gateway control API.
 *
 * GET  /internal/gateway/status          — current gateway status and routing config
 * GET  /internal/gateway/legacy-servers  — configured legacy server fallbacks
 * GET  /internal/gateway/routes          — full route table with server instances
 * GET  /internal/gateway/stats           — routing statistics
 */
@RestController
@RequestMapping("/internal/gateway")
@RequiredArgsConstructor
public class GatewayStatusController {

    private final SshServer sftpGatewayServer;
    private final LegacyServerConfigRepository legacyRepo;
    private final ServerInstanceRepository serverInstanceRepo;
    private final TransferAccountRepository accountRepo;

    @Value("${gateway.sftp.port:2220}")
    private int sftpPort;

    @Value("${gateway.ftp.port:2121}")
    private int ftpPort;

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

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('INTERNAL', 'ADMIN')")
    public Map<String, Object> status() {
        return Map.of(
                "sftpGatewayPort", sftpPort,
                "ftpGatewayPort", ftpPort,
                "sftpGatewayRunning", sftpGatewayServer.isStarted()
        );
    }

    @GetMapping("/legacy-servers")
    @PreAuthorize("hasAnyRole('INTERNAL', 'ADMIN')")
    public List<LegacyServerConfig> legacyServers(
            @RequestParam(required = false) Protocol protocol) {
        return protocol != null
                ? legacyRepo.findByProtocolAndActiveTrue(protocol)
                : legacyRepo.findAll();
    }

    /** Full route table: default services + server instances + legacy fallbacks */
    @GetMapping("/routes")
    @PreAuthorize("hasAnyRole('INTERNAL', 'ADMIN')")
    public Map<String, Object> routes() {
        // Default internal routes
        List<Map<String, Object>> defaultRoutes = List.of(
                Map.of("name", "SFTP Default", "protocol", "SFTP",
                        "targetHost", internalSftpHost, "targetPort", internalSftpPort,
                        "type", "INTERNAL", "active", true),
                Map.of("name", "FTP Default", "protocol", "FTP",
                        "targetHost", internalFtpHost, "targetPort", internalFtpPort,
                        "type", "INTERNAL", "active", true),
                Map.of("name", "FTP-Web Default", "protocol", "FTP_WEB",
                        "targetHost", internalFtpWebHost, "targetPort", internalFtpWebPort,
                        "type", "INTERNAL", "active", true)
        );

        // Server instances as additional routes
        List<Map<String, Object>> instanceRoutes = serverInstanceRepo.findByActiveTrue().stream()
                .map(si -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", si.getName());
                    r.put("instanceId", si.getInstanceId());
                    r.put("protocol", si.getProtocol().name());
                    r.put("targetHost", si.getInternalHost());
                    r.put("targetPort", si.getInternalPort());
                    r.put("externalHost", si.getExternalHost());
                    r.put("externalPort", si.getExternalPort());
                    r.put("useProxy", si.isUseProxy());
                    r.put("maxConnections", si.getMaxConnections());
                    r.put("type", "INSTANCE");
                    r.put("active", si.isActive());
                    return r;
                }).collect(Collectors.toList());

        // Legacy fallback servers
        List<Map<String, Object>> legacyRoutes = legacyRepo.findAll().stream()
                .map(ls -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("name", ls.getName());
                    r.put("protocol", ls.getProtocol().name());
                    r.put("targetHost", ls.getHost());
                    r.put("targetPort", ls.getPort());
                    r.put("type", "LEGACY");
                    r.put("active", ls.isActive());
                    return r;
                }).collect(Collectors.toList());

        return Map.of(
                "defaultRoutes", defaultRoutes,
                "instanceRoutes", instanceRoutes,
                "legacyRoutes", legacyRoutes,
                "totalRoutes", defaultRoutes.size() + instanceRoutes.size() + legacyRoutes.size()
        );
    }

    /** Gateway statistics: account counts, instance counts, port mapping */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('INTERNAL', 'ADMIN')")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sftpGatewayPort", sftpPort);
        result.put("ftpGatewayPort", ftpPort);
        result.put("sftpGatewayRunning", sftpGatewayServer.isStarted());

        // Counts
        List<ServerInstance> instances = serverInstanceRepo.findByActiveTrue();
        Map<String, Long> instancesByProtocol = instances.stream()
                .collect(Collectors.groupingBy(si -> si.getProtocol().name(), Collectors.counting()));
        result.put("activeInstances", instances.size());
        result.put("instancesByProtocol", instancesByProtocol);

        long legacyCount = legacyRepo.findAll().stream().filter(LegacyServerConfig::isActive).count();
        result.put("legacyServers", legacyCount);

        long totalAccounts = accountRepo.count();
        result.put("totalAccounts", totalAccounts);

        return result;
    }
}
