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

    @org.springframework.beans.factory.annotation.Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("status", "UP");
        map.put("sftpServerRunning", sshServer.isStarted());
        map.put("sftpPort", sshServer.getPort());
        if (instanceId != null) map.put("instanceId", instanceId);
        return map;
    }
}
