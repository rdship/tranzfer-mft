package com.filetransfer.dmz.security;

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
 * Flow:
 * 1. Connection arrives → extract source IP
 * 2. Rate limiter check (instant, no network)
 * 3. Query AI verdict (cached or sync)
 * 4. First bytes arrive → protocol detection
 * 5. Apply verdict: ALLOW → pass through, BLOCK → close, THROTTLE → apply limits
 * 6. Report event to AI engine (async)
 * 7. On connection close → report final stats
 *
 * Product-agnostic: handles any TCP connection.
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

    // Per-connection state
    private String sourceIp;
    private boolean protocolDetected = false;
    private String detectedProtocol = "UNKNOWN";
    private boolean connectionAllowed = false;
    private boolean securityCheckDone = false;

    public IntelligentProxyHandler(
            ConnectionTracker connectionTracker,
            RateLimiter rateLimiter,
            AiVerdictClient aiClient,
            ThreatEventReporter eventReporter,
            SecurityMetrics metrics,
            int listenPort,
            String mappingName) {
        this.connectionTracker = connectionTracker;
        this.rateLimiter = rateLimiter;
        this.aiClient = aiClient;
        this.eventReporter = eventReporter;
        this.metrics = metrics;
        this.listenPort = listenPort;
        this.mappingName = mappingName;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        metrics.recordConnection();
        metrics.recordPort(listenPort);

        // Extract source IP
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        sourceIp = remote != null ? remote.getAddress().getHostAddress() : "unknown";

        log.debug("[{}] New connection from {} on port {}", mappingName, sourceIp, listenPort);

        // ── Step 1: Rate limiter (instant, no network) ──
        if (!rateLimiter.tryAcquire(sourceIp)) {
            log.info("[{}] Rate limited: {}", mappingName, sourceIp);
            metrics.recordRateLimited();
            metrics.recordAction("RATE_LIMITED");
            connectionTracker.connectionRejected(sourceIp);
            eventReporter.reportRateLimitHit(sourceIp, listenPort, detectedProtocol);
            ctx.close();
            return;
        }

        // ── Step 2: AI verdict (cached → sub-ms, uncached → sync with timeout) ──
        CachedVerdict verdict = aiClient.getVerdict(sourceIp, listenPort, null);
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
                // Silent drop: no RST, no FIN — just close silently
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
                // Connection continues but with rate limits applied
            }
            case CHALLENGE -> {
                log.debug("[{}] CHALLENGE: {} (risk={})", mappingName, sourceIp, verdict.riskScore());
                metrics.recordAction("CHALLENGE");
                connectionAllowed = true;
                // For TCP proxy, challenge = allow but monitor closely
            }
            case ALLOW -> {
                metrics.recordAllowed();
                metrics.recordAction("ALLOW");
                connectionAllowed = true;
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
