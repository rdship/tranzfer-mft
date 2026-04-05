package com.filetransfer.config.controller;

import com.filetransfer.config.service.ServerConfigService;
import com.filetransfer.shared.entity.ServerConfig;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Server Configuration API — lets admins dynamically configure service instances.
 *
 * GET    /api/servers                     list all active servers
 * GET    /api/servers?type=SFTP           filter by service type
 * GET    /api/servers/{id}               get one
 * POST   /api/servers                    create new server config
 * PUT    /api/servers/{id}               update full config
 * PATCH  /api/servers/{id}/active        enable / disable
 * DELETE /api/servers/{id}               delete
 */
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ServerConfigController {

    private final ServerConfigService serverConfigService;

    @GetMapping
    public List<ServerConfig> list(@RequestParam(required = false) ServiceType type) {
        return serverConfigService.list(type);
    }

    @GetMapping("/{id}")
    public ServerConfig get(@PathVariable UUID id) {
        return serverConfigService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerConfig create(@Valid @RequestBody ServerConfig config) {
        return serverConfigService.create(config);
    }

    @PutMapping("/{id}")
    public ServerConfig update(@PathVariable UUID id, @Valid @RequestBody ServerConfig config) {
        return serverConfigService.update(id, config);
    }

    @PatchMapping("/{id}/active")
    public ServerConfig setActive(@PathVariable UUID id, @RequestParam boolean value) {
        return serverConfigService.setActive(id, value);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        serverConfigService.delete(id);
    }
}
