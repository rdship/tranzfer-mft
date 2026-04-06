package com.filetransfer.dmz.security;

import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.security.AiVerdictClient.Action;
import com.filetransfer.dmz.security.AiVerdictClient.CachedVerdict;
import com.filetransfer.dmz.security.ProtocolDetector.DetectionResult;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * Intelligent Proxy Handler — Netty ChannelInboundHandler that orchestrates
 * all security checks before allowing a connection to proceed to the backend.
 *
 * Pipeline position: FIRST handler (before RelayHandler).
 *
 * Supports per-mapping security tiers:
 * - RULES: local IP/geo/rate checks only, zero network calls
 * - AI: RULES + AI engine verdict
 * - AI_LLM: AI + LLM escalation for borderline cases
 *
 * When securityPolicy is null, falls back to existing global AI behavior.
 */
@Slf4j
public class IntelligentProxyHandler extends ChannelInboundHandlerAdapter {

    private final ConnectionTracker connectionTracker;
    private final RateLimiter rateLimiter;
    private final AiVerdictClient aiClient;
    private final ThreatEventReporter eventReporter;
    private final SecurityMetrics metrics;
    private final int listenPort;
    private final String mappingName;

    // Per-mapping security (nullable — null means global defaults)
    private final PortMapping.SecurityPolicy securityPolicy;
    private final ManualSecurityFilter manualFilter;
    private final String securityTier;

    // Per-connection state
    private String sourceIp;
    private boolean protocolDetected = false;
    private String detectedProtocol = "UNKNOWN";
    private boolean connectionAllowed = false;
    private boolean securityCheckDone = false;

    /** Backward-compatible constructor — global security, no per-mapping policy. */
    public IntelligentProxyHandler(
            ConnectionTracker connectionTracker,
            RateLimiter rateLimiter,
            AiVerdictClient aiClient,
            ThreatEventReporter eventReporter,
            SecurityMetrics metrics,
            int listenPort,
            String mappingName) {
        this(connectionTracker, rateLimiter, aiClient, eventReporter, metrics,
             listenPort, mappingName, null, null);
    }

    /** Per-mapping security constructor. */
    public IntelligentProxyHandler(
            ConnectionTracker connectionTracker,
            RateLimiter rateLimiter,
            AiVerdictClient aiClient,
            ThreatEventReporter eventReporter,
            SecurityMetrics metrics,
            int listenPort,
            String mappingName,
            PortMapping.SecurityPolicy securityPolicy,
            ManualSecurityFilter manualFilter) {
        this.connectionTracker = connectionTracker;
        this.rateLimiter = rateLimiter;
        this.aiClient = aiClient;
        this.eventReporter = eventReporter;
        this.metrics = metrics;
        this.listenPort = listenPort;
        this.mappingName = mappingName;
        this.securityPolicy = securityPolicy;
        this.manualFilter = manualFilter;
        this.securityTier = (securityPolicy != null) ? securityPolicy.getSecurityTier() : "AI";
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        metrics.recordConnection();
        metrics.recordPort(listenPort);

        // Extract source IP
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";

        log.debug("[{}] New connection from {} on port {}", mappingName, sourceIp, listenPort);

        // ── Step 1: Manual security checks (all tiers) ──
        if (manualFilter != null) {
            ManualSecurityFilter.FilterResult manualResult = manualFilter.checkConnection(sourceIp);
            if (!manualResult.allowed()) {
                log.info("[{}] Manual security blocked {} on port {}: {}",
                    mappingName, sourceIp, listenPort, manualResult.reason());
                metrics.recordBlocked();
                metrics.recordAction("MANUAL_BLOCK");
                connectionTracker.connectionRejected(sourceIp);
                eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol, manualResult.reason());
                ctx.close();
                return;
            }
        }

        // ── Step 2: Rate limiter (instant, no network — uses per-port defaults if set) ──
        if (!rateLimiter.tryAcquire(sourceIp, listenPort)) {
            log.info("[{}] Rate limited: {}", mappingName, sourceIp);
            metrics.recordRateLimited();
            metrics.recordAction("RATE_LIMITED");
            connectionTracker.connectionRejected(sourceIp);
            eventReporter.reportRateLimitHit(sourceIp, listenPort, detectedProtocol);
            ctx.close();
            return;
        }

        // ── Step 3: AI verdict (AI and AI_LLM tiers only) ──
        if (!"RULES".equals(securityTier)) {
            CachedVerdict verdict = aiClient.getVerdict(sourceIp, listenPort, null, securityTier);
            metrics.recordAiVerdictRequest();

            if (verdict.signals() != null && verdict.signals().contains("FALLBACK")) {
                metrics.recordAiVerdictFallback();
            }

            // Apply AI-driven rate limits if present
            if (verdict.maxConnectionsPerMinute() != null) {
                rateLimiter.setIpLimits(sourceIp,
                    verdict.maxConnectionsPerMinute(),
                    verdict.maxConcurrentConnections() != null ? verdict.maxConcurrentConnections() : 20,
                    verdict.maxBytesPerMinute() != null ? verdict.maxBytesPerMinute() : 500_000_000L);
            }

            switch (verdict.action()) {
                case BLACKHOLE -> {
                    log.info("[{}] BLACKHOLE: {} (risk={}, reason={})",
                        mappingName, sourceIp, verdict.riskScore(), verdict.reason());
                    metrics.recordBlackholed();
                    metrics.recordAction("BLACKHOLE");
                    connectionTracker.connectionRejected(sourceIp);
                    rateLimiter.release(sourceIp);
                    eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol, "blackholed");
                    ctx.channel().config().setOption(ChannelOption.SO_LINGER, 0);
                    ctx.close();
                    return;
                }
                case BLOCK -> {
                    log.info("[{}] BLOCKED: {} (risk={}, reason={})",
                        mappingName, sourceIp, verdict.riskScore(), verdict.reason());
                    metrics.recordBlocked();
                    metrics.recordAction("BLOCK");
                    connectionTracker.connectionRejected(sourceIp);
                    rateLimiter.release(sourceIp);
                    eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol, verdict.reason());
                    ctx.close();
                    return;
                }
                case THROTTLE -> {
                    log.debug("[{}] THROTTLED: {} (risk={})", mappingName, sourceIp, verdict.riskScore());
                    metrics.recordThrottled();
                    metrics.recordAction("THROTTLE");
                    connectionAllowed = true;
                }
                case CHALLENGE -> {
                    log.debug("[{}] CHALLENGE: {} (risk={})", mappingName, sourceIp, verdict.riskScore());
                    metrics.recordAction("CHALLENGE");
                    connectionAllowed = true;
                }
                case ALLOW -> {
                    metrics.recordAllowed();
                    metrics.recordAction("ALLOW");
                    connectionAllowed = true;
                }
            }
        } else {
            // RULES tier: no AI, just allow through after rules + rate limit checks
            metrics.recordAllowed();
            metrics.recordAction("RULES_ALLOW");
            connectionAllowed = true;
            boolean shouldLog = securityPolicy == null || securityPolicy.isConnectionLogging();
            if (shouldLog) {
                log.info("[{}] RULES tier: allowed {} on port {}", mappingName, sourceIp, listenPort);
            }
        }

        // Register the connection
        connectionTracker.connectionOpened(ctx.channel(), sourceIp, listenPort);
        eventReporter.reportConnectionOpened(sourceIp, listenPort, detectedProtocol);

        // Continue pipeline
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            long bytes = buf.readableBytes();
            metrics.recordBytesIn(bytes);
            connectionTracker.recordBytesIn(ctx.channel(), bytes);

            // ── Protocol detection on first data ──
            if (!protocolDetected && buf.readableBytes() >= 3) {
                DetectionResult detection = ProtocolDetector.detect(buf, listenPort);
                detectedProtocol = ProtocolDetector.protocolToString(detection.protocol());
                protocolDetected = true;
                metrics.recordProtocol(detectedProtocol);

                ConnectionTracker.IpState ipState = connectionTracker.get(sourceIp).orElse(null);
                if (ipState != null) {
                    ipState.setProtocol(detectedProtocol);
                }

                log.debug("[{}] Protocol detected: {} (confidence={}, detail={})",
                    mappingName, detectedProtocol, detection.confidence(), detection.detail());

                // Re-check verdict with protocol info (async, don't block)
                if (!securityCheckDone) {
                    securityCheckDone = true;
                    aiClient.getVerdictAsync(sourceIp, listenPort, detectedProtocol)
                        .thenAccept(v -> {
                            if (v.action() == Action.BLOCK || v.action() == Action.BLACKHOLE) {
                                log.info("[{}] Post-detection block: {} protocol={} risk={}",
                                    mappingName, sourceIp, detectedProtocol, v.riskScore());
                                ctx.close();
                            }
                        });
                }

                // Byte limit check
                if (!rateLimiter.checkBytes(sourceIp, bytes)) {
                    log.info("[{}] Byte rate limit exceeded for {}", mappingName, sourceIp);
                    eventReporter.reportRateLimitHit(sourceIp, listenPort, detectedProtocol);
                    ctx.close();
                    return;
                }
            }
        }

        // Pass through to relay handler
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Connection closed — report final stats
        ConnectionTracker.ConnectionInfo info = connectionTracker.connectionClosed(ctx.channel());
        rateLimiter.release(sourceIp);

        if (info != null) {
            long durationMs = java.time.Duration.between(info.openedAt(), java.time.Instant.now()).toMillis();
            long bytesIn = info.bytesIn().get();
            long bytesOut = info.bytesOut().get();

            metrics.recordBytesOut(bytesOut);

            eventReporter.reportConnectionClosed(
                sourceIp, listenPort, detectedProtocol,
                bytesIn, bytesOut, durationMs);

            log.debug("[{}] Connection closed: {} protocol={} duration={}ms bytes={}/{}",
                mappingName, sourceIp, detectedProtocol, durationMs, bytesIn, bytesOut);
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("[{}] Exception from {}: {}", mappingName, sourceIp, cause.getMessage());
        ctx.close();
    }
}
