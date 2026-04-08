package com.filetransfer.gateway.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges a TunnelStreamChannel (virtual multiplexed stream) with a local NioSocketChannel
 * (connection to an internal service such as sftp-service:2220 or ftp-service:2122).
 * <p>
 * Bidirectional relay with backpressure: if the write side is slow, autoRead is disabled
 * on the read side and re-enabled when the write completes. ByteBuf instances are forwarded
 * directly -- zero-copy, no byte[] conversion.
 */
@Slf4j
public class TunnelStreamRelay {

    private TunnelStreamRelay() {}

    /**
     * Installs bidirectional relay handlers on both channels.
     *
     * @param tunnelStream the virtual TunnelStreamChannel
     * @param localChannel the NioSocketChannel connected to the internal service
     */
    public static void bridge(Channel tunnelStream, Channel localChannel) {
        tunnelStream.pipeline().addLast("relay-tunnel-to-local", new RelayHandler(localChannel));
        localChannel.pipeline().addLast("relay-local-to-tunnel", new RelayHandler(tunnelStream));
        log.debug("Relay bridge established: tunnel-stream <-> {}", localChannel.remoteAddress());
    }

    /**
     * Reads from one channel and writes to the other, with write-backpressure handling.
     */
    static class RelayHandler extends ChannelInboundHandlerAdapter {

        private final Channel peer;

        RelayHandler(Channel peer) {
            this.peer = peer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!peer.isActive()) {
                if (msg instanceof ByteBuf buf) {
                    buf.release();
                }
                ctx.close();
                return;
            }

            // Write the ByteBuf directly to the peer -- zero-copy
            peer.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    log.debug("Relay write to {} failed: {}", peer.remoteAddress(),
                            future.cause().getMessage());
                    future.channel().close();
                    ctx.close();
                    return;
                }
                // Backpressure: if peer's write buffer is not writable, pause reading
                if (!peer.isWritable()) {
                    ctx.channel().config().setAutoRead(false);
                }
            });
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            // Peer became writable again -- resume reading on the source side
            // (this is fired on the peer's pipeline, so 'peer' here is the source)
            if (ctx.channel().isWritable()) {
                peer.config().setAutoRead(true);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peer.isActive()) {
                peer.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("Relay error on {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
            ctx.close();
            if (peer.isActive()) {
                peer.close();
            }
        }
    }
}
