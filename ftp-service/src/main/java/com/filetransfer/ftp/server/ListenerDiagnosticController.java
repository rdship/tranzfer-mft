package com.filetransfer.ftp.server;

import lombok.RequiredArgsConstructor;
import org.apache.ftpserver.FtpServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live listener state for the FTP service — diagnostic endpoint that reads
 * the in-process registry, not the DB. Use when bind_state in Postgres
 * disagrees with what this node is actually serving.
 */
@RestController
@RequestMapping("/internal/listeners")
@RequiredArgsConstructor
public class ListenerDiagnosticController {

    private final FtpListenerRegistry registry;

    @GetMapping("/live")
    public Map<String, Object> live() {
        List<Map<String, Object>> listeners = new ArrayList<>();
        for (Map.Entry<UUID, FtpServer> e : registry.snapshot().entrySet()) {
            FtpServer ftpd = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("serverInstanceId", e.getKey().toString());
            entry.put("stopped", ftpd.isStopped());
            entry.put("suspended", ftpd.isSuspended());
            listeners.add(entry);
        }
        return Map.of("protocol", "FTP", "count", listeners.size(), "listeners", listeners);
    }
}
