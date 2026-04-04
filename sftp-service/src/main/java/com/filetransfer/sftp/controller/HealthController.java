package com.filetransfer.sftp.controller;

import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final SshServer sshServer;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "sftpServerRunning", sshServer.isStarted(),
                "sftpPort", sshServer.getPort()
        );
    }
}
