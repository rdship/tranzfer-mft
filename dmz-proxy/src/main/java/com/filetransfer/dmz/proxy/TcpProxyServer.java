package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.dmz.security.*;
import com.filetransfer.dmz.tls.TlsTerminator;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single Netty TCP proxy server for one port mapping.
 * Bidirectionally forwards bytes between the external client and the internal target.
 *
 * Full enterprise pipeline:
 * [TLS Handler] → [Security Handler] → [Relay Handler] → Backend
 *
 * Features:
 * - TLS termination with mTLS (optional per-mapping)
 * - AI-powered security with zone enforcement and DPI
 * - PROXY protocol header injection to backends
 * - Backend health checking (reject if backend unhealthy)
 * - Audit logging of all connection events
 * - Graceful connection draining on shutdown
 */
@Slf4j
public class TcpProxyServer {

    private final PortMapping mapping;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    @Getter private Channel serverChannel;

    // Security components (null if security disabled)
    private final ConnectionTracker connectionTracker;
    private final RateLimiter rateLimiter;
    private final AiVerdictClient aiVerdictClient;
    private final ThreatEventReporter eventReporter;
    private final SecurityMetrics securityMetrics;
    private final boolean securityEnabled;
    private final ManualSecurityFilter manualFilter;

    // Enterprise components (from ProxyManager)
    private final ProxyManager manager;

    @Getter
    private final AtomicLong bytesForwarded = new AtomicLong(0);
    @Getter
    private final AtomicLong activeConnections = new AtomicLong(0);

    /** Create a proxy server without security (backward compatible). */
    public TcpProxyServer(PortMapping mapping, ProxyManager manager) {
        this(mapping, null, null, null, null, null, null, manager);
    }

    /** Create a proxy server with per-mapping security policy. */
    public TcpProxyServer(PortMapping mapping,
                          ConnectionTracker connectionTracker,
                          RateLimiter rateLimiter,
                          AiVerdictClient aiVerdictClient,
                          ThreatEventReporter eventReporter,
                          SecurityMetrics securityMetrics,
                          ManualSecurityFilter manualFilter,
                          ProxyManager manager) {
        this.mapping = mapping;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.connectionTracker = connectionTracker;
        this.rateLimiter = rateLimiter;
        this.aiVerdictClient = aiVerdictClient;
        this.eventReporter = eventReporter;
        this.securityMetrics = securityMetrics;
        this.manualFilter = manualFilter;
        this.manager = manager;
        this.securityEnabled = connectionTracker != null && rateLimiter != null
            && aiVerdictClient != null && eventReporter != null && securityMetrics != null;
    }

    public void start() throws InterruptedException {
        // Pre-build TLS context if TLS termination enabled for this mapping
        final SslContext sslCtx = buildSslContext();

        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel clientCh) {
                        activeConnections.incrementAndGet();

                        // ── Step 1: TLS termination (if enabled) ──
                        if (sslCtx != null) {
                            SslHandler sslHandler = sslCtx.newHandler(clientCh.alloc());
                            clientCh.pipeline().addLast("tls", sslHandler);
                            sslHandler.handshakeFuture().addListener(f -> {
                                if (f.isSuccess()) {
                                    auditTls(clientCh, sslHandler);
                                } else {
                                    log.warn("[{}] TLS handshake failed from {}",
                                        mapping.getName(), clientCh.remoteAddress());
                                    clientCh.close();
                                }
                            });
                        }

                        // ── Step 2: Security handler ──
                        if (securityEnabled) {
                            clientCh.pipeline().addLast("security",
                                new IntelligentProxyHandler(
                                    connectionTracker, rateLimiter,
                                    aiVerdictClient, eventReporter, securityMetrics,
                                    mapping.getListenPort(), mapping.getName(),
                                    mapping.getSecurityPolicy(), manualFilter,
                                    manager));
                        }

                        // ── Step 3: Backend health check ──
                        BackendHealthChecker hc = manager != null ? manager.getHealthChecker() : null;
                        if (hc != null && !hc.isHealthy(mapping.getName())) {
                            log.warn("[{}] Backend unhealthy, rejecting connection from {}",
                                mapping.getName(), clientCh.remoteAddress());
                            auditConnection("REJECTED", clientCh, "backend_unhealthy");
                            activeConnections.decrementAndGet();
                            clientCh.close();
                            return;
                        }

                        // ── Step 4: Egress filter check on target ──
                        EgressFilter ef = manager != null ? manager.getEgressFilter() : null;
                        if (ef != null) {
                            EgressFilter.EgressCheckResult egress = ef.checkDestination(
                                mapping.getTargetHost(), mapping.getTargetPort());
                            if (!egress.allowed()) {
                                log.warn("[{}] Egress filter blocked backend {}:{}: {}",
                                    mapping.getName(), mapping.getTargetHost(),
                                    mapping.getTargetPort(), egress.reason());
                                auditConnection("EGRESS_BLOCKED", clientCh, egress.reason());
                                activeConnections.decrementAndGet();
                                clientCh.close();
                                return;
                            }
                        }

                        // ── Step 5: Zone enforcement ──
                        ZoneEnforcer ze = manager != null ? manager.getZoneEnforcer() : null;
                        if (ze != null) {
                            InetSocketAddress remote = (InetSocketAddress) clientCh.remoteAddress();
                            String sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";
                            ZoneEnforcer.ZoneCheckResult zoneResult = ze.checkTransition(
                                sourceIp, mapping.getTargetHost(), mapping.getTargetPort());
                            if (!zoneResult.allowed()) {
                                log.warn("[{}] Zone violation: {} → {}: {}",
                                    mapping.getName(), zoneResult.sourceZone(),
                                    zoneResult.targetZone(), zoneResult.reason());
                                auditConnection("ZONE_BLOCKED", clientCh, zoneResult.reason());
                                activeConnections.decrementAndGet();
                                clientCh.close();
                                return;
                            }
                        }

                        // ── Step 6: Backend connection with PROXY protocol ──
                        Bootstrap backendBootstrap = new Bootstrap()
                                .group(clientCh.eventLoop())
                                .channel(NioSocketChannel.class)
                                .option(ChannelOption.AUTO_READ, false)
                                .handler(new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel backendCh) {
                                        // PROXY protocol header (if enabled)
                                        if (mapping.isProxyProtocolEnabled()) {
                                            InetSocketAddress clientAddr = (InetSocketAddress) clientCh.remoteAddress();
                                            InetSocketAddress localAddr = (InetSocketAddress) clientCh.localAddress();
                                            if (clientAddr != null && localAddr != null) {
                                                backendCh.pipeline().addLast("proxy-protocol",
                                                    ProxyProtocolHandler.v1(clientAddr, localAddr));
                                            }
                                        }
                                        backendCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded));
                                    }
                                });

                        ChannelFuture backendFuture = backendBootstrap.connect(
                            mapping.getTargetHost(), mapping.getTargetPort());
                        Channel backendCh = backendFuture.channel();

                        clientCh.pipeline().addLast("relay", new RelayHandler(backendCh, bytesForwarded));

                        backendFuture.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                auditConnection("OPEN", clientCh, "connected");
                                clientCh.read();
                            } else {
                                log.warn("Backend connection failed: {}:{} -> {}",
                                    mapping.getName(), mapping.getTargetHost(), mapping.getTargetPort());
                                auditConnection("BACKEND_FAILED", clientCh, "connection_refused");
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

        List<String> features = new java.util.ArrayList<>();
        features.add("security=" + (securityEnabled ? "ON" : "OFF"));
        if (sslCtx != null) features.add("tls=ON");
        if (mapping.isProxyProtocolEnabled()) features.add("proxy-protocol=ON");

        log.info("DMZ proxy [{}]: listening :{} -> {}:{} [{}]",
                mapping.getName(), mapping.getListenPort(),
                mapping.getTargetHost(), mapping.getTargetPort(),
                String.join(", ", features));
    }

    public void stop() {
        if (serverChannel != null) serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        log.info("DMZ proxy [{}] stopped", mapping.getName());
    }

    private SslContext buildSslContext() {
        PortMapping.TlsPolicy tlsPolicy = mapping.getTlsPolicy();
        TlsTerminator terminator = manager != null ? manager.getTlsTerminator() : null;
        if (tlsPolicy == null || !tlsPolicy.isEnabled() || terminator == null) return null;

        try {
            TlsTerminator.TlsConfig tlsConfig = new TlsTerminator.TlsConfig(
                true,
                tlsPolicy.getCertPath(),
                tlsPolicy.getKeyPath(),
                tlsPolicy.getKeyPassword(),
                tlsPolicy.getTrustStorePath(),
                tlsPolicy.isRequireClientCert(),
                tlsPolicy.getMinTlsVersion(),
                tlsPolicy.getCipherSuites(),
                tlsPolicy.isEnableOcspStapling(),
                tlsPolicy.getSessionTimeoutSeconds(),
                tlsPolicy.getSessionCacheSize());
            return terminator.createServerContext(tlsConfig);
        } catch (Exception e) {
            log.error("[{}] Failed to create TLS context: {}", mapping.getName(), e.getMessage());
            return null;
        }
    }

    private void auditTls(SocketChannel clientCh, SslHandler sslHandler) {
        AuditLogger audit = manager != null ? manager.getAuditLogger() : null;
        if (audit == null || !mapping.isAuditEnabled()) return;
        try {
            javax.net.ssl.SSLSession session = sslHandler.engine().getSession();
            InetSocketAddress remote = (InetSocketAddress) clientCh.remoteAddress();
            String sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";
            audit.logTls(sourceIp, mapping.getListenPort(), mapping.getName(),
                session.getProtocol(), session.getCipherSuite(),
                session.getPeerCertificates() != null && session.getPeerCertificates().length > 0,
                session.getPeerCertificates() != null && session.getPeerCertificates().length > 0
                    ? session.getPeerCertificates()[0].toString().substring(0, Math.min(100, session.getPeerCertificates()[0].toString().length()))
                    : null);
        } catch (Exception e) {
            // Audit failures must never block proxy operations
            log.debug("Audit TLS logging error: {}", e.getMessage());
        }
    }

    private void auditConnection(String event, SocketChannel clientCh, String detail) {
        AuditLogger audit = manager != null ? manager.getAuditLogger() : null;
        if (audit == null || !mapping.isAuditEnabled()) return;
        try {
            InetSocketAddress remote = (InetSocketAddress) clientCh.remoteAddress();
            String sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";
            String tier = mapping.getSecurityPolicy() != null
                ? mapping.getSecurityPolicy().getSecurityTier() : "AI";
            audit.logConnection(event, sourceIp, mapping.getListenPort(),
                mapping.getName(), tier, event, 0, "UNKNOWN", detail);
        } catch (Exception e) {
            log.debug("Audit connection logging error: {}", e.getMessage());
        }
    }

    /** Simple relay: forward all bytes from inbound channel to the paired outbound channel */
    static class RelayHandler extends ChannelInboundHandlerAdapter {
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
