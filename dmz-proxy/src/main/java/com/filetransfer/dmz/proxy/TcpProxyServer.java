package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.dmz.inspection.DeepPacketInspector;
import com.filetransfer.dmz.inspection.FtpCommandFilter;
import com.filetransfer.dmz.security.*;
import com.filetransfer.dmz.tls.TlsTerminator;
import com.filetransfer.dmz.tunnel.TunnelAcceptor;
import com.filetransfer.dmz.qos.BandwidthQoS;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private volatile ManualSecurityFilter manualFilter;

    // Enterprise components (from ProxyManager)
    private final ProxyManager manager;

    // QoS bandwidth enforcement
    private final BandwidthQoS bandwidthQoS;

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
        this.bandwidthQoS = manager != null ? manager.getBandwidthQoS() : null;
        this.securityEnabled = connectionTracker != null && rateLimiter != null
            && aiVerdictClient != null && eventReporter != null && securityMetrics != null;
    }

    public void start() throws InterruptedException {
        // Pre-build TLS context if TLS termination enabled for this mapping
        final SslContext sslCtx = buildSslContext();

        final TcpProxyServer self = this;
        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel clientCh) {
                        activeConnections.incrementAndGet();
                        // QoS: generate connection ID and determine priority
                        String connectionId = mapping.getName() + "-" + clientCh.id().asShortText();
                        int qosPriority = resolveQosPriority(mapping);

                        // ── Step 0: Inbound PROXY protocol (if behind a load balancer) ──
                        boolean inboundPP = manager != null && manager.isInboundProxyProtocolEnabled();
                        if (inboundPP) {
                            // HAProxyMessageDecoder auto-removes itself after parsing the
                            // single PROXY header. InboundProxyProtocolHandler extracts the
                            // real client IP into a channel attribute and also self-removes.
                            clientCh.pipeline().addLast("ha-proxy-decoder", new HAProxyMessageDecoder());
                            clientCh.pipeline().addLast("ha-proxy-handler", new InboundProxyProtocolHandler());
                        }

                        // ── Step 1: TLS termination (if enabled) ──
                        if (sslCtx != null) {
                            SslHandler sslHandler = sslCtx.newHandler(clientCh.alloc());
                            clientCh.pipeline().addLast("tls", sslHandler);
                            sslHandler.handshakeFuture().addListener(f -> {
                                if (f.isSuccess()) {
                                    auditTls(clientCh, sslHandler);
                                } else {
                                    log.warn("[{}] TLS handshake failed from {}",
                                        mapping.getName(),
                                        InboundProxyProtocolHandler.resolveClientIp(clientCh));
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
                                mapping.getName(),
                                InboundProxyProtocolHandler.resolveClientIp(clientCh));
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
                        // When inbound PROXY protocol is enabled, the real client IP is not
                        // yet available (arrives with the first data read). Zone enforcement
                        // is deferred to ZoneCheckHandler which runs after the PROXY header
                        // is decoded. When PP is disabled, check immediately with socket IP.
                        ZoneEnforcer ze = manager != null ? manager.getZoneEnforcer() : null;
                        if (ze != null && !inboundPP) {
                            String sourceIp = InboundProxyProtocolHandler.resolveClientIp(clientCh);
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
                        if (ze != null && inboundPP) {
                            // Deferred zone check: runs as a pipeline handler after
                            // InboundProxyProtocolHandler has set the real client IP.
                            clientCh.pipeline().addLast("zone-check",
                                new DeferredZoneCheckHandler(ze, mapping, self));
                        }

                        // ── Step 6: Backend connection with PROXY protocol ──
                        // When inbound PP is enabled, real IP is not yet available — pass
                        // null and let RelayHandler lazily resolve from channel attribute.
                        // When PP is disabled, resolve immediately from socket address.
                        String clientIp = inboundPP ? null
                            : InboundProxyProtocolHandler.resolveClientIp(clientCh);
                        DeepPacketInspector dpi = manager != null
                            ? manager.getDeepPacketInspector() : null;
                        FtpCommandFilter ftpFilter = manager != null
                            ? manager.getFtpCommandFilter() : null;

                        // ── Backend connection: tunnel path vs direct path ──
                        TunnelAcceptor tunnel = manager != null ? manager.getTunnelAcceptor() : null;
                        boolean useTunnel = tunnel != null && tunnel.isConnected()
                                && tunnel.getConnector() != null;
                        DmzProperties.Tunnel tunnelCfg = manager != null ? manager.getProperties().getTunnel() : null;
                        boolean tunnelOnly = tunnel != null && tunnelCfg != null
                                && tunnelCfg.isEnabled() && !tunnelCfg.isFallbackToDirect();

                        if (useTunnel) {
                            // ── Tunnel path: multiplex over single tunnel connection ──
                            try {
                                ChannelFuture streamFuture = tunnel.getConnector()
                                        .connectViaStream(mapping, clientCh);
                                Channel streamCh = streamFuture.channel();

                                // Backend→client: banner rewrite + relay via tunnel stream
                                if (manager.isSshBannerRewriteEnabled()) {
                                    streamCh.pipeline().addLast("ssh-banner-rewrite",
                                        new SshBannerRewriteHandler(manager.getSshBanner()));
                                }
                                final String tunnelPasvHost = resolvePasvExternalHost();
                                if (tunnelPasvHost != null) {
                                    streamCh.pipeline().addLast("pasv-rewrite",
                                        new PasvResponseRewriter(mapping.getName(), tunnelPasvHost));
                                }
                                streamCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded,
                                    null, null, null, 0, null, false,
                                    null, null, 0));

                                // Client→backend: inspect in-flight data via DPI + FTP filter + QoS
                                clientCh.pipeline().addLast("relay", new RelayHandler(streamCh, bytesForwarded,
                                    dpi, ftpFilter, clientIp, mapping.getListenPort(),
                                    mapping.getName(), true, bandwidthQoS, connectionId, qosPriority));

                                if (bandwidthQoS != null) {
                                    bandwidthQoS.registerConnection(connectionId, mapping.getName(), qosPriority);
                                }
                                auditConnection("OPEN", clientCh, "tunnel-connected");
                                clientCh.read();

                                clientCh.closeFuture().addListener(cf -> {
                                    activeConnections.decrementAndGet();
                                    if (bandwidthQoS != null) {
                                        bandwidthQoS.unregisterConnection(connectionId);
                                    }
                                    streamCh.close();
                                });
                            } catch (Exception e) {
                                if (tunnelOnly) {
                                    log.error("Tunnel stream failed for [{}] and fallbackToDirect=false — rejecting connection: {}",
                                        mapping.getName(), e.getMessage());
                                    auditConnection("TUNNEL_FAILED", clientCh, "no_fallback");
                                    clientCh.close();
                                    return;
                                }
                                log.warn("Tunnel stream failed for [{}]: {} — falling back to direct",
                                    mapping.getName(), e.getMessage());
                                // Fall through to direct connection below
                                useTunnel = false;
                            }
                        } else if (tunnelOnly && tunnel != null && tunnel.isActive()) {
                            // Tunnel is enabled with no fallback but not yet connected
                            log.warn("Tunnel not connected for [{}] and fallbackToDirect=false — rejecting connection",
                                mapping.getName());
                            auditConnection("TUNNEL_UNAVAILABLE", clientCh, "no_fallback");
                            clientCh.close();
                            return;
                        }

                        if (!useTunnel) {
                            // ── Direct path: Bootstrap.connect() (default / fallback) ──
                            final String pasvExternalHost = resolvePasvExternalHost();
                            Bootstrap backendBootstrap = new Bootstrap()
                                    .group(clientCh.eventLoop())
                                    .channel(NioSocketChannel.class)
                                    .option(ChannelOption.AUTO_READ, false)
                                    .handler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel backendCh) {
                                            if (mapping.isProxyProtocolEnabled()) {
                                                backendCh.pipeline().addLast("proxy-protocol",
                                                    new LazyProxyProtocolOutboundHandler(clientCh));
                                            }
                                            if (manager != null && manager.isSshBannerRewriteEnabled()) {
                                                backendCh.pipeline().addLast("ssh-banner-rewrite",
                                                    new SshBannerRewriteHandler(manager.getSshBanner()));
                                            }
                                            // FTP PASV rewriter on backend→client only. Must sit BEFORE
                                            // RelayHandler so the rewritten bytes are what get forwarded.
                                            if (pasvExternalHost != null) {
                                                backendCh.pipeline().addLast("pasv-rewrite",
                                                    new PasvResponseRewriter(mapping.getName(), pasvExternalHost));
                                            }
                                            backendCh.pipeline().addLast(new RelayHandler(clientCh, bytesForwarded,
                                                null, null, null, 0, null, false,
                                                null, null, 0));
                                        }
                                    });

                            ChannelFuture backendFuture = backendBootstrap.connect(
                                mapping.getTargetHost(), mapping.getTargetPort());
                            Channel backendCh = backendFuture.channel();

                            clientCh.pipeline().addLast("relay", new RelayHandler(backendCh, bytesForwarded,
                                dpi, ftpFilter, clientIp, mapping.getListenPort(),
                                mapping.getName(), true, bandwidthQoS, connectionId, qosPriority));

                            backendFuture.addListener((ChannelFutureListener) f -> {
                                if (f.isSuccess()) {
                                    if (bandwidthQoS != null) {
                                        bandwidthQoS.registerConnection(connectionId, mapping.getName(), qosPriority);
                                    }
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
                                if (bandwidthQoS != null) {
                                    bandwidthQoS.unregisterConnection(connectionId);
                                }
                                backendCh.close();
                            });
                        }
                    }
                });

        serverChannel = b.bind(mapping.getListenPort()).sync().channel();

        List<String> features = new java.util.ArrayList<>();
        features.add("security=" + (securityEnabled ? "ON" : "OFF"));
        if (sslCtx != null) features.add("tls=ON");
        if (mapping.isProxyProtocolEnabled()) features.add("proxy-protocol-out=ON");
        if (manager != null && manager.isInboundProxyProtocolEnabled())
            features.add("proxy-protocol-in=ON");

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

    /**
     * Hot-swap the manual security filter for this server.
     * The new filter applies to all subsequent connections; existing connections
     * are not affected (they already captured a reference at pipeline init time).
     *
     * @param filter the new ManualSecurityFilter (may be null to remove filtering)
     */
    public void updateSecurityFilter(ManualSecurityFilter filter) {
        this.manualFilter = filter;
    }

    /**
     * Resolve the externally-visible host for FTP PASV rewriting, honoring
     * precedence: per-mapping {@code ftpDataChannelPolicy.externalHost} >
     * service-wide {@code dmz.external-host} > null (= no rewriting).
     *
     * <p>Returns null when this mapping has no FTP data-channel policy or PASV
     * rewriting is disabled on it — the pipeline won't install the rewriter.
     */
    private String resolvePasvExternalHost() {
        PortMapping.FtpDataChannelPolicy fdc = mapping.getFtpDataChannelPolicy();
        if (fdc == null || !fdc.isRewritePasvResponse()) return null;
        if (fdc.getExternalHost() != null && !fdc.getExternalHost().isBlank()) {
            return fdc.getExternalHost();
        }
        if (manager != null) {
            String svc = manager.getProperties().getExternalHost();
            if (svc != null && !svc.isBlank()) return svc;
        }
        log.warn("[{}] FTP data-channel policy present but no externalHost resolvable " +
                "(neither per-mapping nor dmz.external-host). PASV replies will NOT be rewritten " +
                "— clients may fail passive mode.", mapping.getName());
        return null;
    }

    /**
     * Resolve QoS priority for a mapping: from QoSPolicy > SecurityPolicy tier > default (5).
     */
    private static int resolveQosPriority(PortMapping mapping) {
        if (mapping.getQosPolicy() != null && mapping.getQosPolicy().isEnabled()) {
            return mapping.getQosPolicy().getPriority();
        }
        if (mapping.getSecurityPolicy() != null) {
            return BandwidthQoS.tierToPriority(mapping.getSecurityPolicy().getSecurityTier());
        }
        return 5;
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
            String sourceIp = InboundProxyProtocolHandler.resolveClientIp(clientCh);
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
            String sourceIp = InboundProxyProtocolHandler.resolveClientIp(clientCh);
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
        private final String initialClientIp;  // null when inbound PP active (lazy resolve)
        private final int listenPort;
        private final String mappingName;
        private final boolean inspectTraffic;

        // QoS bandwidth enforcement (null when disabled or backend→client)
        private final BandwidthQoS qos;
        private final String connectionId;
        private final int qosPriority;

        // Protocol detected from first packet (lazy, only when inspecting)
        private String detectedProtocol;
        // Lazily resolved client IP (from channel attribute or initial value)
        private String clientIp;

        RelayHandler(Channel outboundChannel, AtomicLong bytesForwarded,
                     DeepPacketInspector dpi, FtpCommandFilter ftpFilter,
                     String clientIp, int listenPort, String mappingName,
                     boolean inspectTraffic,
                     BandwidthQoS qos, String connectionId, int qosPriority) {
            this.outboundChannel = outboundChannel;
            this.bytesForwarded = bytesForwarded;
            this.dpi = dpi;
            this.ftpFilter = ftpFilter;
            this.initialClientIp = clientIp;
            this.listenPort = listenPort;
            this.mappingName = mappingName;
            this.inspectTraffic = inspectTraffic;
            this.qos = qos;
            this.connectionId = connectionId;
            this.qosPriority = qosPriority;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Lazy client IP resolution: when inbound PROXY protocol is active,
            // the real IP is set as a channel attribute after the first read.
            if (clientIp == null) {
                clientIp = initialClientIp != null ? initialClientIp
                        : InboundProxyProtocolHandler.resolveClientIp(ctx.channel());
            }

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

            // ── QoS bandwidth enforcement (client→backend direction) ──
            if (qos != null && msg instanceof ByteBuf qosBuf && qosBuf.readableBytes() > 0) {
                if (!qos.tryConsume(connectionId, mappingName, qosPriority, qosBuf.readableBytes())) {
                    // Bandwidth limit exceeded: forward this chunk but apply backpressure
                    // by pausing reads. Token bucket refills every 100ms.
                    ctx.channel().config().setAutoRead(false);
                    ctx.channel().eventLoop().schedule(() -> {
                        ctx.channel().config().setAutoRead(true);
                        ctx.channel().read();
                    }, 100, TimeUnit.MILLISECONDS);
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

    /**
     * One-shot handler that sanitizes the SSH version banner from the backend.
     * The SSH protocol includes the version string (V_S) in the key exchange hash,
     * so replacing it entirely would break the cryptographic handshake. Instead,
     * this handler strips the <b>comment</b> portion (the OS/distro info after the
     * software version) which is NOT part of the SSH identification string proper
     * but IS included in the hash — so we can only log and alert, not modify.
     *
     * <p>For FTP connections (220 banner), the banner IS safely rewritable since
     * FTP does not include it in any cryptographic computation.
     *
     * <p>One-shot: processes the first inbound message, then removes itself.
     */
    static class SshBannerRewriteHandler extends ChannelInboundHandlerAdapter {
        private static final byte[] SSH_PREFIX = "SSH-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        private static final byte[] FTP_220 = "220".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        private final String replacementBanner;

        SshBannerRewriteHandler(String replacementBanner) {
            this.replacementBanner = replacementBanner;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.pipeline().remove(this);

            if (msg instanceof ByteBuf buf && buf.readableBytes() >= 4) {
                int readerIdx = buf.readerIndex();

                // ── SSH banner: log but do NOT modify (breaks KEX hash) ──
                if (buf.getByte(readerIdx) == SSH_PREFIX[0]
                        && buf.getByte(readerIdx + 1) == SSH_PREFIX[1]
                        && buf.getByte(readerIdx + 2) == SSH_PREFIX[2]
                        && buf.getByte(readerIdx + 3) == SSH_PREFIX[3]) {
                    // Extract the banner for audit logging
                    int endIdx = findLineEnd(buf, readerIdx);
                    if (endIdx > readerIdx) {
                        byte[] bannerBytes = new byte[endIdx - readerIdx];
                        buf.getBytes(readerIdx, bannerBytes);
                        String banner = new String(bannerBytes, java.nio.charset.StandardCharsets.US_ASCII);
                        log.info("Backend SSH banner detected: {} (not rewritten — SSH KEX integrity)", banner);
                    }
                    // Pass through unmodified — SSH KEX requires original V_S
                    ctx.fireChannelRead(msg);
                    return;
                }

                // ── FTP 220 banner: safe to rewrite (no crypto dependency) ──
                if (buf.readableBytes() >= 3
                        && buf.getByte(readerIdx) == FTP_220[0]
                        && buf.getByte(readerIdx + 1) == FTP_220[1]
                        && buf.getByte(readerIdx + 2) == FTP_220[2]) {
                    int endIdx = findLineEnd(buf, readerIdx);
                    if (endIdx > readerIdx) {
                        String ftpBanner = "220 " + replacementBanner + " Ready\r\n";
                        byte[] ftpBytes = ftpBanner.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                        int afterBanner = endIdx + 2;
                        int remaining = buf.writerIndex() - afterBanner;

                        ByteBuf replacement = ctx.alloc().buffer(ftpBytes.length + remaining);
                        replacement.writeBytes(ftpBytes);
                        if (remaining > 0) {
                            replacement.writeBytes(buf, afterBanner, remaining);
                        }
                        log.info("FTP banner rewritten to: {}", replacementBanner);
                        ReferenceCountUtil.release(msg);
                        ctx.fireChannelRead(replacement);
                        return;
                    }
                }
            }
            ctx.fireChannelRead(msg);
        }

        private static int findLineEnd(ByteBuf buf, int from) {
            for (int i = from; i < buf.writerIndex() - 1; i++) {
                if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') return i;
            }
            return -1;
        }
    }

    /**
     * Lazy outbound PROXY protocol handler — resolves the real client IP from
     * the inbound channel's attribute at write time (not at construction time).
     * This is necessary when inbound PROXY protocol is active because the real
     * client IP is only available after the first inbound read decodes the header.
     *
     * <p>One-shot: removes itself after sending the header on the first write.
     */
    static class LazyProxyProtocolOutboundHandler extends ChannelOutboundHandlerAdapter {
        private final Channel inboundChannel;
        private volatile boolean headerSent = false;

        LazyProxyProtocolOutboundHandler(Channel inboundChannel) {
            this.inboundChannel = inboundChannel;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            if (!headerSent) {
                headerSent = true;

                // Resolve real client address — attribute set by InboundProxyProtocolHandler,
                // or fall back to socket address
                InetSocketAddress clientAddr;
                String realIp = inboundChannel.attr(InboundProxyProtocolHandler.REAL_CLIENT_IP).get();
                if (realIp != null) {
                    Integer realPort = inboundChannel.attr(InboundProxyProtocolHandler.REAL_CLIENT_PORT).get();
                    clientAddr = new InetSocketAddress(realIp, realPort != null ? realPort : 0);
                } else {
                    clientAddr = (InetSocketAddress) inboundChannel.remoteAddress();
                }

                InetSocketAddress localAddr = (InetSocketAddress) inboundChannel.localAddress();

                if (clientAddr != null && localAddr != null) {
                    // Replace ourselves with the real ProxyProtocolHandler (which builds
                    // and prepends the v1 header on this same write call).
                    ctx.pipeline().replace(this, "proxy-protocol-v1",
                            ProxyProtocolHandler.v1(clientAddr, localAddr));
                    // Delegate to the replacement handler which will prepend the header
                    ctx.pipeline().context("proxy-protocol-v1").write(msg, promise);
                    return;
                }

                // No valid addresses — remove ourselves and pass through
                ctx.pipeline().remove(this);
            }
            ctx.write(msg, promise);
        }
    }

    /**
     * Deferred zone enforcement handler — checks zone policy after the real
     * client IP has been extracted from the inbound PROXY protocol header.
     * Runs on {@code channelActive} (which is deferred by InboundProxyProtocolHandler
     * until after the PROXY header is decoded).
     *
     * <p>One-shot: removes itself after the zone check passes.
     */
    static class DeferredZoneCheckHandler extends ChannelInboundHandlerAdapter {
        private final ZoneEnforcer zoneEnforcer;
        private final PortMapping mapping;
        private final TcpProxyServer server;

        DeferredZoneCheckHandler(ZoneEnforcer zoneEnforcer, PortMapping mapping,
                                 TcpProxyServer server) {
            this.zoneEnforcer = zoneEnforcer;
            this.mapping = mapping;
            this.server = server;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            String sourceIp = InboundProxyProtocolHandler.resolveClientIp(ctx.channel());

            ZoneEnforcer.ZoneCheckResult zoneResult = zoneEnforcer.checkTransitionFast(
                    sourceIp, mapping.getCachedTargetZone(), mapping.getTargetPort());

            if (!zoneResult.allowed()) {
                log.warn("[{}] Zone violation (deferred): {} → {}: {}",
                        mapping.getName(), zoneResult.sourceZone(),
                        zoneResult.targetZone(), zoneResult.reason());
                server.auditConnection("ZONE_BLOCKED", (SocketChannel) ctx.channel(),
                        zoneResult.reason());
                server.activeConnections.decrementAndGet();
                ctx.close();
                return;
            }

            // Zone check passed — remove ourselves and continue
            ctx.pipeline().remove(this);
            super.channelActive(ctx);
        }
    }
}
