package com.filetransfer.config.controller;

import com.filetransfer.config.service.ServerConfigService;
import com.filetransfer.shared.entity.core.ServerConfig;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Legacy Server Configuration API — backed by the {@code server_configs}
 * table, which R130's acceptance found carrying 0 rows on a running stack.
 * The authoritative "server instances" surface is
 * {@code onboarding-api}'s {@code ServerInstanceController} at
 * {@code /api/servers}, which reads {@code server_instances} (13 active
 * rows on the tester's stack).
 *
 * <p>R130 renamed this legacy controller's request mapping from
 * {@code /api/servers} → {@code /api/legacy-server-configs} so the gateway
 * route to {@code /api/servers} resolves unambiguously to the live
 * onboarding-api controller instead of this dead legacy one. The Server
 * Instances admin page was rendering "No servers registered" because the
 * gateway was proxying to this controller's empty list.
 *
 * <p>Code kept in place rather than deleted so any script or CLI that
 * still references the legacy shape can be migrated with a precise grep.
 */
@RestController
@RequestMapping("/api/legacy-server-configs")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Tag(name = "Legacy Server Configuration", description = "Deprecated — use /api/servers on onboarding-api")
public class ServerConfigController {

    private final ServerConfigService serverConfigService;

    @GetMapping
    @Operation(summary = "List all server configs, optionally filtered by service type")
    public List<ServerConfig> list(@RequestParam(required = false) ServiceType type) {
        return serverConfigService.list(type);
    }

    @GetMapping("/{id}")
    public ServerConfig get(@PathVariable UUID id) {
        return serverConfigService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new server configuration")
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
