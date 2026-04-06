package com.filetransfer.dmz.proxy;

import com.filetransfer.dmz.security.*;
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
 * When security is enabled, creates shared security components
 * (ConnectionTracker, RateLimiter, AiVerdictClient, ThreatEventReporter, SecurityMetrics)
 * and injects them into each proxy server.
 */
@Slf4j
@Service
public class ProxyManager {

    private final DmzProperties properties;
    private final ConcurrentHashMap<String, TcpProxyServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PortMapping> mappings = new ConcurrentHashMap<>();

    // Shared security components
    @Getter private ConnectionTracker connectionTracker;
    @Getter private RateLimiter rateLimiter;
    @Getter private AiVerdictClient aiVerdictClient;
    @Getter private ThreatEventReporter eventReporter;
    @Getter private SecurityMetrics securityMetrics;
    @Getter private boolean securityEnabled;

    public ProxyManager(DmzProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        DmzProperties.Security secConfig = properties.getSecurity();
        this.securityEnabled = secConfig.isEnabled();

        if (securityEnabled) {
            log.info("Initializing AI-powered security layer");
            log.info("  AI Engine URL: {}", secConfig.getAiEngineUrl());
            log.info("  Verdict timeout: {}ms", secConfig.getVerdictTimeoutMs());
            log.info("  Default rate limit: {}/min, {} concurrent",
                secConfig.getDefaultRatePerMinute(), secConfig.getDefaultMaxConcurrent());

            this.connectionTracker = new ConnectionTracker();
            this.rateLimiter = new RateLimiter();
            this.securityMetrics = new SecurityMetrics();

            // Configure rate limiter defaults
            rateLimiter.setDefaultMaxPerMinute(secConfig.getDefaultRatePerMinute());
            rateLimiter.setDefaultMaxConcurrent(secConfig.getDefaultMaxConcurrent());
            rateLimiter.setDefaultMaxBytesPerMinute(secConfig.getDefaultMaxBytesPerMinute());
            rateLimiter.setGlobalMaxPerMinute(secConfig.getGlobalRatePerMinute());

            // AI verdict client (with internal API key for authenticated communication)
            this.aiVerdictClient = new AiVerdictClient(
                secConfig.getAiEngineUrl(), secConfig.getVerdictTimeoutMs(),
                secConfig.getInternalApiKey());

            // Event reporter
            this.eventReporter = new ThreatEventReporter(
                aiVerdictClient,
                secConfig.getEventQueueCapacity(),
                secConfig.getEventBatchSize(),
                secConfig.getEventFlushIntervalMs());

            log.info("Security layer initialized — all connections will be AI-analyzed");
        } else {
            log.info("Security layer DISABLED — running in pass-through mode");
        }

        if (properties.getMappings() != null) {
            properties.getMappings().forEach(m -> {
                m.setActive(true);
                add(m);
            });
        }
    }

    public void add(PortMapping mapping) {
        if (servers.containsKey(mapping.getName())) {
            throw new IllegalArgumentException("Mapping already exists: " + mapping.getName());
        }

        TcpProxyServer server;
        if (securityEnabled) {
            server = new TcpProxyServer(mapping,
                connectionTracker, rateLimiter,
                aiVerdictClient, eventReporter, securityMetrics);
        } else {
            server = new TcpProxyServer(mapping);
        }

        try {
            server.start();
            servers.put(mapping.getName(), server);
            mappings.put(mapping.getName(), mapping);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start proxy for " + mapping.getName(), e);
        }
    }

    public void remove(String name) {
        TcpProxyServer server = servers.remove(name);
        mappings.remove(name);
        if (server != null) server.stop();
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
            result.add(entry);
        });
        return result;
    }

    public Collection<PortMapping> getMappings() {
        return Collections.unmodifiableCollection(mappings.values());
    }

    @PreDestroy
    public void shutdown() {
        servers.values().forEach(TcpProxyServer::stop);
        if (eventReporter != null) eventReporter.shutdown();
        if (aiVerdictClient != null) aiVerdictClient.shutdown();
    }
}
