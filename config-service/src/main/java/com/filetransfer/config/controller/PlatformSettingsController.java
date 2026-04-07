package com.filetransfer.config.controller;

import com.filetransfer.config.service.PlatformSettingsService;
import com.filetransfer.shared.entity.PlatformSetting;
import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform Settings CRUD API — database-backed configuration for all microservices.
 *
 * GET    /api/platform-settings                                   list all
 * GET    /api/platform-settings?env=PROD                          filter by environment
 * GET    /api/platform-settings?env=PROD&service=SFTP             filter by env + service
 * GET    /api/platform-settings?service=GATEWAY                   filter by service
 * GET    /api/platform-settings?category=Network                  filter by category
 * GET    /api/platform-settings/{id}                              get one
 * POST   /api/platform-settings                                   create
 * PUT    /api/platform-settings/{id}                              update full
 * PATCH  /api/platform-settings/{id}/value                        update value only
 * DELETE /api/platform-settings/{id}                              delete
 * GET    /api/platform-settings/environments                      list distinct environments
 * GET    /api/platform-settings/services                          list distinct service names
 * GET    /api/platform-settings/categories                        list distinct categories
 * POST   /api/platform-settings/clone?source=TEST&target=CERT     clone env settings
 */
@RestController
@RequestMapping("/api/platform-settings")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
@Tag(name = "Platform Settings", description = "Database-backed configuration for all microservices")
public class PlatformSettingsController {

    private final PlatformSettingsService service;

    @GetMapping
    @Operation(summary = "List platform settings with optional filters (env, service, category)")
    public List<PlatformSetting> list(
            @RequestParam(required = false) Environment env,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String category) {
        if (env != null && service != null) return this.service.listByEnvironmentAndService(env, service);
        if (env != null) return this.service.listByEnvironment(env);
        if (service != null) return this.service.listByService(service);
        if (category != null) return this.service.listByCategory(category);
        return this.service.listAll();
    }

    @GetMapping("/{id}")
    public PlatformSetting get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformSetting create(@RequestBody PlatformSetting setting) {
        return service.create(setting);
    }

    @PutMapping("/{id}")
    public PlatformSetting update(@PathVariable UUID id, @RequestBody PlatformSetting setting) {
        return service.update(id, setting);
    }

    @PatchMapping("/{id}/value")
    public PlatformSetting updateValue(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return service.updateValue(id, body.get("value"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // ── Metadata endpoints ──────────────────────────────────────────────────

    @GetMapping("/environments")
    public List<Environment> environments() {
        return service.getEnvironments();
    }

    @GetMapping("/services")
    public List<String> services() {
        return service.getServiceNames();
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.getCategories();
    }

    @PostMapping("/clone")
    @Operation(summary = "Clone all settings from one environment to another")
    public List<PlatformSetting> cloneEnvironment(
            @RequestParam Environment source, @RequestParam Environment target) {
        return service.cloneEnvironment(source, target);
    }
}
