package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.proxy.ProtocolThreatDetector.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolThreatDetectorTest {

    private ProtocolThreatDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ProtocolThreatDetector();
    }

    @Test
    void detectsSuspiciousSshClient() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 22, "SSH", 100, 200, 5000,
            Map.of("clientBanner", "SSH-2.0-libssh-0.7.0"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "SCAN".equals(s.threatType())));
    }

    @Test
    void detectsSshBannerGrab() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 22, "SSH", 50, 0, 200);
        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s ->
            "SCAN".equals(s.threatType()) && s.description().contains("banner grab")));
    }

    @Test
    void detectsFtpBounceAttack() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 21, "FTP", 100, 200, 5000,
            Map.of("lastCommand", "PORT"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "ABUSE".equals(s.threatType())));
    }

    @Test
    void detectsFtpDirectoryTraversal() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 21, "FTP", 100, 200, 5000,
            Map.of("path", "../../etc/passwd"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "EXPLOIT".equals(s.threatType())));
    }

    @Test
    void detectsSuspiciousHttpPath() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 80, "HTTP", 500, 0, 1000,
            Map.of("path", "/wp-admin/login.php", "method", "GET", "userAgent", "Mozilla/5.0"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "SCAN".equals(s.threatType())));
    }

    @Test
    void detectsSqlInjectionInUrl() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 80, "HTTP", 300, 0, 500,
            Map.of("path", "/users?id=1'; DROP TABLE users--", "method", "GET", "userAgent", "curl"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "EXPLOIT".equals(s.threatType())));
    }

    @Test
    void detectsScanningUserAgent() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 80, "HTTP", 200, 0, 300,
            Map.of("path", "/", "method", "GET", "userAgent", "nikto/2.1.5"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s ->
            "SCAN".equals(s.threatType()) && s.description().contains("user agent")));
    }

    @Test
    void detectsTlsDowngrade() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 443, "TLS", 0, 0, 500,
            Map.of("tlsVersion", "SSLv3", "sni", "example.com"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s ->
            "EXPLOIT".equals(s.threatType()) && s.description().contains("Deprecated TLS")));
    }

    @Test
    void detectsZeroBytePortProbe() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 9999, "TCP", 0, 0, 100);
        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s ->
            "SCAN".equals(s.threatType()) && s.description().contains("port probe")));
    }

    @Test
    void unknownProtocolFlagged() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 12345, null, 100, 50, 1000);
        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.stream().anyMatch(s -> "ANOMALY".equals(s.threatType())));
    }

    @Test
    void detectsBruteForce() {
        ThreatSignal signal = detector.detectBruteForce("10.0.0.1", "SSH", 10, 5);
        assertNotNull(signal);
        assertEquals("BRUTE_FORCE", signal.threatType());
        assertTrue(signal.severity() >= 50);
    }

    @Test
    void noBruteForceUnderThreshold() {
        ThreatSignal signal = detector.detectBruteForce("10.0.0.1", "SSH", 3, 5);
        assertNull(signal);
    }

    @Test
    void detectsPortScan() {
        ThreatSignal signal = detector.detectPortScan("10.0.0.1",
            java.util.Set.of(22, 80, 443, 8080, 3306, 5432), 10);
        assertNotNull(signal);
        assertEquals("SCAN", signal.threatType());
    }

    @Test
    void cleanSshConnectionProducesNoSignals() {
        ConnectionEvent event = new ConnectionEvent("10.0.0.1", 22, "SSH", 5000, 10000, 60000,
            Map.of("clientBanner", "SSH-2.0-OpenSSH_8.9"));

        List<ThreatSignal> signals = detector.analyze(event);
        assertTrue(signals.isEmpty());
    }
}
