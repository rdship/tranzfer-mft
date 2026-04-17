package com.filetransfer.dmz.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites FTP {@code 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)} replies
 * from the backend so the advertised IP matches the reverse-proxy's external
 * address. Sits in the <b>backend → client</b> direction of the control channel.
 *
 * <p>Without this handler, a client connected to DMZ reads the internal
 * container IP from the PASV reply and attempts a data connection that either
 * fails (external network can't route to the internal IP) or leaks internal
 * topology. With it, clients see only the DMZ external address, and because
 * DMZ listens on the same passive port number on the outside (no port
 * translation), the advertised port is passed through unchanged.
 *
 * <p>Deliberately does NOT rewrite {@code 229 Entering Extended Passive Mode}
 * — EPSV only advertises a port number (no IP), so there is nothing to
 * rewrite as long as DMZ is listening on the same port.
 *
 * <p>Control channel is line-based ASCII. The rewriter buffers partial reads
 * until it sees a full line ending in CRLF, so a reply split across packets
 * still rewrites correctly.
 */
@Slf4j
public class PasvResponseRewriter extends ChannelInboundHandlerAdapter {

    // 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2).
    // RFC 959 allows arbitrary text before/after the parens. Match anchored
    // on the literal "227 " prefix and the first "(h1,h2,h3,h4,p1,p2)" group.
    private static final Pattern PASV_PATTERN = Pattern.compile(
            "(227\\b[^(]*)\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)(.*)",
            Pattern.DOTALL);

    private final String mappingName;
    /** Externally-advertised host — either {@code h.h.h.h} or a DNS name that resolves to that. */
    private final String externalHost;
    /** Pre-computed "h1,h2,h3,h4" form. Null when externalHost is a DNS name (unresolved). */
    private final String externalIpCsv;

    private ByteBuf accumulator;

    public PasvResponseRewriter(String mappingName, String externalHost) {
        this.mappingName = mappingName;
        this.externalHost = externalHost;
        this.externalIpCsv = toIpCsv(externalHost);
    }

    /**
     * Convert a dotted-quad IPv4 string into the "h1,h2,h3,h4" form required
     * by the RFC 959 PASV reply. Returns null for anything that isn't a
     * parseable IPv4 literal — the caller must decide whether to resolve a
     * hostname ahead of time.
     */
    static String toIpCsv(String host) {
        if (host == null || host.isBlank()) return null;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return null;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return String.join(",", parts);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (externalIpCsv == null || !(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Accumulate bytes until we see a line terminator. FTP replies are
        // short — a few hundred bytes at most — so a simple growable accumulator
        // is fine. Forward each complete line immediately to keep latency low.
        if (accumulator == null) {
            accumulator = ctx.alloc().buffer(buf.readableBytes());
        }
        accumulator.writeBytes(buf);
        buf.release();

        while (true) {
            int eol = indexOfLf(accumulator);
            if (eol < 0) break;

            int lineLen = eol - accumulator.readerIndex() + 1; // include LF
            ByteBuf line = accumulator.readBytes(lineLen);
            ByteBuf rewritten = rewriteIfPasv(line, ctx);
            ctx.fireChannelRead(rewritten);
        }

        // Compact the accumulator to avoid unbounded growth on very long replies
        if (accumulator.readerIndex() > 0) {
            accumulator.discardReadBytes();
        }
    }

    private ByteBuf rewriteIfPasv(ByteBuf line, ChannelHandlerContext ctx) {
        String text = line.toString(StandardCharsets.US_ASCII);
        if (!text.startsWith("227")) {
            return line;
        }
        Matcher m = PASV_PATTERN.matcher(text);
        if (!m.matches()) {
            return line;
        }
        String prefix = m.group(1);
        String p1 = m.group(6);
        String p2 = m.group(7);
        String suffix = m.group(8);
        String rewritten = prefix + "(" + externalIpCsv + "," + p1 + "," + p2 + ")" + suffix;
        log.info("[{}] PASV rewrite: '{}' -> '{}'",
                mappingName,
                text.trim(),
                rewritten.trim());
        line.release();
        return Unpooled.copiedBuffer(rewritten, StandardCharsets.US_ASCII);
    }

    private static int indexOfLf(ByteBuf buf) {
        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
            if (buf.getByte(i) == '\n') return i;
        }
        return -1;
    }
}
