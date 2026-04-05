package com.filetransfer.dmz.security;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Protocol Detector — identifies the protocol from the first bytes of a connection.
 * Zero-copy analysis: reads ByteBuf without consuming it.
 *
 * Detects:
 * - SSH (banner: "SSH-")
 * - FTP (server 220 banner or client commands)
 * - HTTP (GET/POST/PUT/DELETE/HEAD/OPTIONS/PATCH/CONNECT)
 * - TLS/SSL (ClientHello: 0x16 0x03)
 * - FTPS (TLS on port 990 or AUTH TLS)
 *
 * Product-agnostic: works with any TCP proxy.
 */
@Slf4j
public class ProtocolDetector {

    public enum Protocol {
        SSH,
        FTP,
        FTPS,
        HTTP,
        TLS,     // Generic TLS (could be HTTPS, FTPS implicit, etc.)
        UNKNOWN
    }

    public record DetectionResult(
        Protocol protocol,
        int confidence,        // 0-100
        String detail          // e.g., SSH version, HTTP method, TLS version
    ) {}

    // Minimum bytes needed for reliable detection
    private static final int MIN_BYTES = 3;

    /**
     * Detect protocol from the first bytes of a connection.
     * Does NOT consume bytes from the buffer (uses getBytes/getByte).
     *
     * @param buf    the first bytes received
     * @param port   the listen port (helps disambiguate, e.g., 990 = FTPS)
     * @return detection result
     */
    public static DetectionResult detect(ByteBuf buf, int port) {
        int readable = buf.readableBytes();
        if (readable < MIN_BYTES) {
            return new DetectionResult(Protocol.UNKNOWN, 0, "insufficient_data");
        }

        int readerIndex = buf.readerIndex();

        // ── TLS/SSL ClientHello ───────────────────────────────────────
        // First byte 0x16 (handshake), second byte 0x03 (TLS), third byte version
        byte b0 = buf.getByte(readerIndex);
        byte b1 = buf.getByte(readerIndex + 1);

        if (b0 == 0x16 && b1 == 0x03) {
            byte tlsMinor = buf.getByte(readerIndex + 2);
            String tlsVersion = switch (tlsMinor) {
                case 0x00 -> "SSLv3";
                case 0x01 -> "TLSv1.0";
                case 0x02 -> "TLSv1.1";
                case 0x03 -> "TLSv1.2";
                case 0x04 -> "TLSv1.3";
                default -> "TLS_unknown";
            };

            // Disambiguate: FTPS implicit on port 990
            if (port == 990) {
                return new DetectionResult(Protocol.FTPS, 95, "implicit_ftps:" + tlsVersion);
            }
            // Port 443 or 8443 likely HTTPS, but we report as TLS
            return new DetectionResult(Protocol.TLS, 95, tlsVersion);
        }

        // ── SSLv2 ClientHello (legacy) ────────────────────────────────
        if (readable >= 3 && (b0 & 0x80) != 0 && buf.getByte(readerIndex + 2) == 0x01) {
            return new DetectionResult(Protocol.TLS, 70, "SSLv2_compat");
        }

        // ── Text-based protocol detection ─────────────────────────────
        int textLen = Math.min(readable, 64);
        byte[] textBytes = new byte[textLen];
        buf.getBytes(readerIndex, textBytes);
        String text = new String(textBytes, StandardCharsets.US_ASCII);
        String textUpper = text.toUpperCase();

        // SSH: starts with "SSH-"
        if (textUpper.startsWith("SSH-")) {
            String version = text.contains("\r") ? text.substring(0, text.indexOf('\r'))
                           : text.contains("\n") ? text.substring(0, text.indexOf('\n'))
                           : text.substring(0, Math.min(text.length(), 40));
            return new DetectionResult(Protocol.SSH, 99, version.trim());
        }

        // FTP server banner: starts with "220 " or "220-"
        if (textUpper.startsWith("220 ") || textUpper.startsWith("220-")) {
            return new DetectionResult(Protocol.FTP, 90, "server_banner");
        }

        // FTP client commands
        if (textUpper.startsWith("USER ") || textUpper.startsWith("AUTH TLS")
                || textUpper.startsWith("AUTH SSL") || textUpper.startsWith("FEAT")) {
            if (textUpper.startsWith("AUTH TLS") || textUpper.startsWith("AUTH SSL")) {
                return new DetectionResult(Protocol.FTPS, 85, "explicit_ftps");
            }
            return new DetectionResult(Protocol.FTP, 80, "client_command");
        }

        // HTTP methods
        if (textUpper.startsWith("GET ") || textUpper.startsWith("POST ")
                || textUpper.startsWith("PUT ") || textUpper.startsWith("DELETE ")
                || textUpper.startsWith("HEAD ") || textUpper.startsWith("OPTIONS ")
                || textUpper.startsWith("PATCH ") || textUpper.startsWith("CONNECT ")) {
            String method = text.split("\\s")[0];
            return new DetectionResult(Protocol.HTTP, 95, "method:" + method);
        }

        // HTTP response
        if (textUpper.startsWith("HTTP/")) {
            return new DetectionResult(Protocol.HTTP, 90, "response");
        }

        // ── Port-based fallback ───────────────────────────────────────
        return switch (port) {
            case 22, 2222 -> new DetectionResult(Protocol.SSH, 30, "port_based");
            case 21 -> new DetectionResult(Protocol.FTP, 30, "port_based");
            case 990 -> new DetectionResult(Protocol.FTPS, 30, "port_based");
            case 80, 8080 -> new DetectionResult(Protocol.HTTP, 30, "port_based");
            case 443, 8443 -> new DetectionResult(Protocol.TLS, 30, "port_based");
            default -> new DetectionResult(Protocol.UNKNOWN, 0, "unrecognized");
        };
    }

    /**
     * Map protocol enum to string for AI engine communication.
     */
    public static String protocolToString(Protocol protocol) {
        return switch (protocol) {
            case SSH -> "SSH";
            case FTP -> "FTP";
            case FTPS -> "FTPS";
            case HTTP -> "HTTP";
            case TLS -> "TLS";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
