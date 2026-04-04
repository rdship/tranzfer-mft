package com.filetransfer.gateway.server;

import com.filetransfer.gateway.routing.UserRoutingService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * FTP Gateway: Netty-based TCP proxy that peeks at the FTP USER command
 * to determine routing (internal vs legacy), then bridges both connections.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FtpGatewayConfig {

    private final UserRoutingService routingService;

    @Value("${gateway.ftp.port:2121}")
    private int gatewayFtpPort;

    @Bean(destroyMethod = "shutdownGracefully")
    public NioEventLoopGroup ftpGatewayBossGroup() {
        return new NioEventLoopGroup(1);
    }

    @Bean(destroyMethod = "shutdownGracefully")
    public NioEventLoopGroup ftpGatewayWorkerGroup() {
        return new NioEventLoopGroup();
    }

    @Bean
    public ApplicationRunner ftpGatewayRunner(@Qualifier("ftpGatewayBossGroup") NioEventLoopGroup ftpGatewayBossGroup,
                                               @Qualifier("ftpGatewayWorkerGroup") NioEventLoopGroup ftpGatewayWorkerGroup) {
        return args -> {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(ftpGatewayBossGroup, ftpGatewayWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new DelimiterBasedFrameDecoder(8192, Unpooled.wrappedBuffer(new byte[]{'\r', '\n'})),
                                    new StringDecoder(StandardCharsets.UTF_8),
                                    new StringEncoder(StandardCharsets.UTF_8),
                                    new FtpRoutingHandler(routingService)
                            );
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            bootstrap.bind(gatewayFtpPort).sync();
            log.info("FTP gateway started on port {}", gatewayFtpPort);
        };
    }
}
