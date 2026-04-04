package com.filetransfer.dmz.proxy;

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
 */
@Slf4j
public class TcpProxyServer {

    private final PortMapping mapping;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private Channel serverChannel;

    @Getter
    private final AtomicLong bytesForwarded = new AtomicLong(0);
    @Getter
    private final AtomicLong activeConnections = new AtomicLong(0);

    public TcpProxyServer(PortMapping mapping) {
        this.mapping = mapping;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
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
                        // Connect to the backend
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

                        ChannelFuture backendFuture = backendBootstrap.connect(mapping.getTargetHost(), mapping.getTargetPort());
                        Channel backendCh = backendFuture.channel();

                        clientCh.pipeline().addLast(new RelayHandler(backendCh, bytesForwarded));

                        backendFuture.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                clientCh.read();
                            } else {
                                log.warn("Backend connection failed: {}:{} → {}", mapping.getName(),
                                        mapping.getTargetHost(), mapping.getTargetPort());
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
        log.info("DMZ proxy [{}]: listening :{}  →  {}:{}",
                mapping.getName(), mapping.getListenPort(),
                mapping.getTargetHost(), mapping.getTargetPort());
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
