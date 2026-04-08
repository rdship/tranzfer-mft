package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.dmz.inspection.DeepPacketInspector;
import com.filetransfer.dmz.inspection.FtpCommandFilter;
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
import io.netty.util.ReferenceCountUtil;
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

                        // ── Step 4: Egress filter — SKIPPED (pre-checked at mapping creation) ──
                        // EgressFilter.checkDestination() involves DNS resolution for hostnames.
                        // Since the backend target (host:port) is static per mapping, the check
                        // runs once in ProxyManager.add() — not here on the event loop.

                        // ── Step 5: Zone enforcement (source IP only — target pre-resolved) ──
                        ZoneEnforcer ze = manager != null ? manager.getZoneEnforcer() : null;
                        if (ze != null) {
                            InetSocketAddress remote = (InetSocketAddress) clientCh.remoteAddress();
                            String sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";
                            // Source IP is always an IP literal — classifyIp does NO DNS lookup.
                            // Target zone was pre-resolved at mapping creation (no DNS here).
                            ZoneEnforcer.ZoneCheckResult zoneResult = ze.checkTransitionFast(
                                sourceIp, mapping.getCachedTargetZone(), mapping.getTargetPort());
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
                        // Resolve client IP and inspection components for in-flight DPI
                        InetSocketAddress clientRemote = (InetSocketAddress) clientCh.remoteAddress();
                        String clientIp = clientRemote != null
                            ? clientRemote.getAddress().getHostAddress() : "unknown";
                        DeepPacketInspector dpi = manager != null
                            ? manager.getDeepPacketInspector() : null;
                        FtpCommandFilter ftpFilter = manager != null
                            ? manager.getFtpCommandFilter() : null;

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
                                        // Backend→client: no inspection needed
                                        backendCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded,
                                            null, null, null, 0, null, false));
                                    }
                                });

                        ChannelFuture backendFuture = backendBootstrap.connect(
                            mapping.getTargetHost(), mapping.getTargetPort());
                        Channel backendCh = backendFuture.channel();

                        // Client→backend: inspect in-flight data via DPI + FTP filter
                        clientCh.pipeline().addLast("relay", new RelayHandler(backendCh, bytesForwarded,
                            dpi, ftpFilter, clientIp, mapping.getListenPort(),
                            mapping.getName(), true));

                        backendFuture.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                auditConnection("OPEN", clientCh, "connected");
                                clientCh.read();
                                backendCh.read();
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

    /**
     * Relay handler: forwards bytes between inbound and outbound channels.
     * When {@code inspectTraffic} is true (client→backend direction), runs DPI and FTP
     * command filtering on every packet before forwarding.
     */
    static class RelayHandler extends ChannelInboundHandlerAdapter {
        private final Channel outboundChannel;
        private final AtomicLong bytesForwarded;

        // Inspection components (null when inspection disabled or backend→client direction)
        private final DeepPacketInspector dpi;
        private final FtpCommandFilter ftpFilter;
        private final String clientIp;
        private final int listenPort;
        private final String mappingName;
        private final boolean inspectTraffic;

        // Protocol detected from first packet (lazy, only when inspecting)
        private String detectedProtocol;

        RelayHandler(Channel outboundChannel, AtomicLong bytesForwarded,
                     DeepPacketInspector dpi, FtpCommandFilter ftpFilter,
                     String clientIp, int listenPort, String mappingName,
                     boolean inspectTraffic) {
            this.outboundChannel = outboundChannel;
            this.bytesForwarded = bytesForwarded;
            this.dpi = dpi;
            this.ftpFilter = ftpFilter;
            this.clientIp = clientIp;
            this.listenPort = listenPort;
            this.mappingName = mappingName;
            this.inspectTraffic = inspectTraffic;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                bytesForwarded.addAndGet(buf.readableBytes());

                // ── In-flight inspection (client→backend only) ──
                if (inspectTraffic && buf.readableBytes() > 0) {
                    // Detect protocol on first packet if not yet known
                    if (detectedProtocol == null && buf.readableBytes() >= 3) {
                        ProtocolDetector.DetectionResult detection =
                            ProtocolDetector.detect(buf, listenPort);
                        detectedProtocol = ProtocolDetector.protocolToString(detection.protocol());
                    }

                    // Deep packet inspection for file-transfer protocols
                    if (dpi != null && detectedProtocol != null) {
                        String proto = detectedProtocol.toUpperCase();
                        if ("SFTP".equals(proto) || "SSH".equals(proto)
                                || "FTP".equals(proto) || "FTPS".equals(proto)) {
                            DeepPacketInspector.InspectionResult inspResult =
                                dpi.inspect(buf, detectedProtocol, listenPort);
                            if (!inspResult.allowed()) {
                                log.warn("[{}] Relay DPI blocked {} protocol={}: {} (severity={})",
                                    mappingName, clientIp, detectedProtocol,
                                    inspResult.finding(), inspResult.severity());
                                ReferenceCountUtil.release(msg);
                                ctx.close();
                                return;
                            }
                        }
                    }

                    // FTP command filtering on every command packet
                    if (ftpFilter != null && detectedProtocol != null
                            && "FTP".equals(detectedProtocol.toUpperCase())) {
                        FtpCommandFilter.FtpCommandResult cmdResult =
                            ftpFilter.checkCommand(buf, clientIp);
                        if (!cmdResult.allowed()) {
                            log.warn("[{}] Relay FTP filter blocked {} cmd={}: {}",
                                mappingName, clientIp, cmdResult.command(), cmdResult.reason());
                            ReferenceCountUtil.release(msg);
                            ctx.close();
                            return;
                        }
                    }
                }
            }

            // ── Forward to outbound channel ──
            if (outboundChannel.isActive()) {
                outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) ctx.channel().read();
                    else f.channel().close();
                });
            } else {
                ReferenceCountUtil.release(msg);
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
