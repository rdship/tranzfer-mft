package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateServerInstanceRequest;
import com.filetransfer.onboarding.dto.request.UpdateServerInstanceRequest;
import com.filetransfer.onboarding.dto.response.ServerInstanceResponse;
import com.filetransfer.onboarding.service.ServerInstanceService;
import com.filetransfer.shared.security.Roles;
import com.filetransfer.shared.enums.Protocol;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server instance (protocol listener) lifecycle API.
 *
 * <p>Create/update/delete publishes events via the outbox pattern so protocol
 * services (sftp/ftp/as2) bind/unbind listeners at runtime without a restart.</p>
 */
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
@Tag(name = "Server Instances", description = "Protocol listener lifecycle — dynamic bind/unbind across SFTP/FTP/AS2")
public class ServerInstanceController {

    private final ServerInstanceService service;

    @GetMapping
    @Operation(summary = "List server instances (all protocols or filtered)")
    public List<ServerInstanceResponse> listAll(
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(required = false) Protocol protocol) {
        if (protocol != null) {
            return service.listByProtocol(protocol, activeOnly);
        }
        return activeOnly ? service.listActive() : service.listAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a server instance by UUID")
    public ServerInstanceResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/by-instance/{instanceId}")
    @Operation(summary = "Get a server instance by its instanceId string")
    public ServerInstanceResponse getByInstanceId(@PathVariable String instanceId) {
        return service.getByInstanceId(instanceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new listener. Returns 409 if host:port is already assigned.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Listener created; bind event queued"),
            @ApiResponse(responseCode = "409", description = "Port already assigned on the target host"),
            @ApiResponse(responseCode = "400", description = "Validation error on request body")
    })
    public ServerInstanceResponse create(@Valid @RequestBody CreateServerInstanceRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update listener configuration. Port/key changes trigger rebind.")
    public ServerInstanceResponse update(@PathVariable UUID id,
                                          @RequestBody UpdateServerInstanceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Soft-delete a listener. Protocol service unbinds the port.")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/port-suggestions")
    @Operation(summary = "List free ports near the requested one, for a given host",
            description = "Helper for the UI port picker. Returns up to N free ports in the range " +
                    "[requested-5, requested+20], preferring ports after the requested value.")
    public Map<String, Object> portSuggestions(@RequestParam String host,
                                                @RequestParam int port,
                                                @RequestParam(defaultValue = "5") int count) {
        return Map.of(
                "host", host,
                "requestedPort", port,
                "suggestedPorts", service.suggestAlternativePorts(host, port, count)
        );
    }

    @PostMapping("/{id}/rebind")
    @Operation(summary = "Retry binding a listener that is BIND_FAILED or drifted",
            description = "Publishes a synthetic UPDATED event; the owning protocol service unbinds then binds again. " +
                    "Idempotent — safe to call on a BOUND listener.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Rebind event queued"),
            @ApiResponse(responseCode = "404", description = "Listener not found")
    })
    public ResponseEntity<ServerInstanceResponse> rebind(@PathVariable UUID id) {
        ServerInstanceResponse body = service.requestRebind(id);
        return ResponseEntity.accepted().body(body);
    }

    // ── Error mapping ────────────────────────────────────────────────────────
    /**
     * Port collision pre-detected by the service — includes suggestions the UI
     * can show directly ("port N is in use; try: [N+1, N+2, N+3]").
     */
    @ExceptionHandler(com.filetransfer.onboarding.exception.PortConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handlePortConflict(com.filetransfer.onboarding.exception.PortConflictException ex) {
        return Map.of(
                "error", "Port " + ex.getRequestedPort() + " already in use on host " + ex.getHost(),
                "host", ex.getHost(),
                "requestedPort", ex.getRequestedPort(),
                "suggestedPorts", ex.getSuggestedPorts()
        );
    }

    /**
     * Fallback: if the service-level pre-check missed it (race between two
     * simultaneous creates), the DB unique index catches it. Still return 409,
     * but without port suggestions since we don't have the request context here.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDbConstraint(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        String friendly = (msg != null && msg.contains("uk_server_instance_host_port_active"))
                ? "Port already assigned on this host"
                : "Conflict: " + msg;
        return Map.of("error", friendly);
    }
}
