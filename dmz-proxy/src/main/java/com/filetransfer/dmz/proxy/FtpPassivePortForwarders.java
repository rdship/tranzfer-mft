package com.filetransfer.dmz.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns one lightweight Netty TCP forwarder per port in an FTP mapping's
 * passive-port range. Each forwarder listens on {@code port} on the DMZ side
 * and blind-forwards bytes to the same {@code port} on the backend.
 *
 * <p>FTP data channels carry either plain binary or TLS-encrypted bytes (when
 * PROT P is active). In either case DMZ has no business decoding them — it is
 * a byte pipe. No DPI, no inspection, no QoS here; the control channel (where
 * commands flow) already enforces all of that via {@link TcpProxyServer}.
 *
 * <p>One event-loop group per range (sized to half the passive range, capped
 * at 8) keeps memory bounded when many FTP mappings coexist.
 */
@Slf4j
public class FtpPassivePortForwarders {

    private final String mappingName;
    private final String targetHost;
    private final int from;
    private final int to;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final List<Channel> serverChannels = new ArrayList<>();

    public FtpPassivePortForwarders(String mappingName, String targetHost, int from, int to) {
        this.mappingName = mappingName;
        this.targetHost = targetHost;
        this.from = from;
        this.to = to;
        int workers = Math.min(8, Math.max(1, (to - from + 1) / 2));
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(workers);
    }

    public void start() {
        int bound = 0;
        int failed = 0;
        for (int port = from; port <= to; port++) {
            try {
                Channel ch = bindOne(port);
                serverChannels.add(ch);
                bound++;
            } catch (Exception e) {
                // One port refusal shouldn't kill the whole range — log and continue.
                log.warn("[{}] Could not bind passive port {}: {}", mappingName, port, e.getMessage());
                failed++;
            }
        }
        log.info("[{}] Passive range {}-{} forwarders: {} bound, {} failed",
                mappingName, from, to, bound, failed);
    }

    public void stop() {
        for (Channel ch : serverChannels) {
            try { ch.close().sync(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        serverChannels.clear();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private Channel bindOne(int port) throws InterruptedException {
        final int targetPort = port; // same port on backend
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel clientCh) {
                        Bootstrap backend = new Bootstrap()
                                .group(clientCh.eventLoop())
                                .channel(NioSocketChannel.class)
                                .option(ChannelOption.AUTO_READ, false)
                                .handler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel backendCh) {
                                        backendCh.pipeline().addLast(new ByteRelay(clientCh));
                                    }
                                });
                        ChannelFuture fut = backend.connect(targetHost, targetPort);
                        Channel backendCh = fut.channel();
                        clientCh.pipeline().addLast(new ByteRelay(backendCh));
                        fut.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                clientCh.read();
                                backendCh.read();
                            } else {
                                log.debug("[{}] passive forward :{} -> backend connect failed: {}",
                                        mappingName, targetPort, f.cause() != null ? f.cause().getMessage() : "?");
                                clientCh.close();
                            }
                        });
                        clientCh.closeFuture().addListener(c -> backendCh.close());
                    }
                });
        return b.bind(port).sync().channel();
    }

    /** Minimal relay — forwards bytes one direction, closes the peer on EOF. */
    private static final class ByteRelay extends ChannelInboundHandlerAdapter {
        private final Channel peer;
        ByteRelay(Channel peer) { this.peer = peer; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf) || !peer.isActive()) {
                ReferenceCountUtil.release(msg);
                return;
            }
            peer.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) ctx.channel().read();
                else f.channel().close();
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peer.isActive()) peer.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
