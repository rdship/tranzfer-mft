package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.dmz.inspection.ContentScreeningBridge;
import com.filetransfer.dmz.inspection.DeepPacketInspector;
import com.filetransfer.dmz.inspection.FtpCommandFilter;
import com.filetransfer.dmz.qos.BandwidthQoS;
import com.filetransfer.dmz.security.*;
import com.filetransfer.dmz.tls.KeystoreIntegration;
import com.filetransfer.dmz.tls.TlsTerminator;
import com.filetransfer.dmz.tunnel.TunnelAcceptor;
import com.filetransfer.dmz.tunnel.TunnelAiVerdictClient;
import com.filetransfer.dmz.tunnel.TunnelBackendHealthChecker;
import com.filetransfer.dmz.tunnel.TunnelContentScreeningBridge;
import com.filetransfer.dmz.tunnel.TunnelKeystoreIntegration;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.channel.nio.NioEventLoopGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of all TcpProxyServer instances.
 * Supports adding/removing/listing mappings at runtime without restart.
 *
 * Initializes and wires all enterprise security components:
 * - AI-powered security (verdict, rate limiting, connection tracking)
 * - TLS termination with mTLS and Keystore Manager integration
 * - Network zone enforcement (EXTERNAL → DMZ → INTERNAL)
 * - Egress filtering (anti-SSRF, anti-exfiltration)
 * - Deep packet inspection and protocol-specific filtering
 * - Content screening bridge (AML/sanctions via screening-service)
 * - Bandwidth QoS with priority queuing
 * - Backend health checking with circuit breaker
 * - Persistent compliance-grade audit logging
 * - PROXY protocol support for real client IP preservation
 * - Graceful connection draining on shutdown
 */
@Slf4j
@Service
public class ProxyManager {

    @Getter private final DmzProperties properties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private SpiffeProxyAuth spiffeProxyAuth;

    private final ConcurrentHashMap<String, TcpProxyServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PortMapping> mappings = new ConcurrentHashMap<>();

    // ── Core security components ──
    @Getter private ConnectionTracker connectionTracker;
    @Getter private RateLimiter rateLimiter;
    @Getter private AiVerdictClient aiVerdictClient;
    @Getter private ThreatEventReporter eventReporter;
    @Getter private SecurityMetrics securityMetrics;
    @Getter private boolean securityEnabled;

    // ── Prometheus metrics registry ──
    @Getter private PrometheusMeterRegistry prometheusRegistry;

    // ── Enterprise security components ──
    @Getter private AuditLogger auditLogger;
    @Getter private BackendHealthChecker healthChecker;
    @Getter private TlsTerminator tlsTerminator;
    @Getter private KeystoreIntegration keystoreIntegration;
    @Getter private ZoneEnforcer zoneEnforcer;
    @Getter private EgressFilter egressFilter;
    @Getter private DeepPacketInspector deepPacketInspector;
    @Getter private FtpCommandFilter ftpCommandFilter;
    @Getter private ContentScreeningBridge contentScreeningBridge;
    @Getter private BandwidthQoS bandwidthQoS;
    @Getter private ConnectionDrainer connectionDrainer;

    // ── Tunnel (single-port multiplexed DMZ tunnel) ──
    @Getter private TunnelAcceptor tunnelAcceptor;

    /** Whether inbound PROXY protocol parsing is enabled (LB in front). */
    public boolean isInboundProxyProtocolEnabled() {
        return properties.getProxyProtocol() != null
                && properties.getProxyProtocol().isInboundEnabled();
    }

    /** Whether SSH banner rewriting is enabled. */
    public boolean isSshBannerRewriteEnabled() {
        return properties.getSecurity() != null
                && properties.getSecurity().isSshBannerRewrite();
    }

    /** The SSH banner string to present to clients. */
    public String getSshBanner() {
        return properties.getSecurity() != null
                ? properties.getSecurity().getSshBanner()
                : "SSH-2.0-TranzFer_MFT_Proxy";
    }

    public ProxyManager(DmzProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        DmzProperties.Security secConfig = properties.getSecurity();
        this.securityEnabled = secConfig.isEnabled();

        // ── Audit logger (always initialize if enabled — independent of security) ──
        initAuditLogger();

        // ── Core AI security ──
        if (securityEnabled) {
            initCoreSecurity(secConfig);
        } else {
            log.info("Security layer DISABLED — running in pass-through mode");
        }

        // ── Enterprise security components ──
        initTls();
        initZoneEnforcer();
        initEgressFilter();
        initInspection();
        initHealthChecker();
        initQoS();
        initDrainer();
        initTunnel();

        // ── Load default port mappings ──
        if (properties.getMappings() != null) {
            properties.getMappings().forEach(m -> {
                m.setActive(true);
                try {
                    add(m);
                } catch (Exception e) {
                    log.warn("Skipping mapping [{}]: {} (backend may not be available yet — add via REST later)",
                        m.getName(), e.getMessage());
                }
            });
        }

        logStartupSummary();
    }

    private void initCoreSecurity(DmzProperties.Security secConfig) {
        log.info("Initializing AI-powered security layer");
        log.info("  AI Engine URL: {}", secConfig.getAiEngineUrl());
        log.info("  Verdict timeout: {}ms", secConfig.getVerdictTimeoutMs());
        log.info("  Default rate limit: {}/min, {} concurrent",
            secConfig.getDefaultRatePerMinute(), secConfig.getDefaultMaxConcurrent());

        this.connectionTracker = new ConnectionTracker();
        this.rateLimiter = new RateLimiter();
        this.securityMetrics = new SecurityMetrics();

        rateLimiter.setDefaultMaxPerMinute(secConfig.getDefaultRatePerMinute());
        rateLimiter.setDefaultMaxConcurrent(secConfig.getDefaultMaxConcurrent());
        rateLimiter.setDefaultMaxBytesPerMinute(secConfig.getDefaultMaxBytesPerMinute());
        rateLimiter.setGlobalMaxPerMinute(secConfig.getGlobalRatePerMinute());

        int replicaCount = Integer.parseInt(
                System.getenv().getOrDefault("REPLICA_COUNT", "1"));
        rateLimiter.setReplicaCount(replicaCount);

        this.aiVerdictClient = new AiVerdictClient(
            secConfig.getAiEngineUrl(), secConfig.getVerdictTimeoutMs(),
            spiffeProxyAuth);

        this.eventReporter = new ThreatEventReporter(
            aiVerdictClient,
            secConfig.getEventQueueCapacity(),
            secConfig.getEventBatchSize(),
            secConfig.getEventFlushIntervalMs());

        // ── Prometheus metrics registry ──
        this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheusRegistry.gauge("dmz_connections_active",
                connectionTracker, ct -> (double) ct.getActiveConnectionCount());
        securityMetrics.registerMicrometer(prometheusRegistry);
        log.info("Prometheus metrics registry initialized");

        log.info("Core security initialized");
    }

    private void initAuditLogger() {
        DmzProperties.Audit auditConfig = properties.getAudit();
        if (auditConfig.isEnabled()) {
            this.auditLogger = new AuditLogger(
                auditConfig.getLogDirectory(),
                auditConfig.getMaxDays(),
                auditConfig.getMaxFileSizeMb(),
                true);
            log.info("Audit logger initialized: dir={}, retention={}d",
                auditConfig.getLogDirectory(), auditConfig.getMaxDays());
        }
    }

    private void initTls() {
        DmzProperties.Tls tlsConfig = properties.getTls();
        if (tlsConfig.isEnabled()) {
            TlsTerminator.TlsConfig defaultTls = new TlsTerminator.TlsConfig(
                true, null, null, null, null, false,
                tlsConfig.getMinTlsVersion(), List.of(),
                false, 3600, 10_000);
            this.tlsTerminator = new TlsTerminator(defaultTls, tlsConfig.isBlockWeakCiphers());

            this.keystoreIntegration = new KeystoreIntegration(
                tlsConfig.getKeystoreManagerUrl(),
                spiffeProxyAuth,
                tlsConfig.getCertCacheDir(),
                tlsConfig.getCertRefreshIntervalSeconds());
            keystoreIntegration.start();
            log.info("TLS termination initialized: minTLS={}, keystoreManager={}",
                tlsConfig.getMinTlsVersion(), tlsConfig.getKeystoreManagerUrl());
        }
    }

    private void initZoneEnforcer() {
        DmzProperties.Zones zonesConfig = properties.getZones();
        if (zonesConfig.isEnabled()) {
            Map<ZoneEnforcer.Zone, List<String>> zoneCidrs = new EnumMap<>(ZoneEnforcer.Zone.class);
            zonesConfig.getCidrs().forEach((zoneName, cidrs) -> {
                try {
                    ZoneEnforcer.Zone zone = ZoneEnforcer.Zone.valueOf(zoneName);
                    zoneCidrs.put(zone, cidrs);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown zone name in config: {}", zoneName);
                }
            });
            this.zoneEnforcer = new ZoneEnforcer(zoneCidrs, ZoneEnforcer.defaultRules());
            log.info("Zone enforcer initialized: {} zones configured", zoneCidrs.size());
        }
    }

    private void initEgressFilter() {
        DmzProperties.Egress egressConfig = properties.getEgress();
        if (egressConfig.isEnabled()) {
            this.egressFilter = new EgressFilter(new EgressFilter.EgressConfig(
                List.of(), // allowedDestinations populated per-mapping
                false,     // blockPrivateRanges: false — backends are private
                egressConfig.isBlockLinkLocal(),
                egressConfig.isBlockMetadataService(),
                egressConfig.isBlockLoopback(),
                egressConfig.isDnsPinning(),
                egressConfig.getMaxDnsResolutionMs(),
                egressConfig.getBlockedPorts(),
                egressConfig.getDnsTtlSeconds()));
            log.info("Egress filter initialized: blockedPorts={}", egressConfig.getBlockedPorts());
        }
    }

    private void initInspection() {
        DmzProperties.Inspection inspConfig = properties.getInspection();
        if (inspConfig.isEnabled()) {
            this.deepPacketInspector = new DeepPacketInspector(new DeepPacketInspector.InspectionConfig(
                true,
                true, "TLSv1.2",
                true,
                inspConfig.isBlockSshV1(),
                List.of(),
                true, 8192,
                inspConfig.isBlockSqlInjection(),
                inspConfig.isBlockCommandInjection(),
                inspConfig.isBlockPathTraversal()));

            if (inspConfig.isFtpFilterEnabled()) {
                this.ftpCommandFilter = new FtpCommandFilter(new FtpCommandFilter.FtpFilterConfig(
                    true, true, true, true, true,
                    List.of(), List.of(), 512, false));
            }

            this.contentScreeningBridge = new ContentScreeningBridge(
                inspConfig.getScreeningServiceUrl(),
                spiffeProxyAuth,
                new ContentScreeningBridge.ContentScreeningConfig(
                    false, 50_000_000L, 30, true, false, true,
                    List.of("FTP", "SFTP", "HTTP")));

            log.info("Deep packet inspection initialized: DPI={}, FTP filter={}, screening={}",
                true, inspConfig.isFtpFilterEnabled(), false);
        }
    }

    private void initHealthChecker() {
        DmzProperties.HealthCheck hcConfig = properties.getHealthCheck();
        if (hcConfig.isEnabled()) {
            this.healthChecker = new BackendHealthChecker(
                hcConfig.getIntervalSeconds(),
                hcConfig.getTimeoutSeconds(),
                hcConfig.getUnhealthyThreshold(),
                hcConfig.getHealthyThreshold());
            healthChecker.start();
            log.info("Backend health checker initialized: interval={}s, unhealthyAfter={}",
                hcConfig.getIntervalSeconds(), hcConfig.getUnhealthyThreshold());
        }
    }

    private void initQoS() {
        DmzProperties.Qos qosConfig = properties.getQos();
        if (qosConfig.isEnabled()) {
            this.bandwidthQoS = new BandwidthQoS(new BandwidthQoS.QoSConfig(
                true, qosConfig.getGlobalMaxBytesPerSecond(),
                qosConfig.getPerMappingMaxBytesPerSecond(), 0, 5, 20));
            bandwidthQoS.start();
            log.info("Bandwidth QoS initialized: globalMax={}B/s", qosConfig.getGlobalMaxBytesPerSecond());
        }
    }

    private void initDrainer() {
        this.connectionDrainer = new ConnectionDrainer(30);
    }

    private void initTunnel() {
        DmzProperties.Tunnel tunnelConfig = properties.getTunnel();
        if (tunnelConfig == null || !tunnelConfig.isEnabled()) {
            return;
        }
        try {
            NioEventLoopGroup bossGroup = new NioEventLoopGroup(1, r -> {
                Thread t = new Thread(r, "tunnel-boss");
                t.setDaemon(true);
                return t;
            });
            NioEventLoopGroup workerGroup = new NioEventLoopGroup(0, r -> {
                Thread t = new Thread(r, "tunnel-worker");
                t.setDaemon(true);
                return t;
            });

            this.tunnelAcceptor = new TunnelAcceptor(
                tunnelConfig.getPort(), bossGroup, workerGroup,
                null, // TLS context — extend here when tunnel TLS is needed
                tunnelConfig.getMaxStreams(), tunnelConfig.getWindowSize());
            tunnelAcceptor.start();

            // Swap security components to tunnel-aware versions once tunnel is active
            // (tunnel adapter subclasses fall back to direct HTTP when tunnel is down)
            if (securityEnabled && aiVerdictClient != null) {
                DmzProperties.Security sec = properties.getSecurity();
                this.aiVerdictClient = new TunnelAiVerdictClient(
                    sec.getAiEngineUrl(), sec.getVerdictTimeoutMs(),
                    spiffeProxyAuth, tunnelAcceptor);
                // Re-create event reporter with tunnel-aware client
                if (eventReporter != null) {
                    eventReporter.shutdown();
                    this.eventReporter = new ThreatEventReporter(
                        aiVerdictClient,
                        sec.getEventQueueCapacity(),
                        sec.getEventBatchSize(),
                        sec.getEventFlushIntervalMs());
                }
            }
            if (contentScreeningBridge != null) {
                DmzProperties.Inspection insp = properties.getInspection();
                this.contentScreeningBridge = new TunnelContentScreeningBridge(
                    insp.getScreeningServiceUrl(),
                    spiffeProxyAuth,
                    new ContentScreeningBridge.ContentScreeningConfig(
                        false, 50_000_000L, 30, true, false, true,
                        List.of("FTP", "SFTP", "HTTP")),
                    tunnelAcceptor);
            }
            if (keystoreIntegration != null) {
                DmzProperties.Tls tlsConfig = properties.getTls();
                keystoreIntegration.shutdown();
                this.keystoreIntegration = new TunnelKeystoreIntegration(
                    tlsConfig.getKeystoreManagerUrl(),
                    spiffeProxyAuth,
                    tlsConfig.getCertCacheDir(),
                    tlsConfig.getCertRefreshIntervalSeconds(),
                    tunnelAcceptor);
                keystoreIntegration.start();
            }
            if (healthChecker != null) {
                healthChecker.shutdown();
                this.healthChecker = new TunnelBackendHealthChecker(
                    properties.getHealthCheck().getIntervalSeconds(),
                    properties.getHealthCheck().getTimeoutSeconds(),
                    properties.getHealthCheck().getUnhealthyThreshold(),
                    properties.getHealthCheck().getHealthyThreshold(),
                    tunnelAcceptor);
                healthChecker.start();
            }

            log.info("Tunnel initialized on port {} (maxStreams={}, windowSize={})",
                tunnelConfig.getPort(), tunnelConfig.getMaxStreams(), tunnelConfig.getWindowSize());
        } catch (Exception e) {
            log.error("Failed to initialize tunnel: {}", e.getMessage(), e);
            this.tunnelAcceptor = null;
        }
    }

    public void add(PortMapping mapping) {
        if (servers.containsKey(mapping.getName())) {
            throw new IllegalArgumentException("Mapping already exists: " + mapping.getName());
        }

        // ── Fail-fast: egress filter on static backend target (DNS resolved once here, not per-connection) ──
        if (egressFilter != null) {
            EgressFilter.EgressCheckResult egress = egressFilter.checkDestination(
                mapping.getTargetHost(), mapping.getTargetPort());
            if (!egress.allowed()) {
                throw new IllegalArgumentException(
                    "Egress filter blocked backend " + mapping.getTargetHost() + ":"
                    + mapping.getTargetPort() + " for mapping " + mapping.getName()
                    + ": " + egress.reason());
            }
        }

        // ── Fail-fast: zone enforcement on static backend target (DNS resolved once here) ──
        if (zoneEnforcer != null) {
            // Pre-resolve target zone so per-connection checks only classify the source IP (no DNS)
            ZoneEnforcer.Zone targetZone = zoneEnforcer.classifyHost(mapping.getTargetHost());
            mapping.setCachedTargetZone(targetZone);
        }

        // Register backend for health checking
        if (healthChecker != null) {
            PortMapping.HealthCheckPolicy hcp = mapping.getHealthCheckPolicy();
            if (hcp == null || hcp.isEnabled()) {
                healthChecker.registerBackend(mapping.getName(),
                    mapping.getTargetHost(), mapping.getTargetPort());
                // Register Prometheus gauge for this backend's health
                if (prometheusRegistry != null) {
                    String backendName = mapping.getName();
                    prometheusRegistry.gauge("dmz_backend_health",
                            io.micrometer.core.instrument.Tags.of("backend", backendName),
                            healthChecker, hc -> hc.isHealthy(backendName) ? 1.0 : 0.0);
                }
            }
        }

        TcpProxyServer server;
        if (securityEnabled) {
            ManualSecurityFilter manualFilter = null;
            PortMapping.SecurityPolicy policy = mapping.getSecurityPolicy();
            if (policy != null) {
                manualFilter = new ManualSecurityFilter(policy);
                rateLimiter.setPortDefaults(mapping.getListenPort(),
                    policy.getRateLimitPerMinute(), policy.getMaxConcurrent(),
                    policy.getMaxBytesPerMinute());
                log.info("Mapping [{}]: tier={}, manual filter active",
                    mapping.getName(), policy.getSecurityTier());
            }
            server = new TcpProxyServer(mapping,
                connectionTracker, rateLimiter,
                aiVerdictClient, eventReporter, securityMetrics,
                manualFilter, this);
        } else {
            server = new TcpProxyServer(mapping, this);
        }

        try {
            server.start();
            servers.put(mapping.getName(), server);
            mappings.put(mapping.getName(), mapping);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start proxy for " + mapping.getName(), e);
        }
    }

    /**
     * Update the security policy for an existing mapping (hot-reconfigure).
     * Performs an in-place update with zero downtime — the server keeps running,
     * existing connections are unaffected, and only new connections pick up
     * the updated policy.
     */
    public void updateSecurityPolicy(String name, PortMapping.SecurityPolicy policy) {
        PortMapping existing = mappings.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Mapping not found: " + name);
        }
        TcpProxyServer server = servers.get(name);
        if (server == null) {
            throw new IllegalStateException("Server not running for mapping: " + name);
        }

        // 1. Update the policy on the mapping (SecurityPolicy getters used by
        //    IntelligentProxyHandler at connection init time via mapping reference)
        existing.setSecurityPolicy(policy);

        // 2. Build a new ManualSecurityFilter from the updated policy
        ManualSecurityFilter newFilter = (policy != null) ? new ManualSecurityFilter(policy) : null;

        // 3. Update rate limiter port defaults for the new limits
        if (securityEnabled && rateLimiter != null && policy != null) {
            rateLimiter.setPortDefaults(existing.getListenPort(),
                policy.getRateLimitPerMinute(), policy.getMaxConcurrent(),
                policy.getMaxBytesPerMinute());
        }

        // 4. Hot-swap the filter in the running server — volatile write ensures
        //    visibility to Netty I/O threads creating new connections
        server.updateSecurityFilter(newFilter);

        log.info("Hot-reconfigured security for mapping [{}]: tier={}", name, policy.getSecurityTier());
    }

    public void remove(String name) {
        TcpProxyServer server = servers.remove(name);
        mappings.remove(name);
        if (server != null) {
            if (connectionDrainer != null && server.getActiveConnections().get() > 0) {
                log.info("Draining active connections for mapping [{}]", name);
                connectionDrainer.drainSync(server.getServerChannel(), server.getActiveConnections());
            }
            server.stop();
        }
        if (healthChecker != null) healthChecker.removeBackend(name);
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> result = new ArrayList<>();
        mappings.forEach((name, mapping) -> {
            TcpProxyServer server = servers.get(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("listenPort", mapping.getListenPort());
            entry.put("targetHost", mapping.getTargetHost());
            entry.put("targetPort", mapping.getTargetPort());
            entry.put("active", mapping.isActive());
            entry.put("bytesForwarded", server != null ? server.getBytesForwarded().get() : 0);
            entry.put("activeConnections", server != null ? server.getActiveConnections().get() : 0);
            entry.put("securityEnabled", securityEnabled);
            if (mapping.getSecurityPolicy() != null) {
                entry.put("securityTier", mapping.getSecurityPolicy().getSecurityTier());
            } else {
                entry.put("securityTier", securityEnabled ? "AI" : "NONE");
            }
            // Enterprise security status
            entry.put("tlsEnabled", mapping.getTlsPolicy() != null && mapping.getTlsPolicy().isEnabled());
            entry.put("proxyProtocol", mapping.isProxyProtocolEnabled());
            entry.put("auditEnabled", mapping.isAuditEnabled());
            if (healthChecker != null) {
                entry.put("backendHealthy", healthChecker.isHealthy(name));
            }
            result.add(entry);
        });
        return result;
    }

    public Collection<PortMapping> getMappings() {
        return Collections.unmodifiableCollection(mappings.values());
    }

    /**
     * Returns listener status for all active port mappings — which ports are bound,
     * connections per port, and detected protocol.
     */
    public List<Map<String, Object>> getListenerStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        mappings.forEach((name, mapping) -> {
            TcpProxyServer server = servers.get(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("listenPort", mapping.getListenPort());
            entry.put("bound", server != null && server.getServerChannel() != null
                    && server.getServerChannel().isActive());
            entry.put("activeConnections", server != null ? server.getActiveConnections().get() : 0);
            entry.put("bytesForwarded", server != null ? server.getBytesForwarded().get() : 0);
            entry.put("targetHost", mapping.getTargetHost());
            entry.put("targetPort", mapping.getTargetPort());
            entry.put("securityTier", mapping.getSecurityPolicy() != null
                    ? mapping.getSecurityPolicy().getSecurityTier() : (securityEnabled ? "AI" : "NONE"));
            if (healthChecker != null) {
                entry.put("backendHealthy", healthChecker.isHealthy(name));
            }
            result.add(entry);
        });
        return result;
    }

    /**
     * Add an IP/CIDR to a mapping's blacklist and hot-reload the security filter.
     */
    public void addBlacklistIp(String name, String ip) {
        modifyIpList(name, ip, true, true);
    }

    /**
     * Remove an IP/CIDR from a mapping's blacklist and hot-reload the security filter.
     */
    public void removeBlacklistIp(String name, String ip) {
        modifyIpList(name, ip, true, false);
    }

    /**
     * Add an IP/CIDR to a mapping's whitelist and hot-reload the security filter.
     */
    public void addWhitelistIp(String name, String ip) {
        modifyIpList(name, ip, false, true);
    }

    /**
     * Remove an IP/CIDR from a mapping's whitelist and hot-reload the security filter.
     */
    public void removeWhitelistIp(String name, String ip) {
        modifyIpList(name, ip, false, false);
    }

    /**
     * Modifies a mapping's IP blacklist or whitelist and hot-reloads the ManualSecurityFilter.
     *
     * @param name      mapping name
     * @param ip        IP or CIDR to add/remove
     * @param blacklist true=blacklist, false=whitelist
     * @param add       true=add, false=remove
     */
    private void modifyIpList(String name, String ip, boolean blacklist, boolean add) {
        PortMapping mapping = mappings.get(name);
        if (mapping == null) {
            throw new IllegalArgumentException("Mapping not found: " + name);
        }
        TcpProxyServer server = servers.get(name);
        if (server == null) {
            throw new IllegalStateException("Server not running for mapping: " + name);
        }

        PortMapping.SecurityPolicy policy = mapping.getSecurityPolicy();
        if (policy == null) {
            policy = PortMapping.SecurityPolicy.builder().build();
            mapping.setSecurityPolicy(policy);
        }

        // Get current list (immutable from @Builder.Default) — copy to mutable
        List<String> list = new ArrayList<>(blacklist
                ? policy.getIpBlacklist() : policy.getIpWhitelist());

        if (add) {
            if (!list.contains(ip)) list.add(ip);
        } else {
            list.remove(ip);
        }

        // Write back
        if (blacklist) {
            policy.setIpBlacklist(List.copyOf(list));
        } else {
            policy.setIpWhitelist(List.copyOf(list));
        }

        // Hot-reload the filter
        ManualSecurityFilter newFilter = new ManualSecurityFilter(policy);
        server.updateSecurityFilter(newFilter);
        log.info("Updated {} for mapping [{}]: {} IP {}", blacklist ? "blacklist" : "whitelist",
                name, add ? "added" : "removed", ip);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down DMZ proxy — draining all connections");

        // Drain all active connections
        servers.forEach((name, server) -> {
            if (connectionDrainer != null && server.getActiveConnections().get() > 0) {
                connectionDrainer.drainSync(server.getServerChannel(), server.getActiveConnections());
            }
        });

        servers.values().forEach(TcpProxyServer::stop);
        if (eventReporter != null) eventReporter.shutdown();
        if (aiVerdictClient != null) aiVerdictClient.shutdown();
        if (healthChecker != null) healthChecker.shutdown();
        if (keystoreIntegration != null) keystoreIntegration.shutdown();
        if (bandwidthQoS != null) bandwidthQoS.shutdown();
        if (contentScreeningBridge != null) contentScreeningBridge.shutdown();
        if (tunnelAcceptor != null) tunnelAcceptor.stop();
        if (egressFilter != null) egressFilter.shutdown();
        if (auditLogger != null) {
            auditLogger.flush();
            auditLogger.shutdown();
        }
        if (prometheusRegistry != null) {
            prometheusRegistry.close();
        }
        log.info("DMZ proxy shutdown complete");
    }

    private void logStartupSummary() {
        List<String> features = new ArrayList<>();
        if (securityEnabled) features.add("ai_security");
        if (prometheusRegistry != null) features.add("prometheus_metrics");
        if (auditLogger != null) features.add("audit_logging");
        if (tlsTerminator != null) features.add("tls_termination");
        if (zoneEnforcer != null) features.add("zone_enforcement");
        if (egressFilter != null) features.add("egress_filtering");
        if (deepPacketInspector != null) features.add("deep_packet_inspection");
        if (ftpCommandFilter != null) features.add("ftp_command_filter");
        if (healthChecker != null) features.add("backend_health_check");
        if (bandwidthQoS != null) features.add("bandwidth_qos");
        if (tunnelAcceptor != null) features.add("single_port_tunnel");
        features.add("proxy_protocol");
        features.add("connection_draining");
        log.info("DMZ proxy started with {} enterprise security features: {}",
            features.size(), features);
    }
}
