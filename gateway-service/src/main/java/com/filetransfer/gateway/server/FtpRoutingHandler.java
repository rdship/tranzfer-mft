package com.filetransfer.gateway.server;

import com.filetransfer.gateway.routing.UserRoutingService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Netty handler that:
 * 1. Sends the FTP "220 Ready" greeting to the client
 * 2. Waits for the "USER <username>" command
 * 3. Decides the backend (internal or legacy) based on the username
 * 4. Establishes a TCP connection to the backend
 * 5. Replays the USER command and bridges all subsequent bytes bidirectionally
 */
@Slf4j
@RequiredArgsConstructor
public class FtpRoutingHandler extends SimpleChannelInboundHandler<String> {

    private final UserRoutingService routingService;
    private Channel backendChannel;
    private boolean routeEstablished = false;

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Send FTP greeting
        ctx.writeAndFlush("220 File Transfer Gateway Ready\r\n");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) throws Exception {
        if (routeEstablished && backendChannel != null) {
            // Already routed: forward to backend
            backendChannel.writeAndFlush(Unpooled.copiedBuffer(line + "\r\n", StandardCharsets.UTF_8));
            return;
        }

        // Parse USER command
        if (line.toUpperCase().startsWith("USER ")) {
            String username = line.substring(5).trim();
            UserRoutingService.RouteDecision route = routingService.routeFtp(username);

            if (route == null) {
                ctx.writeAndFlush("530 User unknown and no legacy server configured\r\n");
                ctx.close();
                return;
            }

            log.info("FTP gateway routing: user={} → {}:{} legacy={}", username, route.host(), route.port(), route.isLegacy());

            // Connect to backend
            Channel clientChannel = ctx.channel();
            Bootstrap b = new Bootstrap()
                    .group(clientChannel.eventLoop())
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // Forward backend responses back to the FTP client
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext backendCtx, Object msg) {
                                    clientChannel.writeAndFlush(msg);
                                }
                                @Override
                                public void channelInactive(ChannelHandlerContext backendCtx) {
                                    clientChannel.close();
                                }
                            });
                        }
                    });

            b.connect(route.host(), route.port()).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    backendChannel = future.channel();
                    routeEstablished = true;
                    // Replay the USER command to the backend
                    backendChannel.writeAndFlush(Unpooled.copiedBuffer(line + "\r\n", StandardCharsets.UTF_8));
                } else {
                    ctx.writeAndFlush("421 Backend connection failed\r\n");
                    ctx.close();
                }
            });
        } else {
            // Received a command before USER — unlikely but handle gracefully
            ctx.writeAndFlush("530 Please send USER first\r\n");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (backendChannel != null) backendChannel.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("FTP gateway error: {}", cause.getMessage());
        ctx.close();
    }
}
