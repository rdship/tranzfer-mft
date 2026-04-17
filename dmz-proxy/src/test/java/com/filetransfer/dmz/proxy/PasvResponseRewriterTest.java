package com.filetransfer.dmz.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R88: PASV reply rewriting on the backend→client direction of an FTP
 * control channel through DMZ (reverse proxy).
 *
 * <p>Proves the handler:
 * <ul>
 *   <li>Rewrites the advertised IP in a 227 reply to the external host.</li>
 *   <li>Leaves non-227 replies (230 welcome, 200 OK, etc.) unchanged.</li>
 *   <li>Reassembles replies that arrive split across multiple reads.</li>
 *   <li>Fails-open (no rewrite) when externalHost is not a parseable IPv4.</li>
 *   <li>Preserves the advertised port — DMZ listens on the same passive port
 *       number as the backend, so no port translation is needed.</li>
 * </ul>
 */
class PasvResponseRewriterTest {

    @Test
    void rewritesPasvReplyIpKeepingPort() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new PasvResponseRewriter("ftp-1", "203.0.113.7"));

        String pasv = "227 Entering Passive Mode (172,22,0,5,82,10).\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(pasv, StandardCharsets.US_ASCII));

        ByteBuf out = ch.readInbound();
        String forwarded = out.toString(StandardCharsets.US_ASCII);
        assertThat(forwarded).contains("(203,0,113,7,82,10)");
        assertThat(forwarded).doesNotContain("172,22,0,5");
        out.release();
    }

    @Test
    void passesThroughNon227Replies() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new PasvResponseRewriter("ftp-1", "203.0.113.7"));

        String welcome = "220 Welcome\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(welcome, StandardCharsets.US_ASCII));

        ByteBuf out = ch.readInbound();
        assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo(welcome);
        out.release();
    }

    @Test
    void reassemblesSplitReply() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new PasvResponseRewriter("ftp-1", "203.0.113.7"));

        String part1 = "227 Entering Passive Mode (172,";
        String part2 = "22,0,5,82,10).\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(part1, StandardCharsets.US_ASCII));
        // After the first partial, no full line yet — nothing forwarded.
        assertThat((Object) ch.readInbound()).isNull();

        ch.writeInbound(Unpooled.copiedBuffer(part2, StandardCharsets.US_ASCII));
        ByteBuf out = ch.readInbound();
        String forwarded = out.toString(StandardCharsets.US_ASCII);
        assertThat(forwarded).contains("(203,0,113,7,82,10)");
        out.release();
    }

    @Test
    void failsOpenWhenExternalHostNotIpv4Literal() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new PasvResponseRewriter("ftp-1", "dmz.example.com"));

        String pasv = "227 Entering Passive Mode (172,22,0,5,82,10).\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(pasv, StandardCharsets.US_ASCII));

        ByteBuf out = ch.readInbound();
        // No rewrite happens when we can't build a CSV form — we pass through.
        assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo(pasv);
        out.release();
    }

    @Test
    void doesNotRewriteEpsv229() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new PasvResponseRewriter("ftp-1", "203.0.113.7"));

        // EPSV replies carry only a port, no IP — DMZ listens on the same port
        // number so no rewrite is needed. Verify we pass it through untouched.
        String epsv = "229 Entering Extended Passive Mode (|||21003|)\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(epsv, StandardCharsets.US_ASCII));

        ByteBuf out = ch.readInbound();
        assertThat(out.toString(StandardCharsets.US_ASCII)).isEqualTo(epsv);
        out.release();
    }

    @Test
    void toIpCsvParsesValidIpv4AndRejectsOthers() {
        assertThat(PasvResponseRewriter.toIpCsv("203.0.113.7")).isEqualTo("203,0,113,7");
        assertThat(PasvResponseRewriter.toIpCsv("10.0.0.1")).isEqualTo("10,0,0,1");
        assertThat(PasvResponseRewriter.toIpCsv("not-an-ip")).isNull();
        assertThat(PasvResponseRewriter.toIpCsv("1.2.3")).isNull();
        assertThat(PasvResponseRewriter.toIpCsv("1.2.3.4.5")).isNull();
        assertThat(PasvResponseRewriter.toIpCsv("1.2.3.999")).isNull();
        assertThat(PasvResponseRewriter.toIpCsv(null)).isNull();
        assertThat(PasvResponseRewriter.toIpCsv("")).isNull();
    }
}
