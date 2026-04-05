package com.filetransfer.ftp.controller;

import com.filetransfer.ftp.connection.ConnectionTracker;
import com.filetransfer.ftp.security.LoginLockoutService;
import com.filetransfer.ftp.server.FtpsConfig;
import lombok.RequiredArgsConstructor;
import org.apache.ftpserver.FtpServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Enhanced health endpoint reporting active connections, disk usage,
 * uptime, TLS status, and locked accounts.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class HealthController {

    private final FtpServer ftpServer;
    private final ConnectionTracker connectionTracker;
    private final LoginLockoutService lockoutService;
    private final FtpsConfig ftpsConfig;

    @Value("${ftp.instance-id:#{null}}")
    private String instanceId;

    @Value("${ftp.home-base:/data/ftp}")
    private String homeBase;

    @Value("${ftp.active.enabled:true}")
    private boolean activeModeEnabled;

    private final Instant startTime = Instant.now();

    /**
     * Return detailed health information including active connections,
     * disk usage, uptime, TLS configuration, and locked accounts.
     *
     * @return a map of health metrics
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        var map = new LinkedHashMap<String, Object>();
        map.put("status", ftpServer.isStopped() ? "DOWN" : "UP");
        map.put("ftpServerStopped", ftpServer.isStopped());
        map.put("ftpServerSuspended", ftpServer.isSuspended());
        if (instanceId != null) map.put("instanceId", instanceId);

        // Uptime
        Duration uptime = Duration.between(startTime, Instant.now());
        map.put("uptimeSeconds", uptime.getSeconds());
        map.put("uptimeHuman", formatDuration(uptime));

        // Active connections
        Map<String, Object> connections = new LinkedHashMap<>();
        connections.put("active", connectionTracker.getActiveCount());
        connections.put("maxTotal", connectionTracker.getMaxTotal());
        connections.put("maxPerUser", connectionTracker.getMaxPerUser());
        connections.put("maxPerIp", connectionTracker.getMaxPerIp());
        connections.put("perUser", connectionTracker.getPerUserSnapshot());
        connections.put("perIp", connectionTracker.getPerIpSnapshot());
        map.put("connections", connections);

        // TLS status
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", ftpsConfig.isEnabled());
        if (ftpsConfig.isEnabled()) {
            tls.put("protocol", ftpsConfig.getProtocol());
            tls.put("implicit", ftpsConfig.isImplicit());
            tls.put("requireTls", ftpsConfig.isRequireTls());
            tls.put("requireDataTls", ftpsConfig.isRequireDataTls());
            tls.put("clientAuth", ftpsConfig.getClientAuthMode());
            tls.put("keystoreType", ftpsConfig.getKeystoreType());
            Set<String> ciphers = ftpsConfig.getEnabledCipherSuites();
            if (!ciphers.isEmpty()) {
                tls.put("cipherSuites", ciphers);
            }
            Set<String> protocols = ftpsConfig.getEnabledProtocols();
            if (!protocols.isEmpty()) {
                tls.put("enabledProtocols", protocols);
            }
            // Certificate details (subject, expiry, etc.)
            Map<String, Object> certInfo = ftpsConfig.getCertificateInfo();
            if (!certInfo.isEmpty()) {
                tls.put("certificates", certInfo);
            }
        }
        map.put("tls", tls);

        // Active mode status
        map.put("activeModeEnabled", activeModeEnabled);

        // Disk usage
        File homeDir = new File(homeBase);
        if (homeDir.exists()) {
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("path", homeBase);
            disk.put("totalBytes", homeDir.getTotalSpace());
            disk.put("freeBytes", homeDir.getFreeSpace());
            disk.put("usableBytes", homeDir.getUsableSpace());
            long total = homeDir.getTotalSpace();
            if (total > 0) {
                double usedPercent = 100.0 * (total - homeDir.getUsableSpace()) / total;
                disk.put("usedPercent", Math.round(usedPercent * 10.0) / 10.0);
            }
            map.put("disk", disk);
        }

        // Locked accounts
        Set<String> locked = lockoutService.getLockedAccounts();
        map.put("lockedAccounts", locked);
        map.put("lockedAccountCount", locked.size());

        // JVM info
        map.put("jvmUptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());

        return map;
    }

    private String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
