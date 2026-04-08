package com.filetransfer.gateway.tunnel;

import com.filetransfer.tunnel.control.ControlMessage;
import com.filetransfer.tunnel.keepalive.TunnelKeepAliveHandler;
import com.filetransfer.tunnel.protocol.TunnelFrameCodec;
import com.filetransfer.tunnel.stream.TunnelStreamManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Outbound tunnel client that connects from the internal zone to dmz-proxy:9443.
 * <p>
 * All cross-zone traffic (data streams, control messages, health probes) is multiplexed
 * over this single TCP connection. The connection is re-established automatically with
 * exponential backoff on disconnect.
 * <p>
 * Pipeline: [SslHandler (opt)] -> TunnelFrameCodec -> IdleStateHandler -> TunnelKeepAliveHandler
 *           -> InternalTunnelHandler
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "tunnel.enabled", havingValue = "true")
public class TunnelClient {

    private final TunnelClientProperties properties;

    private TunnelControlForwarder controlForwarder;
    private EventLoopGroup workerGroup;
    private volatile Channel channel;
    private volatile InternalTunnelHandler internalHandler;
    private volatile TunnelReconnectHandler reconnectHandler;
    private volatile SslContext sslContext;

    @Value("${server.port:8085}")
    private int gatewayPort;

    public TunnelClient(TunnelClientProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.controlForwarder = new TunnelControlForwarder(properties, gatewayPort);

        // Use daemon threads so the JVM can shut down cleanly
        this.workerGroup = new NioEventLoopGroup(2, r -> {
            Thread t = new Thread(r, "tunnel-client-io");
            t.setDaemon(true);
            return t;
        });

        this.reconnectHandler = new TunnelReconnectHandler(
                this, properties.getReconnectBaseMs(),
                properties.getReconnectMaxMs(), properties.getReconnectJitter());

        // Initialize TLS if configured
        if (properties.isTlsEnabled()) {
            try {
                SslContextBuilder builder = SslContextBuilder.forClient();
                if (properties.getTlsCertPath() != null && properties.getTlsKeyPath() != null) {
                    builder.keyManager(
                            new File(properties.getTlsCertPath()),
                            new File(properties.getTlsKeyPath()));
                }
                this.sslContext = builder.build();
                log.info("Tunnel TLS enabled (cert={}, key={})",
                        properties.getTlsCertPath(), properties.getTlsKeyPath());
            } catch (Exception e) {
                log.error("Failed to initialize tunnel TLS: {}", e.getMessage(), e);
                throw new IllegalStateException("Tunnel TLS initialization failed", e);
            }
        }

        connect();
    }

    /**
     * Initiates a connection to the DMZ proxy. Called on startup and by the reconnect handler.
     */
    public void connect() {
        String host = properties.getDmzHost();
        int port = properties.getDmzPort();

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // Optional TLS
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port));
                        }

                        // Frame codec
                        pipeline.addLast("frameCodec", new TunnelFrameCodec());

                        // Keepalive: reader idle = pong timeout, writer idle = ping interval
                        pipeline.addLast("idleState", new IdleStateHandler(
                                properties.getKeepaliveTimeoutSeconds(),
                                properties.getKeepaliveIntervalSeconds(),
                                0, TimeUnit.SECONDS));
                        pipeline.addLast("keepAlive", new TunnelKeepAliveHandler());

                        // Core frame handler
                        internalHandler = new InternalTunnelHandler(properties, controlForwarder, TunnelClient.this);
                        pipeline.addLast("tunnelHandler", internalHandler);

                        // Reconnect on disconnect
                        pipeline.addLast("reconnect", reconnectHandler);
                    }
                });

        log.info("Connecting tunnel to {}:{}...", host, port);

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                reconnectHandler.resetBackoff();
                log.info("Tunnel established to {}:{}", host, port);
            } else {
                log.warn("Tunnel connection to {}:{} failed: {}", host, port,
                        future.cause().getMessage());
                // Schedule reconnect via the worker group since there's no channel context yet
                workerGroup.schedule(this::connect,
                        properties.getReconnectBaseMs(), TimeUnit.MILLISECONDS);
            }
        });
    }

    @PreDestroy
    public void disconnect() {
        log.info("Shutting down tunnel client");
        if (channel != null && channel.isActive()) {
            channel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    /**
     * Returns true if the tunnel connection is active.
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    /**
     * Returns the underlying Netty channel (for low-level operations).
     */
    Channel getChannel() {
        return channel;
    }

    /**
     * Returns the stream manager for the current connection, or null if disconnected.
     */
    public TunnelStreamManager getStreamManager() {
        InternalTunnelHandler handler = internalHandler;
        return handler != null ? handler.getStreamManager() : null;
    }

    /**
     * Sends a control request through the tunnel and returns a future for the response.
     *
     * @param request   the control message (correlationId will be generated if null)
     * @param timeoutMs timeout in milliseconds
     * @return future that completes with the response ControlMessage
     */
    public CompletableFuture<ControlMessage> sendControlRequest(ControlMessage request, long timeoutMs) {
        InternalTunnelHandler handler = internalHandler;
        if (handler == null || !isActive()) {
            CompletableFuture<ControlMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Tunnel is not active"));
            return failed;
        }
        return handler.sendControlRequest(request, timeoutMs);
    }
}
