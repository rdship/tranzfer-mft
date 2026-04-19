package com.filetransfer.gateway.controller;

import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R131: UI-facing alias for the admin health badge. Every admin page
 * polls {@code /api/gateway/status} on load (see
 * {@code ui-service/src/context/ServiceContext.jsx:28}); the R130 UI
 * audit caught 46 silent 404s across a single login session because
 * the real endpoint was {@link GatewayStatusController}'s {@code
 * /internal/gateway/status}, which the shared security filter's
 * {@code /internal/**} matcher restricts to {@code ROLE_INTERNAL}.
 *
 * <p>This alias lives outside {@code /internal/} so the filter chain
 * admits authenticated admin JWTs; method-level {@code @PreAuthorize}
 * then scopes it to ADMIN / OPERATOR. Body shape matches the
 * internal endpoint so the UI gets the same JSON.
 */
@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
public class GatewayPublicStatusController {

    private final SshServer sftpGatewayServer;

    @Value("${gateway.sftp.port:2220}")
    private int sftpPort;

    @Value("${gateway.ftp.port:2121}")
    private int ftpPort;

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'INTERNAL')")
    public Map<String, Object> status() {
        return Map.of(
                "sftpGatewayPort", sftpPort,
                "ftpGatewayPort", ftpPort,
                "sftpGatewayRunning", sftpGatewayServer.isStarted()
        );
    }
}
