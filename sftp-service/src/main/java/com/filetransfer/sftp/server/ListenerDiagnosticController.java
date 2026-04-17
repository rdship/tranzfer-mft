package com.filetransfer.sftp.server;

import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal diagnostic for live listener state. Reflects the actual in-process
 * registry — distinct from the DB-persisted {@code bind_state}, which can lag.
 * Used by UI / Sentinel / ops when the DB bindState disagrees with reality.
 */
@RestController
@RequestMapping("/internal/listeners")
@RequiredArgsConstructor
public class ListenerDiagnosticController {

    private final SftpListenerRegistry registry;

    @GetMapping("/live")
    public Map<String, Object> live() {
        List<Map<String, Object>> listeners = new ArrayList<>();
        for (Map.Entry<UUID, SshServer> e : registry.snapshot().entrySet()) {
            SshServer sshd = e.getValue();
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("serverInstanceId", e.getKey().toString());
            entry.put("port", sshd.getPort());
            entry.put("started", sshd.isStarted());
            entry.put("activeSessions", sshd.getActiveSessions() != null ? sshd.getActiveSessions().size() : 0);
            listeners.add(entry);
        }
        return Map.of("protocol", "SFTP", "count", listeners.size(), "listeners", listeners);
    }
}
