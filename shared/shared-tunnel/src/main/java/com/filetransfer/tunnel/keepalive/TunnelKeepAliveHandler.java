package com.filetransfer.tunnel.keepalive;

import com.filetransfer.tunnel.protocol.TunnelFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends PING frames on idle and closes the tunnel if PONG is not received in time.
 * <p>
 * Usage: add {@link io.netty.handler.timeout.IdleStateHandler} before this handler:
 * <pre>
 * pipeline.addLast(new IdleStateHandler(pongTimeoutSeconds, pingIntervalSeconds, 0));
 * pipeline.addLast(new TunnelKeepAliveHandler());
 * </pre>
 * Writer idle → send PING. Reader idle → no PONG received → close tunnel.
 */
@Slf4j
public class TunnelKeepAliveHandler extends ChannelInboundHandlerAdapter {

    private final AtomicLong lastPingSentNanos = new AtomicLong(0);
    private volatile long lastRttNanos;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.WRITER_IDLE) {
                // No writes for pingInterval — send PING
                long now = System.nanoTime();
                lastPingSentNanos.set(now);
                ctx.writeAndFlush(TunnelFrame.ping(now));
                log.trace("PING sent on tunnel {}", ctx.channel().remoteAddress());
            } else if (idle.state() == IdleState.READER_IDLE) {
                // No reads for pongTimeout — tunnel is dead
                log.warn("Tunnel keepalive timeout, no PONG from {} — closing",
                        ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof TunnelFrame frame) {
            switch (frame.getType()) {
                case PING -> {
                    // Respond with PONG carrying the same timestamp
                    long ts = frame.getPayload().readLong();
                    ctx.writeAndFlush(TunnelFrame.pong(ts));
                    return; // consumed
                }
                case PONG -> {
                    long ts = frame.getPayload().readLong();
                    lastRttNanos = System.nanoTime() - ts;
                    log.trace("PONG received, RTT={}ms", lastRttNanos / 1_000_000);
                    return; // consumed
                }
                default -> {
                    // Pass other frames down the pipeline
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * Last measured round-trip time in milliseconds (0 if no PONG received yet).
     */
    public long lastRttMs() {
        return lastRttNanos / 1_000_000;
    }
}
