package com.filetransfer.dmz.proxy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of all TcpProxyServer instances.
 * Supports adding/removing/listing mappings at runtime without restart.
 */
@Slf4j
@Service
public class ProxyManager {

    private final DmzProperties properties;
    private final ConcurrentHashMap<String, TcpProxyServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PortMapping> mappings = new ConcurrentHashMap<>();

    public ProxyManager(DmzProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
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
        TcpProxyServer server = new TcpProxyServer(mapping);
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
            result.add(Map.of(
                    "name", name,
                    "listenPort", mapping.getListenPort(),
                    "targetHost", mapping.getTargetHost(),
                    "targetPort", mapping.getTargetPort(),
                    "active", mapping.isActive(),
                    "bytesForwarded", server != null ? server.getBytesForwarded().get() : 0,
                    "activeConnections", server != null ? server.getActiveConnections().get() : 0
            ));
        });
        return result;
    }

    public Collection<PortMapping> getMappings() {
        return Collections.unmodifiableCollection(mappings.values());
    }

    @PreDestroy
    public void shutdown() {
        servers.values().forEach(TcpProxyServer::stop);
    }
}
