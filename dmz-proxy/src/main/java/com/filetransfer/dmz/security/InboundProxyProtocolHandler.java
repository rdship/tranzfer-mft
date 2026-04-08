package com.filetransfer.dmz.security;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Inbound PROXY Protocol Handler — extracts the real client IP from an
 * upstream load balancer's PROXY protocol header (v1 text or v2 binary)
 * and stores it as a channel attribute for downstream security handlers.
 *
 * <p>Pipeline position: immediately after {@code HAProxyMessageDecoder},
 * before TLS and security handlers. The decoder auto-removes itself after
 * parsing the single PROXY header, and this handler removes itself after
 * extracting the address — zero overhead on subsequent packets.
 *
 * <p><strong>Channel activation deferral:</strong> When active, this handler
 * suppresses the {@code channelActive} event and re-fires it downstream
 * AFTER the PROXY header is parsed. This ensures that downstream handlers
 * (e.g. {@link IntelligentProxyHandler}) see the real client IP in their
 * {@code channelActive()} method via {@link #resolveClientIp(io.netty.channel.Channel)}.
 *
 * <p>Activate by setting {@code dmz.proxy-protocol.inbound-enabled=true}
 * (only when the proxy sits behind a load balancer that sends PROXY protocol).
 *
 * @see io.netty.handler.codec.haproxy.HAProxyMessageDecoder
 */
@Slf4j
public class InboundProxyProtocolHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel attribute holding the real client IP extracted from the
     * inbound PROXY protocol header. All security handlers should check
     * this attribute before falling back to {@code channel.remoteAddress()}.
     */
    public static final AttributeKey<String> REAL_CLIENT_IP =
            AttributeKey.valueOf("REAL_CLIENT_IP");

    /**
     * Channel attribute holding the real client port extracted from the
     * inbound PROXY protocol header.
     */
    public static final AttributeKey<Integer> REAL_CLIENT_PORT =
            AttributeKey.valueOf("REAL_CLIENT_PORT");

    /** Whether we have suppressed channelActive and need to re-fire it. */
    private boolean channelActivePending = false;
    private ChannelHandlerContext savedCtx;

    /**
     * Suppress channelActive — we re-fire it after the PROXY header is
     * parsed so that downstream handlers see the real client IP.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelActivePending = true;
        savedCtx = ctx;
        // Do NOT call super.channelActive(ctx) — suppress propagation.
        // We will fire it after processing the PROXY protocol header.
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HAProxyMessage haProxyMsg) {
            try {
                String sourceAddress = haProxyMsg.sourceAddress();
                int sourcePort = haProxyMsg.sourcePort();

                if (sourceAddress != null && !sourceAddress.isEmpty()) {
                    ctx.channel().attr(REAL_CLIENT_IP).set(sourceAddress);
                    ctx.channel().attr(REAL_CLIENT_PORT).set(sourcePort);

                    log.info("Inbound PROXY protocol: real client {}:{} (protocol={}, proxied={}:{})",
                            sourceAddress, sourcePort,
                            haProxyMsg.proxiedProtocol(),
                            haProxyMsg.destinationAddress(), haProxyMsg.destinationPort());
                } else {
                    log.warn("Inbound PROXY protocol header present but no source address — " +
                            "falling back to socket address");
                }
            } finally {
                // HAProxyMessage is reference-counted — always release
                haProxyMsg.release();
            }

            // Remove this handler — our one-shot job is done
            ctx.pipeline().remove(this);

            // Now fire the deferred channelActive so downstream handlers see the real IP
            if (channelActivePending) {
                channelActivePending = false;
                ctx.fireChannelActive();
            }
            return;
        }

        // Not a HAProxyMessage — pass through (should not happen if pipeline is correct)
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Inbound PROXY protocol parsing failed: {} — closing connection",
                cause.getMessage());
        ctx.close();
    }

    // ── Utility: resolve real client IP from channel ─────────────────────

    /**
     * Resolves the real client IP for a channel. Checks the
     * {@link #REAL_CLIENT_IP} attribute first (set by inbound PROXY protocol
     * parsing), then falls back to the socket's remote address.
     *
     * @param channel the Netty channel
     * @return the client IP string, or "unknown" if unavailable
     */
    public static String resolveClientIp(io.netty.channel.Channel channel) {
        String proxyIp = channel.attr(REAL_CLIENT_IP).get();
        if (proxyIp != null) {
            return proxyIp;
        }
        java.net.InetSocketAddress remote =
                (java.net.InetSocketAddress) channel.remoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
