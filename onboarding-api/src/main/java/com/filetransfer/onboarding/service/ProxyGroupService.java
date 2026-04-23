package com.filetransfer.onboarding.service;

import com.filetransfer.shared.entity.core.ProxyGroup;
import com.filetransfer.shared.repository.core.ProxyGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Proxy-group CRUD backed by {@code ProxyGroup} entity rows.
 *
 * <p><b>R134AH — live instance discovery retired.</b> Earlier versions
 * scanned Redis keys {@code platform:proxy-group:*} written by
 * {@code ProxyGroupRegistrar} (retired at R134AG). With no writer, the
 * scan returned nothing; R134AH deletes the dead Redis path entirely.
 * {@link #discoverLiveInstances()} now returns an empty map and
 * {@link #getGroupsWithInstances()} returns PG-defined groups with
 * {@code instanceCount=0}.
 *
 * <p>DMZ proxy is DB-free (no shared-platform / no PG) so it does not
 * heartbeat to {@code platform_pod_heartbeat}. A future sprint may add
 * HTTP-based proxy heartbeating to restore live-instance visibility;
 * until then, the admin UI shows group definitions without live members.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyGroupService {

    private final ProxyGroupRepository groupRepository;
    @SuppressWarnings("unused") // kept for future HTTP-based proxy heartbeat probe
    private final RestTemplate         restTemplate;

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

    // ── Live instance discovery (stub pending future HTTP-heartbeat) ───────────

    /**
     * Returns live proxy instances grouped by group name. Always empty after
     * R134AH — see class Javadoc.
     */
    public Map<String, List<Map<String, Object>>> discoverLiveInstances() {
        return Map.of();
    }

    public List<Map<String, Object>> discoverInstancesForGroup(String groupName) {
        return List.of();
    }

    /**
     * Full view: group definitions with empty live-instance lists.
     */
    public List<Map<String, Object>> getGroupsWithInstances() {
        List<ProxyGroup> groups = listActive();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProxyGroup g : groups) {
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
            view.put("liveInstances",            List.of());
            view.put("instanceCount",            0);
            view.put("orphaned",                 false);
            result.add(view);
        }
        return result;
    }
}
