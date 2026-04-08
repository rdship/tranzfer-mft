package com.filetransfer.gateway.tunnel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Exponential-backoff reconnection handler for the DMZ tunnel.
 * <p>
 * On channelInactive: schedules a reconnect attempt with exponential backoff
 * (1s -> 2s -> 4s -> 8s -> 16s -> 30s cap) with +/-25% jitter.
 * On successful connect: backoff resets to base. Retries are unlimited --
 * the tunnel must always reconnect to maintain cross-zone connectivity.
 */
@Slf4j
public class TunnelReconnectHandler extends ChannelInboundHandlerAdapter {

    private final TunnelClient tunnelClient;
    private final int baseMs;
    private final int maxMs;
    private final double jitter;

    private int currentBackoffMs;

    public TunnelReconnectHandler(TunnelClient tunnelClient, int baseMs, int maxMs, double jitter) {
        this.tunnelClient = tunnelClient;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.jitter = jitter;
        this.currentBackoffMs = baseMs;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        int delay = applyJitter(currentBackoffMs);
        log.warn("Tunnel disconnected. Reconnecting in {}ms (backoff={}ms)", delay, currentBackoffMs);

        ctx.executor().schedule(() -> {
            log.info("Attempting tunnel reconnection...");
            tunnelClient.connect();
        }, delay, TimeUnit.MILLISECONDS);

        // Advance backoff for next failure (capped at maxMs)
        currentBackoffMs = Math.min(currentBackoffMs * 2, maxMs);
        super.channelInactive(ctx);
    }

    /**
     * Resets backoff to base delay. Called by TunnelClient on successful connection.
     */
    public void resetBackoff() {
        this.currentBackoffMs = baseMs;
        log.debug("Reconnect backoff reset to {}ms", baseMs);
    }

    private int applyJitter(int delayMs) {
        double range = delayMs * jitter;
        double offset = ThreadLocalRandom.current().nextDouble(-range, range);
        return Math.max(1, (int) (delayMs + offset));
    }
}
