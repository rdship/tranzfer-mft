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

    private final DmzProperties properties;
    private final ConcurrentHashMap<String, TcpProxyServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PortMapping> mappings = new ConcurrentHashMap<>();

    // ── Core security components ──
    @Getter private ConnectionTracker connectionTracker;
    @Getter private RateLimiter rateLimiter;
    @Getter private AiVerdictClient aiVerdictClient;
    @Getter private ThreatEventReporter eventReporter;
    @Getter private SecurityMetrics securityMetrics;
    @Getter private boolean securityEnabled;

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

    /** Whether inbound PROXY protocol parsing is enabled (LB in front). */
    public boolean isInboundProxyProtocolEnabled() {
        return properties.getProxyProtocol() != null
                && properties.getProxyProtocol().isInboundEnabled();
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
            secConfig.getInternalApiKey());

        this.eventReporter = new ThreatEventReporter(
            aiVerdictClient,
            secConfig.getEventQueueCapacity(),
            secConfig.getEventBatchSize(),
            secConfig.getEventFlushIntervalMs());

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
                properties.getSecurity().getInternalApiKey(),
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
                properties.getSecurity().getInternalApiKey(),
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

    /** Update the security policy for an existing mapping (hot-reconfigure). */
    public void updateSecurityPolicy(String name, PortMapping.SecurityPolicy policy) {
        PortMapping existing = mappings.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Mapping not found: " + name);
        }
        existing.setSecurityPolicy(policy);
        remove(name);
        add(existing);
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
        if (egressFilter != null) egressFilter.shutdown();
        if (auditLogger != null) {
            auditLogger.flush();
            auditLogger.shutdown();
        }
        log.info("DMZ proxy shutdown complete");
    }

    private void logStartupSummary() {
        List<String> features = new ArrayList<>();
        if (securityEnabled) features.add("ai_security");
        if (auditLogger != null) features.add("audit_logging");
        if (tlsTerminator != null) features.add("tls_termination");
        if (zoneEnforcer != null) features.add("zone_enforcement");
        if (egressFilter != null) features.add("egress_filtering");
        if (deepPacketInspector != null) features.add("deep_packet_inspection");
        if (ftpCommandFilter != null) features.add("ftp_command_filter");
        if (healthChecker != null) features.add("backend_health_check");
        if (bandwidthQoS != null) features.add("bandwidth_qos");
        features.add("proxy_protocol");
        features.add("connection_draining");
        log.info("DMZ proxy started with {} enterprise security features: {}",
            features.size(), features);
    }
}
