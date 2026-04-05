package com.filetransfer.ftp.controller;

import lombok.RequiredArgsConstructor;
import org.apache.ftpserver.FtpServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final FtpServer ftpServer;

    @Value("${ftp.instance-id:#{null}}")
    private String instanceId;

    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", "UP");
        map.put("ftpServerStopped", ftpServer.isStopped());
        if (instanceId != null) map.put("instanceId", instanceId);
        return map;
    }
}
