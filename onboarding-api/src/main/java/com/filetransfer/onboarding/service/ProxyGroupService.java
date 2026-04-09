package com.filetransfer.onboarding.service;

import com.filetransfer.shared.entity.ProxyGroup;
import com.filetransfer.shared.repository.ProxyGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Combines PostgreSQL group definitions with Redis-based live instance discovery.
 *
 * <p>Live proxy instance membership is discovered by scanning Redis keys
 * of the form {@code platform:proxy-group:{groupName}:{instanceId}}.
 * Instances self-register at startup via {@link com.filetransfer.dmzproxy.cluster.ProxyGroupRegistrar}
 * and refresh their TTL every 10 s. Stale/crashed instances disappear within 30 s.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyGroupService {

    private static final String REDIS_KEY_PREFIX = "platform:proxy-group:";

    private final ProxyGroupRepository groupRepository;
    private final RestTemplate         restTemplate;

    @Autowired(required = false)
    @Nullable
    private StringRedisTemplate redis;

    // ── Group CRUD ─────────────────────────────────────────────────────────────

    public List<ProxyGroup> listActive() {
        return groupRepository.findByActiveTrueOrderByRoutingPriorityAscNameAsc();
    }

    public Optional<ProxyGroup> findById(UUID id) {
        return groupRepository.findById(id);
    }

    public ProxyGroup create(ProxyGroup g) {
        if (groupRepository.existsByName(g.getName())) {
            throw new IllegalArgumentException("Proxy group '" + g.getName() + "' already exists");
        }
        g.setId(null);
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groupRepository.save(g);
    }

    public ProxyGroup update(UUID id, ProxyGroup patch) {
        ProxyGroup existing = groupRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Proxy group not found: " + id));
        if (patch.getDescription()  != null) existing.setDescription(patch.getDescription());
        if (patch.getType()         != null) existing.setType(patch.getType());
        if (patch.getAllowedProtocols() != null) existing.setAllowedProtocols(patch.getAllowedProtocols());
        if (patch.getTrustedCidrs() != null) existing.setTrustedCidrs(patch.getTrustedCidrs());
        if (patch.getNotes()        != null) existing.setNotes(patch.getNotes());
        existing.setTlsRequired(patch.isTlsRequired());
        existing.setActive(patch.isActive());
        if (patch.getMaxConnectionsPerInstance() > 0)
            existing.setMaxConnectionsPerInstance(patch.getMaxConnectionsPerInstance());
        if (patch.getRoutingPriority() > 0)
            existing.setRoutingPriority(patch.getRoutingPriority());
        return groupRepository.save(existing);
    }

    public void delete(UUID id) {
        ProxyGroup g = groupRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Proxy group not found: " + id));
        // Soft-delete: mark inactive
        g.setActive(false);
        groupRepository.save(g);
    }

    // ── Live instance discovery ────────────────────────────────────────────────

    /**
     * Returns all live proxy instances registered in Redis, grouped by group name.
     * Each value is a list of instance metadata maps parsed from the Redis JSON payload.
     */
    public Map<String, List<Map<String, Object>>> discoverLiveInstances() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        if (redis == null) return result;

        Set<String> keys = redis.keys(REDIS_KEY_PREFIX + "*");
        if (keys == null) return result;

        for (String key : keys) {
            String payload = redis.opsForValue().get(key);
            if (payload == null) continue;

            // key format: platform:proxy-group:{groupName}:{instanceId}
            String[] parts = key.substring(REDIS_KEY_PREFIX.length()).split(":", 2);
            String groupName = parts.length >= 1 ? parts[0] : "unknown";

            Map<String, Object> instance = parseInstance(payload);
            instance.put("lastSeen", Instant.now().toString());
            // Optionally fetch detailed health from the proxy's /api/proxy/info
            enrichWithHealth(instance);

            result.computeIfAbsent(groupName, k -> new ArrayList<>()).add(instance);
        }
        return result;
    }

    /**
     * Returns live instances for a specific group.
     */
    public List<Map<String, Object>> discoverInstancesForGroup(String groupName) {
        Map<String, List<Map<String, Object>>> all = discoverLiveInstances();
        return all.getOrDefault(groupName, List.of());
    }

    /**
     * Full view: group definitions + their live instances merged into one response.
     */
    public List<Map<String, Object>> getGroupsWithInstances() {
        List<ProxyGroup> groups = listActive();
        Map<String, List<Map<String, Object>>> liveMap = discoverLiveInstances();

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> processedGroups = new LinkedHashSet<>();

        // Known groups first (preserves routing priority order)
        for (ProxyGroup g : groups) {
            Map<String, Object> view = buildGroupView(g, liveMap.getOrDefault(g.getName(), List.of()));
            result.add(view);
            processedGroups.add(g.getName());
        }

        // Orphaned live instances (group name not in DB — proxy misconfigured or new group)
        for (Map.Entry<String, List<Map<String, Object>>> entry : liveMap.entrySet()) {
            if (!processedGroups.contains(entry.getKey())) {
                Map<String, Object> orphan = new LinkedHashMap<>();
                orphan.put("id", null);
                orphan.put("name", entry.getKey());
                orphan.put("type", "UNKNOWN");
                orphan.put("description", "Group not defined in database — proxy may be misconfigured");
                orphan.put("active", false);
                orphan.put("liveInstances", entry.getValue());
                orphan.put("instanceCount", entry.getValue().size());
                orphan.put("orphaned", true);
                result.add(orphan);
            }
        }
        return result;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildGroupView(ProxyGroup g, List<Map<String, Object>> instances) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id",                       g.getId());
        view.put("name",                     g.getName());
        view.put("type",                     g.getType());
        view.put("description",              g.getDescription());
        view.put("allowedProtocols",         g.getAllowedProtocols());
        view.put("tlsRequired",              g.isTlsRequired());
        view.put("trustedCidrs",             g.getTrustedCidrs());
        view.put("maxConnectionsPerInstance",g.getMaxConnectionsPerInstance());
        view.put("routingPriority",          g.getRoutingPriority());
        view.put("active",                   g.isActive());
        view.put("notes",                    g.getNotes());
        view.put("liveInstances",            instances);
        view.put("instanceCount",            instances.size());
        view.put("orphaned",                 false);
        return view;
    }

    private void enrichWithHealth(Map<String, Object> instance) {
        String url = (String) instance.get("url");
        if (url == null || url.isBlank()) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> info = restTemplate.getForObject(url + "/api/proxy/info", Map.class);
            if (info != null) {
                instance.put("healthy",   info.getOrDefault("healthy", true));
                instance.put("uptime",    info.get("uptime"));
                instance.put("activeConnections", info.get("activeConnections"));
            }
        } catch (Exception e) {
            instance.put("healthy", false);
            instance.put("healthError", e.getMessage());
        }
    }

    private static Map<String, Object> parseInstance(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String field : new String[]{"instanceId", "groupName", "groupType", "host", "url"}) {
            m.put(field, extractStr(json, field));
        }
        m.put("port", extractInt(json, "port"));
        return m;
    }

    private static String extractStr(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int s = json.indexOf(marker);
        if (s < 0) return "";
        s += marker.length();
        int e = json.indexOf("\"", s);
        return e < 0 ? "" : json.substring(s, e);
    }

    private static int extractInt(String json, String key) {
        String marker = "\"" + key + "\":";
        int s = json.indexOf(marker);
        if (s < 0) return 0;
        s += marker.length();
        int e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        try { return Integer.parseInt(json.substring(s, e)); } catch (NumberFormatException ex) { return 0; }
    }
}
