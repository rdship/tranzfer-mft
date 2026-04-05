package com.filetransfer.ai.service.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Protocol-specific threat detection.
 * Analyzes protocol signatures, connection patterns, and known attack vectors
 * for SSH, FTP, HTTP/S, and generic TCP protocols.
 *
 * Product-agnostic: detects threats based on protocol behavior, not business logic.
 */
@Slf4j
@Service
public class ProtocolThreatDetector {

    /** Threat detection result */
    public record ThreatSignal(
        String threatType,      // BRUTE_FORCE, SCAN, EXPLOIT, ABUSE, ANOMALY
        String protocol,
        int severity,           // 0-100
        String description,
        Map<String, Object> evidence
    ) {}

    // ── Protocol Constants ─────────────────────────────────────────────

    // SSH banner patterns that indicate scanning tools
    private static final List<String> SUSPICIOUS_SSH_CLIENTS = List.of(
        "libssh", "paramiko", "go-ssh", "ncrack", "medusa", "hydra",
        "nmap", "masscan", "zmap", "putty_fuzzer", "dropbear_fuzzer"
    );

    // Suspicious HTTP paths indicating probing
    private static final List<String> SUSPICIOUS_HTTP_PATHS = List.of(
        "/.env", "/wp-admin", "/wp-login", "/phpmyadmin", "/.git",
        "/admin", "/actuator", "/debug", "/console", "/shell",
        "/cgi-bin", "/../", "/etc/passwd", "/proc/self",
        "/api/v1/exec", "/remote/login", "/telescope", "/.aws"
    );

    // Suspicious FTP commands indicating abuse
    private static final List<String> FTP_ABUSE_COMMANDS = List.of(
        "PORT", "EPRT"  // FTP bounce attack vectors
    );

    // Known malicious TLS fingerprints (JA3-like signatures)
    private static final Set<String> SUSPICIOUS_TLS_PATTERNS = Set.of(
        "empty_sni",           // No SNI = potential scanning
        "downgrade_attempt",   // Trying to force weaker cipher
        "expired_client_cert", // Presenting expired certs
        "self_signed_client"   // Self-signed client certs in mTLS
    );

    // ── Core Detection Methods ─────────────────────────────────────────

    /**
     * Analyze a connection event for protocol-specific threats.
     * Returns list of detected threat signals (may be empty = clean).
     */
    public List<ThreatSignal> analyze(ConnectionEvent event) {
        List<ThreatSignal> signals = new ArrayList<>();

        if (event.protocol() == null) {
            signals.add(new ThreatSignal("ANOMALY", "UNKNOWN", 30,
                "Unidentified protocol on port " + event.targetPort(),
                Map.of("targetPort", event.targetPort())));
            return signals;
        }

        switch (event.protocol().toUpperCase()) {
            case "SSH" -> analyzeSSH(event, signals);
            case "FTP" -> analyzeFTP(event, signals);
            case "HTTP" -> analyzeHTTP(event, signals);
            case "TLS", "HTTPS", "FTPS" -> analyzeTLS(event, signals);
            default -> analyzeGenericTCP(event, signals);
        }

        return signals;
    }

    /**
     * Check if connection pattern indicates a port scan.
     */
    public ThreatSignal detectPortScan(String ip, Set<Integer> portsAttempted, int timeWindowMinutes) {
        if (portsAttempted.size() >= 5) {
            return new ThreatSignal("SCAN", "TCP", 70 + Math.min(30, portsAttempted.size()),
                "Port scan detected: " + portsAttempted.size() + " ports in " + timeWindowMinutes + " min",
                Map.of("ports", new ArrayList<>(portsAttempted), "ip", ip));
        }
        return null;
    }

    /**
     * Check if failure pattern indicates brute force.
     */
    public ThreatSignal detectBruteForce(String ip, String protocol, int failuresInWindow, int windowMinutes) {
        int threshold = switch (protocol.toUpperCase()) {
            case "SSH" -> 5;   // SSH brute force is common, lower threshold
            case "FTP" -> 8;
            case "HTTP", "HTTPS" -> 15;
            default -> 10;
        };

        if (failuresInWindow >= threshold) {
            int severity = Math.min(100, 50 + (failuresInWindow - threshold) * 5);
            return new ThreatSignal("BRUTE_FORCE", protocol, severity,
                failuresInWindow + " failed connections in " + windowMinutes + " min (threshold: " + threshold + ")",
                Map.of("ip", ip, "failures", failuresInWindow, "threshold", threshold));
        }
        return null;
    }

    // ── Protocol-Specific Analysis ─────────────────────────────────────

    private void analyzeSSH(ConnectionEvent event, List<ThreatSignal> signals) {
        String banner = event.metadata().getOrDefault("clientBanner", "").toString().toLowerCase();

        // Check for known scanning/attack tools
        for (String suspicious : SUSPICIOUS_SSH_CLIENTS) {
            if (banner.contains(suspicious)) {
                signals.add(new ThreatSignal("SCAN", "SSH", 60,
                    "Suspicious SSH client: " + suspicious,
                    Map.of("clientBanner", banner, "tool", suspicious)));
                break;
            }
        }

        // Very short connections (< 1 sec) with no data = banner grab
        if (event.durationMs() > 0 && event.durationMs() < 1000 && event.bytesIn() < 100) {
            signals.add(new ThreatSignal("SCAN", "SSH", 40,
                "SSH banner grab (short connection, minimal data)",
                Map.of("durationMs", event.durationMs(), "bytesIn", event.bytesIn())));
        }

        // Rapid reconnection (metadata from ConnectionTracker)
        Object rapidReconnect = event.metadata().get("rapidReconnect");
        if (Boolean.TRUE.equals(rapidReconnect)) {
            signals.add(new ThreatSignal("BRUTE_FORCE", "SSH", 55,
                "Rapid SSH reconnection pattern",
                Map.of("ip", event.sourceIp())));
        }
    }

    private void analyzeFTP(ConnectionEvent event, List<ThreatSignal> signals) {
        // FTP bounce attack detection
        String lastCommand = Objects.toString(event.metadata().get("lastCommand"), "");
        if (FTP_ABUSE_COMMANDS.contains(lastCommand.toUpperCase())) {
            signals.add(new ThreatSignal("ABUSE", "FTP", 70,
                "Potential FTP bounce attack: " + lastCommand + " command detected",
                Map.of("command", lastCommand)));
        }

        // Anonymous access attempt
        String username = Objects.toString(event.metadata().get("username"), "");
        if ("anonymous".equalsIgnoreCase(username) || "ftp".equalsIgnoreCase(username)) {
            signals.add(new ThreatSignal("SCAN", "FTP", 35,
                "Anonymous FTP access attempt",
                Map.of("username", username)));
        }

        // Directory traversal in path
        String path = Objects.toString(event.metadata().get("path"), "");
        if (path.contains("..") || path.contains("/etc/") || path.contains("\\")) {
            signals.add(new ThreatSignal("EXPLOIT", "FTP", 75,
                "Directory traversal attempt in FTP path",
                Map.of("path", path)));
        }
    }

    private void analyzeHTTP(ConnectionEvent event, List<ThreatSignal> signals) {
        String path = Objects.toString(event.metadata().get("path"), "");
        String method = Objects.toString(event.metadata().get("method"), "");
        String userAgent = Objects.toString(event.metadata().get("userAgent"), "").toLowerCase();

        // Suspicious path probing
        for (String suspicious : SUSPICIOUS_HTTP_PATHS) {
            if (path.toLowerCase().contains(suspicious.toLowerCase())) {
                signals.add(new ThreatSignal("SCAN", "HTTP", 55,
                    "Suspicious HTTP path probe: " + suspicious,
                    Map.of("path", path, "pattern", suspicious)));
                break;
            }
        }

        // SQL injection patterns
        if (path.matches(".*['\";]\\s*(OR|AND|SELECT|UNION|DROP|INSERT|UPDATE|DELETE).*")) {
            signals.add(new ThreatSignal("EXPLOIT", "HTTP", 85,
                "Potential SQL injection in URL",
                Map.of("path", path)));
        }

        // Command injection patterns
        if (path.matches(".*[;|`$].*") || path.contains("$(") || path.contains("&&")) {
            signals.add(new ThreatSignal("EXPLOIT", "HTTP", 80,
                "Potential command injection in URL",
                Map.of("path", path)));
        }

        // Suspicious user agents
        if (userAgent.isEmpty() || userAgent.contains("nikto") || userAgent.contains("sqlmap")
                || userAgent.contains("nmap") || userAgent.contains("masscan")
                || userAgent.contains("dirbuster") || userAgent.contains("gobuster")) {
            signals.add(new ThreatSignal("SCAN", "HTTP",
                userAgent.isEmpty() ? 25 : 65,
                "Suspicious HTTP user agent: " + (userAgent.isEmpty() ? "(empty)" : userAgent),
                Map.of("userAgent", userAgent)));
        }

        // Oversized request (potential buffer overflow attempt)
        if (event.bytesIn() > 10_000_000) {  // 10MB+
            signals.add(new ThreatSignal("ABUSE", "HTTP", 50,
                "Oversized HTTP request: " + event.bytesIn() + " bytes",
                Map.of("bytesIn", event.bytesIn())));
        }
    }

    private void analyzeTLS(ConnectionEvent event, List<ThreatSignal> signals) {
        String sni = Objects.toString(event.metadata().get("sni"), "");
        String tlsVersion = Objects.toString(event.metadata().get("tlsVersion"), "");

        // Missing SNI (potential scanning)
        if (sni.isEmpty()) {
            signals.add(new ThreatSignal("SCAN", "TLS", 25,
                "TLS connection without SNI",
                Map.of("targetPort", event.targetPort())));
        }

        // TLS downgrade attempt
        if ("SSLv3".equals(tlsVersion) || "TLSv1.0".equals(tlsVersion)) {
            signals.add(new ThreatSignal("EXPLOIT", "TLS", 60,
                "Deprecated TLS version: " + tlsVersion,
                Map.of("tlsVersion", tlsVersion)));
        }

        // Also run HTTP analysis if this is HTTPS
        if ("HTTPS".equalsIgnoreCase(event.protocol()) || "FTPS".equalsIgnoreCase(event.protocol())) {
            analyzeHTTP(event, signals);
        }
    }

    private void analyzeGenericTCP(ConnectionEvent event, List<ThreatSignal> signals) {
        // Zero-byte connections = port probe
        if (event.bytesIn() == 0 && event.bytesOut() == 0 && event.durationMs() < 2000) {
            signals.add(new ThreatSignal("SCAN", "TCP", 35,
                "Zero-byte TCP connection (port probe)",
                Map.of("targetPort", event.targetPort(), "durationMs", event.durationMs())));
        }

        // Very large data transfer on non-standard port
        if (event.bytesIn() > 100_000_000) {  // 100MB+
            signals.add(new ThreatSignal("ANOMALY", "TCP", 30,
                "Large data transfer: " + (event.bytesIn() / 1_000_000) + " MB",
                Map.of("bytesIn", event.bytesIn())));
        }
    }

    // ── Event Model ────────────────────────────────────────────────────

    public record ConnectionEvent(
        String sourceIp,
        int targetPort,
        String protocol,
        long bytesIn,
        long bytesOut,
        long durationMs,
        Map<String, Object> metadata
    ) {
        public ConnectionEvent(String sourceIp, int targetPort, String protocol,
                               long bytesIn, long bytesOut, long durationMs) {
            this(sourceIp, targetPort, protocol, bytesIn, bytesOut, durationMs, Map.of());
        }
    }
}
