package com.filetransfer.gateway.controller;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Gateway control API.
 *
 * GET  /internal/gateway/status   — current gateway status and routing config
 * GET  /internal/gateway/routes   — active routing rules (internal vs legacy)
 */
@RestController
@RequestMapping("/internal/gateway")
@RequiredArgsConstructor
public class GatewayStatusController {

    private final SshServer sftpGatewayServer;
    private final LegacyServerConfigRepository legacyRepo;

    @Value("${gateway.sftp.port:2220}")
    private int sftpPort;

    @Value("${gateway.ftp.port:2121}")
    private int ftpPort;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "sftpGatewayPort", sftpPort,
                "ftpGatewayPort", ftpPort,
                "sftpGatewayRunning", sftpGatewayServer.isStarted()
        );
    }

    @GetMapping("/legacy-servers")
    public List<LegacyServerConfig> legacyServers(@RequestParam(required = false) Protocol protocol) {
        return protocol != null
                ? legacyRepo.findByProtocolAndActiveTrue(protocol)
                : legacyRepo.findAll();
    }
}
