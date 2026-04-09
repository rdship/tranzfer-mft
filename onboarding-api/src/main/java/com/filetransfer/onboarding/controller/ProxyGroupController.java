package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.service.ProxyGroupService;
import com.filetransfer.shared.entity.ProxyGroup;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for proxy group management.
 *
 * <pre>
 * GET    /api/proxy-groups                  — all groups + live instances  (VIEWER)
 * GET    /api/proxy-groups/{id}             — single group + instances     (VIEWER)
 * POST   /api/proxy-groups                  — create group                 (ADMIN)
 * PUT    /api/proxy-groups/{id}             — update group                 (ADMIN)
 * DELETE /api/proxy-groups/{id}             — soft-delete group            (ADMIN)
 * GET    /api/proxy-groups/discover         — raw Redis scan (all live)    (VIEWER)
 * GET    /api/proxy-groups/by-type/{type}   — groups of a specific type    (VIEWER)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/proxy-groups")
@RequiredArgsConstructor
public class ProxyGroupController {

    private final ProxyGroupService groupService;

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        return ResponseEntity.ok(groupService.getGroupsWithInstances());
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<Map<String, Object>> getOne(@PathVariable UUID id) {
        ProxyGroup g = groupService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proxy group not found: " + id));
        List<Map<String, Object>> instances = groupService.discoverInstancesForGroup(g.getName());
        // Return group + instances merged
        Map<String, Object> view = new java.util.LinkedHashMap<>();
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
        return ResponseEntity.ok(view);
    }

    @GetMapping("/discover")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<Map<String, List<Map<String, Object>>>> discover() {
        return ResponseEntity.ok(groupService.discoverLiveInstances());
    }

    @GetMapping("/by-type/{type}")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<ProxyGroup>> byType(@PathVariable String type) {
        return ResponseEntity.ok(groupService.listActive().stream()
                .filter(g -> type.equalsIgnoreCase(g.getType()))
                .toList());
    }

    // ── Write (ADMIN only) ────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<ProxyGroup> create(@RequestBody ProxyGroup body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (body.getType() == null || body.getType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required (INTERNAL, EXTERNAL, PARTNER, CLOUD, CUSTOM)");
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(groupService.create(body));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<ProxyGroup> update(@PathVariable UUID id, @RequestBody ProxyGroup body) {
        try {
            return ResponseEntity.ok(groupService.update(id, body));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        try {
            groupService.delete(id);
            return ResponseEntity.ok(Map.of("status", "DEACTIVATED", "id", id.toString()));
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
