package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.security.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single Netty TCP proxy server for one port mapping.
 * Bidirectionally forwards bytes between the external client and the internal target.
 *
 * When security is enabled, an IntelligentProxyHandler is added as the first handler
 * in the pipeline to inspect, rate-limit, and verdict every connection before relaying.
 */
@Slf4j
public class TcpProxyServer {

    private final PortMapping mapping;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private Channel serverChannel;

    // Security components (null if security disabled)
    private final ConnectionTracker connectionTracker;
    private final RateLimiter rateLimiter;
    private final AiVerdictClient aiVerdictClient;
    private final ThreatEventReporter eventReporter;
    private final SecurityMetrics securityMetrics;
    private final boolean securityEnabled;

    // Per-mapping security (null for global defaults)
    private final ManualSecurityFilter manualFilter;

    @Getter
    private final AtomicLong bytesForwarded = new AtomicLong(0);
    @Getter
    private final AtomicLong activeConnections = new AtomicLong(0);

    /**
     * Create a proxy server without security (backward compatible).
     */
    public TcpProxyServer(PortMapping mapping) {
        this(mapping, null, null, null, null, null, null);
    }

    /**
     * Create a proxy server with AI-powered security (global defaults).
     */
    public TcpProxyServer(PortMapping mapping,
                          ConnectionTracker connectionTracker,
                          RateLimiter rateLimiter,
                          AiVerdictClient aiVerdictClient,
                          ThreatEventReporter eventReporter,
                          SecurityMetrics securityMetrics) {
        this(mapping, connectionTracker, rateLimiter, aiVerdictClient, eventReporter, securityMetrics, null);
    }

    /**
     * Create a proxy server with per-mapping security policy.
     */
    public TcpProxyServer(PortMapping mapping,
                          ConnectionTracker connectionTracker,
                          RateLimiter rateLimiter,
                          AiVerdictClient aiVerdictClient,
                          ThreatEventReporter eventReporter,
                          SecurityMetrics securityMetrics,
                          ManualSecurityFilter manualFilter) {
        this.mapping = mapping;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.connectionTracker = connectionTracker;
        this.rateLimiter = rateLimiter;
        this.aiVerdictClient = aiVerdictClient;
        this.eventReporter = eventReporter;
        this.securityMetrics = securityMetrics;
        this.manualFilter = manualFilter;
        this.securityEnabled = connectionTracker != null && rateLimiter != null
            && aiVerdictClient != null && eventReporter != null && securityMetrics != null;
    }

    public void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel clientCh) {
                        activeConnections.incrementAndGet();

                        // ── Security handler (first in pipeline) ──
                        if (securityEnabled) {
                            clientCh.pipeline().addLast("security",
                                new IntelligentProxyHandler(
                                    connectionTracker, rateLimiter,
                                    aiVerdictClient, eventReporter, securityMetrics,
                                    mapping.getListenPort(), mapping.getName(),
                                    mapping.getSecurityPolicy(), manualFilter));
                        }

                        // ── Backend connection ──
                        Bootstrap backendBootstrap = new Bootstrap()
                                .group(clientCh.eventLoop())
                                .channel(NioSocketChannel.class)
                                .option(ChannelOption.AUTO_READ, false)
                                .handler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel backendCh) {
                                        backendCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded));
                                    }
                                });

                        ChannelFuture backendFuture = backendBootstrap.connect(
                            mapping.getTargetHost(), mapping.getTargetPort());
                        Channel backendCh = backendFuture.channel();

                        clientCh.pipeline().addLast("relay", new RelayHandler(backendCh, bytesForwarded));

                        backendFuture.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                clientCh.read();
                            } else {
                                log.warn("Backend connection failed: {}:{} -> {}",
                                    mapping.getName(), mapping.getTargetHost(), mapping.getTargetPort());
                                clientCh.close();
                            }
                        });

                        clientCh.closeFuture().addListener(cf -> {
                            activeConnections.decrementAndGet();
                            backendCh.close();
                        });
                    }
                });

        serverChannel = b.bind(mapping.getListenPort()).sync().channel();
        log.info("DMZ proxy [{}]: listening :{} -> {}:{} (security={})",
                mapping.getName(), mapping.getListenPort(),
                mapping.getTargetHost(), mapping.getTargetPort(),
                securityEnabled ? "ON" : "OFF");
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        log.info("DMZ proxy [{}] stopped", mapping.getName());
    }

    /** Simple relay: forward all bytes from inbound channel to the paired outbound channel */
    private static class RelayHandler extends ChannelInboundHandlerAdapter {
        private final Channel outboundChannel;
        private final AtomicLong bytesForwarded;

        RelayHandler(Channel outboundChannel, AtomicLong bytesForwarded) {
            this.outboundChannel = outboundChannel;
            this.bytesForwarded = bytesForwarded;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) bytesForwarded.addAndGet(buf.readableBytes());
            if (outboundChannel.isActive()) {
                outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) ctx.channel().read();
                    else f.channel().close();
                });
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (outboundChannel.isActive()) outboundChannel.writeAndFlush(ctx.alloc().buffer(0))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("Relay error: {}", cause.getMessage());
            ctx.close();
        }
    }
}
