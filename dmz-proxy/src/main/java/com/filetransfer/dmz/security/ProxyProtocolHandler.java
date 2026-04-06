package com.filetransfer.dmz.security;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Proxy Protocol Handler — Netty ChannelOutboundHandler that prepends a
 * PROXY protocol header (v1 text or v2 binary) on the first write to the
 * backend channel, so upstream services can see the real client IP.
 *
 * <p>One-shot: removes itself from the pipeline after the header is sent.
 *
 * <p>Supports:
 * <ul>
 *   <li>PROXY protocol v1 (human-readable, HAProxy spec §2.1)</li>
 *   <li>PROXY protocol v2 (binary, HAProxy spec §2.2)</li>
 * </ul>
 *
 * <p>Usage in pipeline setup:
 * <pre>
 *   backendChannel.pipeline().addFirst("proxy-protocol",
 *       ProxyProtocolHandler.v1(clientAddr, localAddr));
 * </pre>
 *
 * @see <a href="https://www.haproxy.org/download/2.9/doc/proxy-protocol.txt">HAProxy PROXY protocol spec</a>
 */
@Slf4j
public class ProxyProtocolHandler extends ChannelOutboundHandlerAdapter {

    // ── PROXY protocol v2 constants ──────────────────────────────────────

    /** 12-byte v2 signature: \r\n\r\n\0\r\nQUIT\n */
    private static final byte[] V2_SIGNATURE = {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51,
            0x55, 0x49, 0x54, 0x0A
    };

    /** Version 2, PROXY command */
    private static final byte V2_VERSION_COMMAND = 0x21;

    /** AF_INET + STREAM */
    private static final byte V2_FAMILY_TCP4 = 0x11;

    /** AF_INET6 + STREAM */
    private static final byte V2_FAMILY_TCP6 = 0x21;

    /** IPv4 address block length: 4+4+2+2 = 12 bytes */
    private static final int V2_ADDR_LEN_IPV4 = 12;

    /** IPv6 address block length: 16+16+2+2 = 36 bytes */
    private static final int V2_ADDR_LEN_IPV6 = 36;

    // ── Instance fields ──────────────────────────────────────────────────

    private final InetSocketAddress clientAddress;
    private final InetSocketAddress localAddress;
    private final boolean useV2;
    private volatile boolean headerSent = false;

    // ── Constructors & factories ─────────────────────────────────────────

    /**
     * Creates a ProxyProtocolHandler.
     *
     * @param clientAddress the remote client's socket address (real source IP)
     * @param localAddress  the proxy's local socket address
     * @param useV2         {@code true} for binary v2 format, {@code false} for text v1
     */
    public ProxyProtocolHandler(InetSocketAddress clientAddress,
                                InetSocketAddress localAddress,
                                boolean useV2) {
        this.clientAddress = clientAddress;
        this.localAddress = localAddress;
        this.useV2 = useV2;
    }

    /**
     * Factory for PROXY protocol v1 (text format).
     */
    public static ProxyProtocolHandler v1(InetSocketAddress clientAddress,
                                          InetSocketAddress localAddress) {
        return new ProxyProtocolHandler(clientAddress, localAddress, false);
    }

    /**
     * Factory for PROXY protocol v2 (binary format).
     */
    public static ProxyProtocolHandler v2(InetSocketAddress clientAddress,
                                          InetSocketAddress localAddress) {
        return new ProxyProtocolHandler(clientAddress, localAddress, true);
    }

    // ── Outbound write intercept ─────────────────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {

        if (!headerSent) {
            headerSent = true;

            ByteBuf header = useV2 ? buildV2Header(ctx) : buildV1Header(ctx);

            log.debug("Sending PROXY protocol {} header to backend {} — client={}:{}",
                    useV2 ? "v2" : "v1",
                    ctx.channel().remoteAddress(),
                    clientAddress.getAddress().getHostAddress(),
                    clientAddress.getPort());

            // Write the header first, then the original payload.
            // Use voidPromise for the header to avoid extra future allocation;
            // the real write promise flows with the payload.
            ctx.write(header, ctx.voidPromise());

            // Remove ourselves from the pipeline — our job is done.
            ctx.pipeline().remove(this);
        }

        // Forward the original write (or delegate to next handler after removal).
        ctx.write(msg, promise);
    }

    // ── V1 builder (text) ────────────────────────────────────────────────

    /**
     * Builds a PROXY protocol v1 text header.
     * <p>Format: {@code PROXY TCP4|TCP6 <srcIP> <dstIP> <srcPort> <dstPort>\r\n}
     */
    private ByteBuf buildV1Header(ChannelHandlerContext ctx) {
        InetAddress clientIp = clientAddress.getAddress();
        String family = (clientIp instanceof Inet6Address) ? "TCP6" : "TCP4";

        String line = String.format("PROXY %s %s %s %d %d\r\n",
                family,
                clientIp.getHostAddress(),
                localAddress.getAddress().getHostAddress(),
                clientAddress.getPort(),
                localAddress.getPort());

        log.trace("PROXY v1 header: {}", line.trim());
        return Unpooled.copiedBuffer(line, StandardCharsets.US_ASCII);
    }

    // ── V2 builder (binary) ──────────────────────────────────────────────

    /**
     * Builds a PROXY protocol v2 binary header.
     *
     * <pre>
     *   12 bytes  — signature
     *    1 byte   — version (0x2) | command (0x1 = PROXY)
     *    1 byte   — address family | transport protocol
     *    2 bytes  — address length (big-endian)
     *    N bytes  — addresses + ports
     * </pre>
     */
    private ByteBuf buildV2Header(ChannelHandlerContext ctx) {
        InetAddress clientIp = clientAddress.getAddress();
        InetAddress localIp = localAddress.getAddress();
        boolean ipv6 = (clientIp instanceof Inet6Address) || (localIp instanceof Inet6Address);

        byte familyByte;
        int addrLen;
        byte[] srcAddr;
        byte[] dstAddr;

        if (ipv6) {
            familyByte = V2_FAMILY_TCP6;
            addrLen = V2_ADDR_LEN_IPV6;
            srcAddr = toIpv6Bytes(clientIp);
            dstAddr = toIpv6Bytes(localIp);
        } else {
            familyByte = V2_FAMILY_TCP4;
            addrLen = V2_ADDR_LEN_IPV4;
            srcAddr = clientIp.getAddress();
            dstAddr = localIp.getAddress();
        }

        // 12 (sig) + 1 (ver/cmd) + 1 (family) + 2 (len) + addrLen
        int totalLen = 16 + addrLen;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put(V2_SIGNATURE);                          // 12 bytes
        buf.put(V2_VERSION_COMMAND);                     // 1 byte
        buf.put(familyByte);                             // 1 byte
        buf.putShort((short) addrLen);                   // 2 bytes

        buf.put(srcAddr);                                // 4 or 16 bytes
        buf.put(dstAddr);                                // 4 or 16 bytes
        buf.putShort((short) clientAddress.getPort());   // 2 bytes
        buf.putShort((short) localAddress.getPort());    // 2 bytes

        buf.flip();
        log.trace("PROXY v2 header: {} bytes total, family={}", totalLen,
                ipv6 ? "TCP6" : "TCP4");
        return Unpooled.wrappedBuffer(buf);
    }

    /**
     * Converts an address to a 16-byte IPv6 representation.
     * If the address is IPv4, it is mapped to IPv6 (::ffff:a.b.c.d).
     */
    private static byte[] toIpv6Bytes(InetAddress addr) {
        if (addr instanceof Inet6Address) {
            return addr.getAddress();
        }
        // IPv4-mapped IPv6: 10 zero bytes, 2x 0xFF, then the 4 IPv4 bytes
        byte[] v4 = addr.getAddress();
        byte[] v6 = new byte[16];
        v6[10] = (byte) 0xFF;
        v6[11] = (byte) 0xFF;
        System.arraycopy(v4, 0, v6, 12, 4);
        return v6;
    }
}
