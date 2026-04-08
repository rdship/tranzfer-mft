package com.filetransfer.dmz.security;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.inspection.DeepPacketInspector;
import com.filetransfer.dmz.inspection.FtpCommandFilter;
import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.proxy.ProxyManager;
import com.filetransfer.dmz.security.AiVerdictClient.Action;
import com.filetransfer.dmz.security.AiVerdictClient.CachedVerdict;
import com.filetransfer.dmz.security.ProtocolDetector.DetectionResult;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.extern.slf4j.Slf4j;



/**
 * Intelligent Proxy Handler — Netty ChannelInboundHandler that orchestrates
 * all security checks before allowing a connection to proceed to the backend.
 *
 * Pipeline position: after TLS (if present), before RelayHandler.
 *
 * Full security pipeline per connection:
 * 1. Manual security filter (IP whitelist/blacklist, geo, transfer windows)
 * 2. Rate limiting (per-IP, per-port, global)
 * 3. AI verdict (AI/AI_LLM tiers — with cache and fallback)
 * 4. Protocol detection (SSH, FTP, HTTP, TLS — zero-copy)
 * 5. Deep packet inspection (protocol content validation)
 * 6. FTP command filtering (bounce attack, dangerous commands)
 * 7. Byte rate limiting and QoS
 * 8. Audit logging throughout
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

    // Enterprise components (from ProxyManager, nullable)
    private final ProxyManager manager;

    // Per-connection state
    private String sourceIp;
    private boolean protocolDetected = false;
    private String detectedProtocol = "UNKNOWN";
    private boolean connectionAllowed = false;
    private boolean securityCheckDone = false;
    private int verdictRisk = 0;

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
             listenPort, mappingName, null, null, null);
    }

    /** Per-mapping security constructor with enterprise components. */
    public IntelligentProxyHandler(
            ConnectionTracker connectionTracker,
            RateLimiter rateLimiter,
            AiVerdictClient aiClient,
            ThreatEventReporter eventReporter,
            SecurityMetrics metrics,
            int listenPort,
            String mappingName,
            PortMapping.SecurityPolicy securityPolicy,
            ManualSecurityFilter manualFilter,
            ProxyManager manager) {
        this.connectionTracker = connectionTracker;
        this.rateLimiter = rateLimiter;
        this.aiClient = aiClient;
        this.eventReporter = eventReporter;
        this.metrics = metrics;
        this.listenPort = listenPort;
        this.mappingName = mappingName;
        this.securityPolicy = securityPolicy;
        this.manualFilter = manualFilter;
        this.manager = manager;
        this.securityTier = (securityPolicy != null) ? securityPolicy.getSecurityTier() : "AI";
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        metrics.recordConnection();
        metrics.recordPort(listenPort);

        sourceIp = InboundProxyProtocolHandler.resolveClientIp(ctx.channel());

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
                auditVerdict("BLOCK", 100, manualResult.reason(), false);
                ctx.close();
                return;
            }
        }

        // ── Step 2: Rate limiter (instant, no network) ──
        if (!rateLimiter.tryAcquire(sourceIp, listenPort)) {
            log.info("[{}] Rate limited: {}", mappingName, sourceIp);
            metrics.recordRateLimited();
            metrics.recordAction("RATE_LIMITED");
            connectionTracker.connectionRejected(sourceIp);
            eventReporter.reportRateLimitHit(sourceIp, listenPort, detectedProtocol);
            auditVerdict("RATE_LIMITED", 0, "rate_limit_exceeded", false);
            ctx.close();
            return;
        }

        // ── Step 3: AI verdict (AI and AI_LLM tiers only) ──
        if (!"RULES".equals(securityTier)) {
            CachedVerdict verdict = aiClient.getVerdict(sourceIp, listenPort, null, securityTier);
            metrics.recordAiVerdictRequest();
            verdictRisk = verdict.riskScore();

            if (verdict.signals() != null && verdict.signals().contains("FALLBACK")) {
                metrics.recordAiVerdictFallback();
            }

            if (verdict.maxConnectionsPerMinute() != null) {
                rateLimiter.setIpLimits(sourceIp,
                    verdict.maxConnectionsPerMinute(),
                    verdict.maxConcurrentConnections() != null ? verdict.maxConcurrentConnections() : 20,
                    verdict.maxBytesPerMinute() != null ? verdict.maxBytesPerMinute() : 500_000_000L);
            }

            boolean llmUsed = verdict.signals() != null && verdict.signals().contains("LLM_USED");

            switch (verdict.action()) {
                case BLACKHOLE -> {
                    log.info("[{}] BLACKHOLE: {} (risk={}, reason={})",
                        mappingName, sourceIp, verdict.riskScore(), verdict.reason());
                    metrics.recordBlackholed();
                    metrics.recordAction("BLACKHOLE");
                    connectionTracker.connectionRejected(sourceIp);
                    rateLimiter.release(sourceIp);
                    eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol, "blackholed");
                    auditVerdict("BLACKHOLE", verdict.riskScore(), verdict.reason(), llmUsed);
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
                    auditVerdict("BLOCK", verdict.riskScore(), verdict.reason(), llmUsed);
                    ctx.close();
                    return;
                }
                case THROTTLE -> {
                    log.debug("[{}] THROTTLED: {} (risk={})", mappingName, sourceIp, verdict.riskScore());
                    metrics.recordThrottled();
                    metrics.recordAction("THROTTLE");
                    auditVerdict("THROTTLE", verdict.riskScore(), verdict.reason(), llmUsed);
                    connectionAllowed = true;
                }
                case CHALLENGE -> {
                    log.debug("[{}] CHALLENGE: {} (risk={})", mappingName, sourceIp, verdict.riskScore());
                    metrics.recordAction("CHALLENGE");
                    auditVerdict("CHALLENGE", verdict.riskScore(), verdict.reason(), llmUsed);
                    connectionAllowed = true;
                }
                case ALLOW -> {
                    metrics.recordAllowed();
                    metrics.recordAction("ALLOW");
                    auditVerdict("ALLOW", verdict.riskScore(), verdict.reason(), llmUsed);
                    connectionAllowed = true;
                }
            }
        } else {
            metrics.recordAllowed();
            metrics.recordAction("RULES_ALLOW");
            connectionAllowed = true;
            boolean shouldLog = securityPolicy == null || securityPolicy.isConnectionLogging();
            if (shouldLog) {
                log.info("[{}] RULES tier: allowed {} on port {}", mappingName, sourceIp, listenPort);
            }
            auditVerdict("RULES_ALLOW", 0, "rules_tier_allow", false);
        }

        connectionTracker.connectionOpened(ctx.channel(), sourceIp, listenPort);
        eventReporter.reportConnectionOpened(sourceIp, listenPort, detectedProtocol);
        auditConnectionEvent("OPEN", "allowed");

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

                // ── Deep packet inspection ──
                DeepPacketInspector dpi = manager != null ? manager.getDeepPacketInspector() : null;
                if (dpi != null) {
                    DeepPacketInspector.InspectionResult inspResult =
                        dpi.inspect(buf, detectedProtocol, listenPort);
                    if (!inspResult.allowed()) {
                        log.info("[{}] DPI blocked {} protocol={}: {} (severity={})",
                            mappingName, sourceIp, detectedProtocol,
                            inspResult.finding(), inspResult.severity());
                        metrics.recordBlocked();
                        metrics.recordAction("DPI_BLOCK");
                        auditInspection(inspResult);
                        eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol,
                            "dpi:" + inspResult.finding());
                        ctx.close();
                        return;
                    }
                }

                // ── FTP command filtering ──
                FtpCommandFilter ftpFilter = manager != null ? manager.getFtpCommandFilter() : null;
                if (ftpFilter != null && "FTP".equals(detectedProtocol)) {
                    FtpCommandFilter.FtpCommandResult cmdResult =
                        ftpFilter.checkCommand(buf, sourceIp);
                    if (!cmdResult.allowed()) {
                        log.info("[{}] FTP command blocked for {}: {} {}",
                            mappingName, sourceIp, cmdResult.command(), cmdResult.reason());
                        metrics.recordBlocked();
                        metrics.recordAction("FTP_CMD_BLOCK");
                        eventReporter.reportRejected(sourceIp, listenPort, detectedProtocol,
                            "ftp_cmd:" + cmdResult.command());
                        ctx.close();
                        return;
                    }
                }

                // Re-check verdict with protocol info (async, don't block)
                if (!securityCheckDone && !"RULES".equals(securityTier)) {
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

        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
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

            auditConnectionEvent("CLOSE",
                String.format("duration=%dms bytes=%d/%d", durationMs, bytesIn, bytesOut));

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

    // ── Audit helpers ──────────────────────────────────────────────────

    private void auditConnectionEvent(String event, String detail) {
        AuditLogger audit = manager != null ? manager.getAuditLogger() : null;
        if (audit == null) return;
        try {
            audit.logConnection(event, sourceIp, listenPort, mappingName,
                securityTier, connectionAllowed ? "ALLOW" : "BLOCK",
                verdictRisk, detectedProtocol, detail);
        } catch (Exception e) {
            // Never let audit errors affect proxy operations
        }
    }

    private void auditVerdict(String action, int risk, String reason, boolean llmUsed) {
        AuditLogger audit = manager != null ? manager.getAuditLogger() : null;
        if (audit == null) return;
        try {
            audit.logVerdict(sourceIp, listenPort, mappingName, action, risk, reason, llmUsed, null);
        } catch (Exception e) {
            // Silent
        }
    }

    private void auditInspection(DeepPacketInspector.InspectionResult result) {
        AuditLogger audit = manager != null ? manager.getAuditLogger() : null;
        if (audit == null) return;
        try {
            audit.logInspection(sourceIp, listenPort, mappingName, detectedProtocol,
                result.finding(), result.severity().name(), result.detail());
        } catch (Exception e) {
            // Silent
        }
    }
}
