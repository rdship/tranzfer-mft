package com.filetransfer.ftp.controller;

import lombok.RequiredArgsConstructor;
import org.apache.ftpserver.FtpServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final FtpServer ftpServer;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "ftpServerStopped", ftpServer.isStopped()
        );
    }
}
