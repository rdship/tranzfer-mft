package com.filetransfer.dmz.security;

import com.filetransfer.dmz.security.ProtocolDetector.DetectionResult;
import com.filetransfer.dmz.security.ProtocolDetector.Protocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolDetectorTest {

    @Test
    void detectsSshBanner() {
        ByteBuf buf = Unpooled.copiedBuffer("SSH-2.0-OpenSSH_8.9\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 22);
        assertEquals(Protocol.SSH, result.protocol());
        assertEquals(99, result.confidence());
        assertTrue(result.detail().contains("SSH-2.0-OpenSSH_8.9"));
        buf.release();
    }

    @Test
    void detectsFtpServerBanner() {
        ByteBuf buf = Unpooled.copiedBuffer("220 Welcome to FTP Server\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 21);
        assertEquals(Protocol.FTP, result.protocol());
        assertEquals(90, result.confidence());
        buf.release();
    }

    @Test
    void detectsFtpClientCommand() {
        ByteBuf buf = Unpooled.copiedBuffer("USER admin\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 21);
        assertEquals(Protocol.FTP, result.protocol());
        buf.release();
    }

    @Test
    void detectsExplicitFtps() {
        ByteBuf buf = Unpooled.copiedBuffer("AUTH TLS\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 21);
        assertEquals(Protocol.FTPS, result.protocol());
        assertEquals(85, result.confidence());
        buf.release();
    }

    @Test
    void detectsHttpGet() {
        ByteBuf buf = Unpooled.copiedBuffer("GET /index.html HTTP/1.1\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 80);
        assertEquals(Protocol.HTTP, result.protocol());
        assertEquals(95, result.confidence());
        assertTrue(result.detail().contains("GET"));
        buf.release();
    }

    @Test
    void detectsHttpPost() {
        ByteBuf buf = Unpooled.copiedBuffer("POST /api/data HTTP/1.1\r\n", StandardCharsets.US_ASCII);
        DetectionResult result = ProtocolDetector.detect(buf, 80);
        assertEquals(Protocol.HTTP, result.protocol());
        buf.release();
    }

    @Test
    void detectsTls12ClientHello() {
        // TLS 1.2 ClientHello: 0x16 0x03 0x03
        ByteBuf buf = Unpooled.buffer(5);
        buf.writeByte(0x16); // Handshake
        buf.writeByte(0x03); // TLS major
        buf.writeByte(0x03); // TLS 1.2 minor
        buf.writeByte(0x00); // length
        buf.writeByte(0x05);

        DetectionResult result = ProtocolDetector.detect(buf, 443);
        assertEquals(Protocol.TLS, result.protocol());
        assertEquals(95, result.confidence());
        assertEquals("TLSv1.2", result.detail());
        buf.release();
    }

    @Test
    void detectsTls13ClientHello() {
        ByteBuf buf = Unpooled.buffer(5);
        buf.writeByte(0x16);
        buf.writeByte(0x03);
        buf.writeByte(0x04); // TLS 1.3
        buf.writeByte(0x00);
        buf.writeByte(0x05);

        DetectionResult result = ProtocolDetector.detect(buf, 443);
        assertEquals(Protocol.TLS, result.protocol());
        assertEquals("TLSv1.3", result.detail());
        buf.release();
    }

    @Test
    void detectsImplicitFtps() {
        ByteBuf buf = Unpooled.buffer(5);
        buf.writeByte(0x16);
        buf.writeByte(0x03);
        buf.writeByte(0x03);
        buf.writeByte(0x00);
        buf.writeByte(0x05);

        DetectionResult result = ProtocolDetector.detect(buf, 990);
        assertEquals(Protocol.FTPS, result.protocol());
        assertEquals(95, result.confidence());
        buf.release();
    }

    @Test
    void fallsBackToPortBasedDetection() {
        // Random binary data
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeBytes(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09});

        DetectionResult result = ProtocolDetector.detect(buf, 22);
        assertEquals(Protocol.SSH, result.protocol());
        assertEquals(30, result.confidence());
        assertEquals("port_based", result.detail());
        buf.release();
    }

    @Test
    void unknownProtocolOnUnknownPort() {
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeBytes(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09});

        DetectionResult result = ProtocolDetector.detect(buf, 9999);
        assertEquals(Protocol.UNKNOWN, result.protocol());
        buf.release();
    }

    @Test
    void insufficientDataReturnsUnknown() {
        ByteBuf buf = Unpooled.buffer(2);
        buf.writeByte(0x00);
        buf.writeByte(0x01);

        DetectionResult result = ProtocolDetector.detect(buf, 22);
        assertEquals(Protocol.UNKNOWN, result.protocol());
        assertEquals(0, result.confidence());
        buf.release();
    }

    @Test
    void protocolToStringMapsCorrectly() {
        assertEquals("SSH", ProtocolDetector.protocolToString(Protocol.SSH));
        assertEquals("FTP", ProtocolDetector.protocolToString(Protocol.FTP));
        assertEquals("FTPS", ProtocolDetector.protocolToString(Protocol.FTPS));
        assertEquals("HTTP", ProtocolDetector.protocolToString(Protocol.HTTP));
        assertEquals("TLS", ProtocolDetector.protocolToString(Protocol.TLS));
        assertEquals("UNKNOWN", ProtocolDetector.protocolToString(Protocol.UNKNOWN));
    }
}
