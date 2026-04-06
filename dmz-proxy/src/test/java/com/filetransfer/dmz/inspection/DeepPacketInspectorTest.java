package com.filetransfer.dmz.inspection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepPacketInspectorTest {

    private DeepPacketInspector.InspectionConfig defaultConfig;
    private DeepPacketInspector inspector;

    @BeforeEach
    void setUp() {
        defaultConfig = new DeepPacketInspector.InspectionConfig(
            true, true, "TLSv1.2", true, true, List.of(),
            true, 8192, true, true, true
        );
        inspector = new DeepPacketInspector(defaultConfig);
    }

    // ── 1. Disabled ──────────────────────────────────────────────────

    @Test
    void disabled_alwaysAllows() {
        var config = new DeepPacketInspector.InspectionConfig(
            false, true, "TLSv1.2", true, true, List.of(),
            true, 8192, true, true, true
        );
        var dpi = new DeepPacketInspector(config);

        ByteBuf buf = Unpooled.copiedBuffer("SSH-1.99-OpenSSH\r\n", StandardCharsets.US_ASCII);
        try {
            var result = dpi.inspect(buf, "SSH", 22);
            assertTrue(result.allowed());
            assertEquals(DeepPacketInspector.Severity.INFO, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 2. Empty data ────────────────────────────────────────────────

    @Test
    void emptyData_alwaysAllows() {
        ByteBuf buf = Unpooled.EMPTY_BUFFER;
        var result = inspector.inspect(buf, "SSH", 22);
        assertTrue(result.allowed());
    }

    // ── 3. Unknown protocol ──────────────────────────────────────────

    @Test
    void unknownProtocol_noInspection() {
        ByteBuf buf = Unpooled.copiedBuffer("CONNECT mqtt.example.com", StandardCharsets.US_ASCII);
        try {
            var result = inspector.inspect(buf, "MQTT", 1883);
            assertTrue(result.allowed());
            assertTrue(result.detail().contains("No DPI rules"));
        } finally {
            buf.release();
        }
    }

    // ── 4. SSH v1 blocked ────────────────────────────────────────────

    @Test
    void ssh_v1_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer("SSH-1.99-OpenSSH\r\n", StandardCharsets.US_ASCII);
        try {
            var result = inspector.inspect(buf, "SSH", 22);
            assertFalse(result.allowed());
            assertEquals(DeepPacketInspector.Severity.CRITICAL, result.severity());
            assertEquals("ssh_v1_blocked", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 5. SSH v2 allowed ────────────────────────────────────────────

    @Test
    void ssh_v2_allowed() {
        ByteBuf buf = Unpooled.copiedBuffer("SSH-2.0-OpenSSH_8.9\r\n", StandardCharsets.US_ASCII);
        try {
            var result = inspector.inspect(buf, "SSH", 22);
            assertTrue(result.allowed());
            assertEquals("ssh_ok", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 6. SSH attack tool blocked ───────────────────────────────────

    @Test
    void ssh_attackTool_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer("SSH-2.0-libssh-scanner\r\n", StandardCharsets.US_ASCII);
        try {
            var result = inspector.inspect(buf, "SSH", 22);
            assertFalse(result.allowed());
            assertEquals(DeepPacketInspector.Severity.CRITICAL, result.severity());
            assertEquals("ssh_attack_tool", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 7. HTTP SQL injection blocked ────────────────────────────────

    @Test
    void http_sqlInjection_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /search?q=' OR 1=1-- HTTP/1.1\r\nHost: example.com\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_sql_injection", result.finding());
            assertEquals(DeepPacketInspector.Severity.CRITICAL, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 8. HTTP UNION SELECT blocked ─────────────────────────────────

    @Test
    void http_unionSelect_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /data?id=1 UNION ALL SELECT username,password FROM users HTTP/1.1\r\n" +
            "Host: example.com\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_sql_injection", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 9. HTTP command injection blocked ─────────────────────────────

    @Test
    void http_commandInjection_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /api?file=test; cat /etc/passwd HTTP/1.1\r\nHost: example.com\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_command_injection", result.finding());
            assertEquals(DeepPacketInspector.Severity.CRITICAL, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 10. HTTP path traversal blocked ──────────────────────────────

    @Test
    void http_pathTraversal_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /../../../etc/passwd HTTP/1.1\r\nHost: example.com\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_path_traversal", result.finding());
            assertEquals(DeepPacketInspector.Severity.HIGH, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 11. HTTP suspicious User-Agent blocked ───────────────────────

    @Test
    void http_suspiciousUserAgent_blocked() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /index.html HTTP/1.1\r\n" +
            "Host: example.com\r\n" +
            "User-Agent: sqlmap/1.7\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_suspicious_ua", result.finding());
            assertEquals(DeepPacketInspector.Severity.HIGH, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 12. HTTP normal request allowed ──────────────────────────────

    @Test
    void http_normalRequest_allowed() {
        ByteBuf buf = Unpooled.copiedBuffer(
            "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        try {
            var result = inspector.inspect(buf, "HTTP", 80);
            assertTrue(result.allowed());
            assertEquals("http_ok", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 13. HTTP header too large blocked ─────────────────────────────

    @Test
    void http_headerTooLarge_blocked() {
        // Config with a very small max header size
        var smallHeaderConfig = new DeepPacketInspector.InspectionConfig(
            true, true, "TLSv1.2", true, true, List.of(),
            true, 64, true, true, true
        );
        var smallDpi = new DeepPacketInspector(smallHeaderConfig);

        // Build a request whose headers exceed 64 bytes, with no \r\n\r\n terminator
        // so that the "incomplete headers > maxHttpHeaderSize" path triggers
        StringBuilder sb = new StringBuilder();
        sb.append("GET /index.html HTTP/1.1\r\n");
        sb.append("Host: example.com\r\n");
        sb.append("X-Padding: ").append("A".repeat(100)).append("\r\n");

        ByteBuf buf = Unpooled.copiedBuffer(sb.toString(), StandardCharsets.US_ASCII);
        try {
            var result = smallDpi.inspect(buf, "HTTP", 80);
            assertFalse(result.allowed());
            assertEquals("http_header_too_large", result.finding());
            assertEquals(DeepPacketInspector.Severity.MEDIUM, result.severity());
        } finally {
            buf.release();
        }
    }

    // ── 14. TLS not handshake passes through ─────────────────────────

    @Test
    void tls_notHandshake_passesThrough() {
        // First byte is NOT 0x16 (not a handshake)
        byte[] data = new byte[]{0x17, 0x03, 0x03, 0x00, 0x10, 0x01, 0x02, 0x03};
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            var result = inspector.inspect(buf, "TLS", 443);
            assertTrue(result.allowed());
            assertEquals("tls_not_handshake", result.finding());
        } finally {
            buf.release();
        }
    }

    // ── 15. TLS valid ClientHello passes ─────────────────────────────

    @Test
    void tls_validClientHello_passes() {
        // Construct a minimal valid TLS 1.2 ClientHello
        byte[] clientHello = new byte[64];
        clientHello[0] = 0x16;                       // ContentType: Handshake
        clientHello[1] = 0x03; clientHello[2] = 0x03; // Record version: TLS 1.2
        clientHello[3] = 0x00; clientHello[4] = 58;   // Record length: 58 bytes
        clientHello[5] = 0x01;                        // HandshakeType: ClientHello
        // Handshake length (3 bytes)
        clientHello[6] = 0x00; clientHello[7] = 0x00; clientHello[8] = 54;
        // Client version: TLS 1.2
        clientHello[9] = 0x03; clientHello[10] = 0x03;
        // Random (32 bytes) at offsets 11..42 — leave as zeros
        // Session ID length at offset 43
        clientHello[43] = 0;                          // No session ID
        // Cipher suites length at offset 44-45
        clientHello[44] = 0x00; clientHello[45] = 0x02; // 2 bytes = 1 cipher suite
        // A strong cipher suite: TLS_AES_128_GCM_SHA256 = 0x1301
        clientHello[46] = 0x13; clientHello[47] = 0x01;
        // Remaining bytes left as zeros (compression methods, etc.)

        ByteBuf buf = Unpooled.wrappedBuffer(clientHello);
        try {
            var result = inspector.inspect(buf, "TLS", 443);
            assertTrue(result.allowed());
            assertEquals("tls_ok", result.finding());
            assertTrue(result.detail().contains("TLSv1.2"));
        } finally {
            buf.release();
        }
    }
}
