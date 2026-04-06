package com.filetransfer.dmz.security;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProxyProtocolHandlerTest {

    private InetSocketAddress clientAddr;
    private InetSocketAddress localAddr;

    @BeforeEach
    void setUp() {
        clientAddr = new InetSocketAddress("192.168.1.100", 12345);
        localAddr = new InetSocketAddress("10.0.0.1", 2222);
    }

    // ── V1 factory ──────────────────────────────────────────────────────

    @Test
    void v1Factory_createsHandler() {
        ProxyProtocolHandler handler = ProxyProtocolHandler.v1(clientAddr, localAddr);
        assertNotNull(handler);
    }

    @Test
    void v2Factory_createsHandler() {
        ProxyProtocolHandler handler = ProxyProtocolHandler.v2(clientAddr, localAddr);
        assertNotNull(handler);
    }

    // ── V1 pipeline tests ───────────────────────────────────────────────

    @Test
    void v1_ipv4_correctFormat() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v1(clientAddr, localAddr));

        ByteBuf payload = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        channel.writeOutbound(payload);
        channel.flushOutbound();

        ByteBuf first = channel.readOutbound();
        assertNotNull(first);
        String headerStr = first.toString(StandardCharsets.US_ASCII);
        assertTrue(headerStr.startsWith("PROXY TCP4 "), "Header should start with 'PROXY TCP4 '");
        assertTrue(headerStr.endsWith("\r\n"), "Header should end with CRLF");
        first.release();

        // Clean up remaining outbound
        ByteBuf second = channel.readOutbound();
        if (second != null) second.release();
        channel.close();
    }

    @Test
    void v1_containsCorrectIpsAndPorts() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v1(clientAddr, localAddr));

        channel.writeOutbound(Unpooled.copiedBuffer("data", StandardCharsets.UTF_8));
        channel.flushOutbound();

        ByteBuf first = channel.readOutbound();
        assertNotNull(first);
        String headerStr = first.toString(StandardCharsets.US_ASCII);

        assertTrue(headerStr.contains("192.168.1.100"), "Header should contain client IP");
        assertTrue(headerStr.contains("10.0.0.1"), "Header should contain local IP");
        assertTrue(headerStr.contains("12345"), "Header should contain client port");
        assertTrue(headerStr.contains("2222"), "Header should contain local port");

        // Verify exact format: PROXY TCP4 <srcIP> <dstIP> <srcPort> <dstPort>\r\n
        assertEquals("PROXY TCP4 192.168.1.100 10.0.0.1 12345 2222\r\n", headerStr);

        first.release();
        ByteBuf second = channel.readOutbound();
        if (second != null) second.release();
        channel.close();
    }

    @Test
    void v1_writeSendsHeaderThenPayload() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v1(clientAddr, localAddr));

        ByteBuf payload = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        channel.writeOutbound(payload);
        channel.flushOutbound();

        // First outbound message: the PROXY header
        ByteBuf first = channel.readOutbound();
        assertNotNull(first, "First outbound should be the PROXY header");
        String headerStr = first.toString(StandardCharsets.US_ASCII);
        assertTrue(headerStr.startsWith("PROXY TCP4 "));
        first.release();

        // Second outbound message: the original payload
        ByteBuf second = channel.readOutbound();
        assertNotNull(second, "Second outbound should be the original payload");
        assertEquals("hello", second.toString(StandardCharsets.UTF_8));
        second.release();

        channel.close();
    }

    // ── V2 pipeline tests ───────────────────────────────────────────────

    @Test
    void v2_ipv4_correctSignature() {
        byte[] expectedSignature = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51,
                0x55, 0x49, 0x54, 0x0A
        };

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v2(clientAddr, localAddr));

        channel.writeOutbound(Unpooled.copiedBuffer("data", StandardCharsets.UTF_8));
        channel.flushOutbound();

        ByteBuf first = channel.readOutbound();
        assertNotNull(first, "V2 header should be written as first outbound message");
        assertTrue(first.readableBytes() >= 12, "V2 header must be at least 12 bytes");

        byte[] signatureBytes = new byte[12];
        first.getBytes(first.readerIndex(), signatureBytes);
        assertArrayEquals(expectedSignature, signatureBytes, "First 12 bytes must match V2 signature");

        first.release();
        ByteBuf second = channel.readOutbound();
        if (second != null) second.release();
        channel.close();
    }

    @Test
    void v2_ipv4_correctLength() {
        // V2 IPv4 header: 16 (sig+ver+cmd+family+len) + 12 (4+4+2+2 addresses) = 28 bytes
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v2(clientAddr, localAddr));

        channel.writeOutbound(Unpooled.copiedBuffer("data", StandardCharsets.UTF_8));
        channel.flushOutbound();

        ByteBuf first = channel.readOutbound();
        assertNotNull(first);
        assertEquals(28, first.readableBytes(), "V2 IPv4 header should be 28 bytes (16 fixed + 12 addr)");

        first.release();
        ByteBuf second = channel.readOutbound();
        if (second != null) second.release();
        channel.close();
    }

    // ── Self-removal tests ──────────────────────────────────────────────

    @Test
    void handler_removesItselfAfterFirstWrite() {
        ProxyProtocolHandler handler = ProxyProtocolHandler.v1(clientAddr, localAddr);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("proxy-protocol", handler);

        // Handler should be present before any write
        assertNotNull(channel.pipeline().get("proxy-protocol"), "Handler should be in pipeline before write");

        channel.writeOutbound(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8));
        channel.flushOutbound();

        // Handler should be removed after first write
        assertNull(channel.pipeline().get("proxy-protocol"), "Handler should be removed after first write");

        // Drain outbound
        ByteBuf msg;
        while ((msg = channel.readOutbound()) != null) msg.release();
        channel.close();
    }

    @Test
    void secondWrite_noAdditionalHeader() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(ProxyProtocolHandler.v1(clientAddr, localAddr));

        // First write — triggers the header
        channel.writeOutbound(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8));
        channel.flushOutbound();

        ByteBuf header = channel.readOutbound();
        assertNotNull(header);
        String headerStr = header.toString(StandardCharsets.US_ASCII);
        assertTrue(headerStr.startsWith("PROXY TCP4 "), "First message should be the PROXY header");
        header.release();

        ByteBuf firstPayload = channel.readOutbound();
        assertNotNull(firstPayload);
        assertEquals("first", firstPayload.toString(StandardCharsets.UTF_8));
        firstPayload.release();

        // Second write — should NOT produce any header
        channel.writeOutbound(Unpooled.copiedBuffer("second", StandardCharsets.UTF_8));
        channel.flushOutbound();

        ByteBuf secondPayload = channel.readOutbound();
        assertNotNull(secondPayload, "Second write payload should pass through");
        assertEquals("second", secondPayload.toString(StandardCharsets.UTF_8),
                "Second write should be raw payload without any header");
        secondPayload.release();

        // There should be no additional messages
        assertNull(channel.readOutbound(), "No additional outbound messages expected after second write");

        channel.close();
    }
}
