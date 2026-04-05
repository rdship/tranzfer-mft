package com.filetransfer.sftp.controller;

import com.filetransfer.sftp.audit.AuditEventLogger;
import com.filetransfer.sftp.security.LoginAttemptTracker;
import com.filetransfer.sftp.session.ConnectionManager;
import lombok.RequiredArgsConstructor;
import org.apache.sshd.server.SshServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enhanced health endpoint reporting active connections, disk usage, uptime,
 * locked accounts, and authentication statistics.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final SshServer sshServer;
    private final ConnectionManager connectionManager;
    private final LoginAttemptTracker loginAttemptTracker;
    private final AuditEventLogger auditEventLogger;

    @org.springframework.beans.factory.annotation.Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    @org.springframework.beans.factory.annotation.Value("${sftp.home-base:/data/sftp}")
    private String homeBase;

    private final Instant startTime = Instant.now();

    /**
     * Returns comprehensive health information including server status,
     * active connections, disk usage, uptime, locked accounts, and auth stats.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", "UP");
        map.put("sftpServerRunning", sshServer.isStarted());
        map.put("sftpPort", sshServer.getPort());
        if (instanceId != null) map.put("instanceId", instanceId);

        // Uptime
        Duration uptime = Duration.between(startTime, Instant.now());
        map.put("uptimeSeconds", uptime.getSeconds());
        map.put("uptimeFormatted", formatDuration(uptime));

        // Active connections
        Map<String, Object> connections = new LinkedHashMap<>();
        connections.put("active", connectionManager.getActiveConnectionCount());
        connections.put("maxGlobal", connectionManager.getMaxConnections());
        connections.put("maxPerUser", connectionManager.getMaxConnectionsPerUser());
        connections.put("perUser", connectionManager.getPerUserConnectionCounts());
        map.put("connections", connections);

        // Disk usage
        Map<String, Object> disk = new LinkedHashMap<>();
        File homeDir = new File(homeBase);
        if (homeDir.exists()) {
            disk.put("totalBytes", homeDir.getTotalSpace());
            disk.put("freeBytes", homeDir.getFreeSpace());
            disk.put("usableBytes", homeDir.getUsableSpace());
            long totalMb = homeDir.getTotalSpace() / (1024 * 1024);
            long freeMb = homeDir.getFreeSpace() / (1024 * 1024);
            disk.put("totalMB", totalMb);
            disk.put("freeMB", freeMb);
            if (totalMb > 0) {
                disk.put("usedPercent", Math.round((1.0 - (double) freeMb / totalMb) * 100));
            }
        } else {
            disk.put("status", "HOME_DIR_NOT_FOUND");
        }
        map.put("disk", disk);

        // Locked accounts
        Map<String, Object> lockout = new LinkedHashMap<>();
        lockout.put("lockedAccounts", loginAttemptTracker.getLockedAccounts());
        lockout.put("lockedCount", loginAttemptTracker.getLockedAccounts().size());
        lockout.put("maxFailedAttempts", loginAttemptTracker.getMaxFailedAttempts());
        lockout.put("lockoutDurationSeconds", loginAttemptTracker.getLockoutDurationSeconds());
        map.put("lockout", lockout);

        // Auth and transfer stats
        map.put("stats", auditEventLogger.getStats());

        // JVM memory
        Map<String, Object> memory = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();
        memory.put("heapUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("heapMaxMB", runtime.maxMemory() / (1024 * 1024));
        map.put("memory", memory);

        return map;
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        if (days > 0) return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }
}
