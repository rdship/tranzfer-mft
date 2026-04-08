package com.filetransfer.dmz.tunnel;

import com.filetransfer.tunnel.keepalive.TunnelKeepAliveHandler;
import com.filetransfer.tunnel.protocol.TunnelFrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Accepts the single multiplexed tunnel connection from gateway-service on port 9443.
 * <p>
 * Only one tunnel client is expected at a time. The active {@link DmzTunnelHandler}
 * is stored for use by {@link TunnelAwareBackendConnector} and the broader DMZ proxy.
 * <p>
 * Pipeline: [TLS] -> TunnelFrameCodec -> IdleStateHandler -> TunnelKeepAliveHandler -> DmzTunnelHandler
 * <p>
 * No Spring DI — constructed manually by ProxyManager.
 */
@Slf4j
public class TunnelAcceptor {

    private static final int PONG_TIMEOUT_SECONDS = 20;
    private static final int PING_INTERVAL_SECONDS = 15;

    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final SslContext sslContext; // nullable — TLS optional
    private final int maxStreams;
    private final int windowSize;

    private volatile Channel serverChannel;
    @Getter
    private volatile DmzTunnelHandler handler;
    @Getter
    private volatile TunnelAwareBackendConnector connector;

    /**
     * @param port        tunnel listen port (typically 9443)
     * @param bossGroup   Netty boss group (accepts connections)
     * @param workerGroup Netty worker group (I/O)
     * @param sslContext  nullable — set when dmz.tunnel.tls.enabled=true
     * @param maxStreams   max concurrent multiplexed streams
     * @param windowSize  per-stream flow control window in bytes (0 = default 256KB)
     */
    public TunnelAcceptor(int port, EventLoopGroup bossGroup, EventLoopGroup workerGroup,
                          SslContext sslContext, int maxStreams, int windowSize) {
        this.port = port;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.sslContext = sslContext;
        this.maxStreams = maxStreams;
        this.windowSize = windowSize;
    }

    /**
     * Binds the tunnel acceptor on the configured port.
     *
     * @throws InterruptedException if binding is interrupted
     */
    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // ── Optional TLS ──
                        if (sslContext != null) {
                            ch.pipeline().addLast("tls", sslContext.newHandler(ch.alloc()));
                        }

                        // ── Frame codec: [4B StreamID][4B Length][1B Type][1B Flags][Payload] ──
                        ch.pipeline().addLast("codec", new TunnelFrameCodec());

                        // ── Keepalive: reader idle = pong timeout, writer idle = ping interval ──
                        ch.pipeline().addLast("idle", new IdleStateHandler(
                                PONG_TIMEOUT_SECONDS, PING_INTERVAL_SECONDS, 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast("keepalive", new TunnelKeepAliveHandler());

                        // ── Frame dispatch handler ──
                        DmzTunnelHandler tunnelHandler = new DmzTunnelHandler(maxStreams, windowSize);
                        ch.pipeline().addLast("tunnel", tunnelHandler);

                        // Replace the previous handler reference (only one tunnel client expected)
                        DmzTunnelHandler prev = handler;
                        handler = tunnelHandler;
                        connector = new TunnelAwareBackendConnector(tunnelHandler);

                        if (prev != null) {
                            log.warn("New tunnel connection replaced existing handler — "
                                    + "old tunnel from {} superseded by {}",
                                    prev.getRemoteAddress(), ch.remoteAddress());
                        }

                        log.info("Tunnel client connected from {} (tls={})",
                                ch.remoteAddress(), sslContext != null);
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Tunnel acceptor listening on port {} (tls={}, maxStreams={}, windowSize={})",
                port, sslContext != null, maxStreams, windowSize);
    }

    /**
     * Shuts down the tunnel acceptor and closes any active tunnel connection.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            log.info("Tunnel acceptor on port {} stopped", port);
        }
        DmzTunnelHandler h = handler;
        if (h != null) {
            h.close();
        }
        handler = null;
        connector = null;
    }

    /**
     * Returns true if the acceptor is bound and listening.
     */
    public boolean isActive() {
        return serverChannel != null && serverChannel.isActive();
    }

    /**
     * Returns true if the tunnel is actively connected (handler present and channel active).
     * Use this instead of isActive() when you need to know if traffic can flow.
     */
    public boolean isConnected() {
        DmzTunnelHandler h = handler;
        return h != null && h.isConnected();
    }
}
