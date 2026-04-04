package com.filetransfer.dmz.controller;

import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * DMZ Proxy Management API
 *
 * GET    /api/proxy/mappings               — list all port mappings + live stats
 * POST   /api/proxy/mappings               — add a new port mapping (hot-add)
 * DELETE /api/proxy/mappings/{name}        — remove a port mapping (hot-remove)
 * GET    /api/proxy/health                 — overall health
 */
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyManagementController {

    private final ProxyManager proxyManager;

    @Value("${control-api.key}")
    private String controlApiKey;

    @GetMapping("/mappings")
    public List<Map<String, Object>> list(@RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        return proxyManager.status();
    }

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public PortMapping add(@RequestHeader("X-Internal-Key") String key,
                            @RequestBody PortMapping mapping) {
        validateKey(key);
        mapping.setActive(true);
        proxyManager.add(mapping);
        return mapping;
    }

    @DeleteMapping("/mappings/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader("X-Internal-Key") String key,
                       @PathVariable String name) {
        validateKey(key);
        proxyManager.remove(name);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "activeMappings", proxyManager.getMappings().size()
        );
    }

    private void validateKey(String key) {
        if (!controlApiKey.equals(key)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
