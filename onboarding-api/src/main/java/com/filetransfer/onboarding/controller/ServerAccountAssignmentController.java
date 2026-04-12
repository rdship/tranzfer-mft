package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.ServerAccountAssignment;
import com.filetransfer.shared.entity.ServerInstance;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.ServerAccountAssignmentRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * 360-degree account ↔ server assignment management.
 *
 * <pre>
 * ── Manage accounts on a server ─────────────────────────────────────────────
 * GET    /api/servers/{id}/accounts                  list all assignments
 * POST   /api/servers/{id}/accounts/{accountId}      assign account to server
 * PUT    /api/servers/{id}/accounts/{accountId}      update assignment (folder/perms/QoS)
 * DELETE /api/servers/{id}/accounts/{accountId}      revoke access
 * POST   /api/servers/{id}/accounts/bulk             assign multiple accounts at once
 * GET    /api/servers/{id}/access-check/{username}   check if user can connect
 *
 * ── Manage servers for an account ───────────────────────────────────────────
 * GET    /api/accounts/{id}/servers                  list servers the account is on
 * POST   /api/accounts/{id}/servers/{serverId}       add server access
 * DELETE /api/accounts/{id}/servers/{serverId}       remove server access
 *
 * ── Server operational controls ─────────────────────────────────────────────
 * POST   /api/servers/{id}/maintenance               toggle maintenance mode
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ServerAccountAssignmentController {

    private final ServerAccountAssignmentRepository assignmentRepo;
    private final ServerInstanceRepository          serverRepo;
    private final TransferAccountRepository         accountRepo;

    // ════════════════════════════════════════════════════════════════════════════
    // Assignments by server
    // ════════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    @GetMapping("/api/servers/{serverId}/accounts")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<List<Map<String, Object>>> listAccountsOnServer(
            @PathVariable UUID serverId) {
        requireServer(serverId);
        List<Map<String, Object>> result = assignmentRepo.findByServerInstanceId(serverId)
                .stream().map(this::toView).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/servers/{serverId}/accounts/{accountId}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> assignAccount(
            @PathVariable UUID serverId,
            @PathVariable UUID accountId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserDetails user) {

        ServerInstance  server  = requireServer(serverId);
        TransferAccount account = requireAccount(accountId);

        if (assignmentRepo.existsByServerInstanceIdAndTransferAccountId(serverId, accountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Account '" + account.getUsername() + "' is already assigned to server '" + server.getName() + "'");
        }

        ServerAccountAssignment assignment = buildAssignment(server, account, body, user);
        assignmentRepo.save(assignment);

        log.info("[ServerAssign] {} → {} assigned by {}", account.getUsername(), server.getInstanceId(),
                user != null ? user.getUsername() : "api");
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(assignment));
    }

    @PutMapping("/api/servers/{serverId}/accounts/{accountId}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> updateAssignment(
            @PathVariable UUID serverId,
            @PathVariable UUID accountId,
            @RequestBody Map<String, Object> body) {

        requireServer(serverId);
        requireAccount(accountId);

        ServerAccountAssignment a = assignmentRepo
                .findByServerAndAccount(serverId, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Assignment not found for server=" + serverId + " account=" + accountId));

        applyPatch(a, body);
        return ResponseEntity.ok(toView(assignmentRepo.save(a)));
    }

    @DeleteMapping("/api/servers/{serverId}/accounts/{accountId}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> revokeAccess(
            @PathVariable UUID serverId,
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UserDetails user) {

        ServerInstance  server  = requireServer(serverId);
        TransferAccount account = requireAccount(accountId);

        ServerAccountAssignment a = assignmentRepo
                .findByServerAndAccount(serverId, accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));

        assignmentRepo.delete(a);
        log.info("[ServerAssign] {} removed from {} by {}", account.getUsername(), server.getInstanceId(),
                user != null ? user.getUsername() : "api");
        return ResponseEntity.ok(Map.of("status", "REVOKED",
                "username", account.getUsername(), "server", server.getInstanceId()));
    }

    /** Bulk-assign multiple accounts to a server in one request. */
    @PostMapping("/api/servers/{serverId}/accounts/bulk")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> bulkAssign(
            @PathVariable UUID serverId,
            @RequestBody Map<String, List<UUID>> body,
            @AuthenticationPrincipal UserDetails user) {

        requireServer(serverId);
        List<UUID> accountIds = body.getOrDefault("accountIds", List.of());
        if (accountIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountIds list is required");
        }

        List<String> assigned = new ArrayList<>();
        List<String> skipped  = new ArrayList<>();
        for (UUID accountId : accountIds) {
            try {
                TransferAccount account = requireAccount(accountId);
                if (!assignmentRepo.existsByServerInstanceIdAndTransferAccountId(serverId, accountId)) {
                    ServerInstance server = serverRepo.findById(serverId).orElseThrow();
                    assignmentRepo.save(buildAssignment(server, account, null, user));
                    assigned.add(account.getUsername());
                } else {
                    skipped.add(accountId.toString());
                }
            } catch (Exception e) {
                skipped.add(accountId + " (" + e.getMessage() + ")");
            }
        }
        return ResponseEntity.ok(Map.of("assigned", assigned, "skipped", skipped,
                "message", assigned.size() + " account(s) assigned, " + skipped.size() + " skipped"));
    }

    /** Quick access check — can username connect to instanceId? */
    @GetMapping("/api/servers/{serverId}/access-check/{username}")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<Map<String, Object>> accessCheck(
            @PathVariable UUID serverId,
            @PathVariable String username) {

        ServerInstance server = requireServer(serverId);
        boolean authorized = assignmentRepo.isAccountAuthorized(server.getInstanceId(), username);
        return ResponseEntity.ok(Map.of(
                "username",    username,
                "instanceId",  server.getInstanceId(),
                "authorized",  authorized,
                "checkedAt",   Instant.now().toString()));
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Assignments by account
    // ════════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    @GetMapping("/api/accounts/{accountId}/servers")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<Map<String, Object>>> listServersForAccount(
            @PathVariable UUID accountId) {
        requireAccount(accountId);
        return ResponseEntity.ok(assignmentRepo.findByTransferAccountId(accountId)
                .stream().map(this::toServerView).toList());
    }

    @PostMapping("/api/accounts/{accountId}/servers/{serverId}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> addServerToAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID serverId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserDetails user) {
        // Reuse the symmetric endpoint
        return assignAccount(serverId, accountId, body, user);
    }

    @DeleteMapping("/api/accounts/{accountId}/servers/{serverId}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> removeServerFromAccount(
            @PathVariable UUID accountId,
            @PathVariable UUID serverId,
            @AuthenticationPrincipal UserDetails user) {
        return revokeAccess(serverId, accountId, user);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Server operational controls
    // ════════════════════════════════════════════════════════════════════════════

    @PostMapping("/api/servers/{serverId}/maintenance")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> toggleMaintenance(
            @PathVariable UUID serverId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserDetails user) {

        ServerInstance server = requireServer(serverId);
        boolean enable = body != null && Boolean.TRUE.equals(body.get("enable"));
        String message = body != null ? (String) body.get("message") : null;

        server.setMaintenanceMode(enable);
        if (message != null) server.setMaintenanceMessage(message);
        serverRepo.save(server);

        log.info("[Maintenance] {} maintenance={} by {}", server.getInstanceId(), enable,
                user != null ? user.getUsername() : "api");
        return ResponseEntity.ok(Map.of(
                "instanceId",       server.getInstanceId(),
                "maintenanceMode",  enable,
                "message",          message != null ? message : "",
                "changedBy",        user != null ? user.getUsername() : "api"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ServerInstance requireServer(UUID id) {
        return serverRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found: " + id));
    }

    private TransferAccount requireAccount(UUID id) {
        return accountRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + id));
    }

    private ServerAccountAssignment buildAssignment(
            ServerInstance server, TransferAccount account,
            Map<String, Object> body, UserDetails user) {

        ServerAccountAssignment.ServerAccountAssignmentBuilder b = ServerAccountAssignment.builder()
                .serverInstance(server)
                .transferAccount(account)
                .createdBy(user != null ? user.getUsername() : "api");

        if (body != null) applyFromMap(b, body);
        return b.build();
    }

    private void applyPatch(ServerAccountAssignment a, Map<String, Object> body) {
        if (body.containsKey("homeFolderOverride")) a.setHomeFolderOverride((String) body.get("homeFolderOverride"));
        if (body.containsKey("canRead"))            a.setCanRead(toBool(body.get("canRead")));
        if (body.containsKey("canWrite"))           a.setCanWrite(toBool(body.get("canWrite")));
        if (body.containsKey("canDelete"))          a.setCanDelete(toBool(body.get("canDelete")));
        if (body.containsKey("canRename"))          a.setCanRename(toBool(body.get("canRename")));
        if (body.containsKey("canMkdir"))           a.setCanMkdir(toBool(body.get("canMkdir")));
        if (body.containsKey("maxConcurrentSessions")) a.setMaxConcurrentSessions(toInt(body.get("maxConcurrentSessions")));
        if (body.containsKey("maxUploadBytesPerSecond")) a.setMaxUploadBytesPerSecond(toLong(body.get("maxUploadBytesPerSecond")));
        if (body.containsKey("maxDownloadBytesPerSecond")) a.setMaxDownloadBytesPerSecond(toLong(body.get("maxDownloadBytesPerSecond")));
        if (body.containsKey("enabled")) a.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("notes"))   a.setNotes((String) body.get("notes"));
    }

    private void applyFromMap(ServerAccountAssignment.ServerAccountAssignmentBuilder b, Map<String, Object> body) {
        if (body.containsKey("homeFolderOverride")) b.homeFolderOverride((String) body.get("homeFolderOverride"));
        if (body.containsKey("canRead"))   b.canRead(toBool(body.get("canRead")));
        if (body.containsKey("canWrite"))  b.canWrite(toBool(body.get("canWrite")));
        if (body.containsKey("canDelete")) b.canDelete(toBool(body.get("canDelete")));
        if (body.containsKey("canRename")) b.canRename(toBool(body.get("canRename")));
        if (body.containsKey("canMkdir"))  b.canMkdir(toBool(body.get("canMkdir")));
        if (body.containsKey("maxConcurrentSessions")) b.maxConcurrentSessions(toInt(body.get("maxConcurrentSessions")));
        if (body.containsKey("maxUploadBytesPerSecond")) b.maxUploadBytesPerSecond(toLong(body.get("maxUploadBytesPerSecond")));
        if (body.containsKey("maxDownloadBytesPerSecond")) b.maxDownloadBytesPerSecond(toLong(body.get("maxDownloadBytesPerSecond")));
        if (body.containsKey("enabled")) b.enabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("notes"))   b.notes((String) body.get("notes"));
    }

    private Map<String, Object> toView(ServerAccountAssignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          a.getId());
        m.put("accountId",   a.getTransferAccount().getId());
        m.put("username",    a.getTransferAccount().getUsername());
        m.put("protocol",    a.getTransferAccount().getProtocol());
        m.put("homeFolderOverride", a.getHomeFolderOverride());
        m.put("effectiveHomeFolder", a.effectiveHomeFolder());
        m.put("canRead",     a.getCanRead());
        m.put("canWrite",    a.getCanWrite());
        m.put("canDelete",   a.getCanDelete());
        m.put("canRename",   a.getCanRename());
        m.put("canMkdir",    a.getCanMkdir());
        m.put("maxConcurrentSessions",   a.getMaxConcurrentSessions());
        m.put("maxUploadBytesPerSecond", a.getMaxUploadBytesPerSecond());
        m.put("maxDownloadBytesPerSecond", a.getMaxDownloadBytesPerSecond());
        m.put("enabled",     a.isEnabled());
        m.put("notes",       a.getNotes());
        m.put("createdAt",   a.getCreatedAt());
        m.put("createdBy",   a.getCreatedBy());
        return m;
    }

    private Map<String, Object> toServerView(ServerAccountAssignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        ServerInstance si = a.getServerInstance();
        m.put("assignmentId",  a.getId());
        m.put("serverId",      si.getId());
        m.put("instanceId",    si.getInstanceId());
        m.put("serverName",    si.getName());
        m.put("protocol",      si.getProtocol());
        m.put("clientHost",    si.getClientConnectionHost());
        m.put("clientPort",    si.getClientConnectionPort());
        m.put("proxyGroupName",si.getProxyGroupName());
        m.put("maintenanceMode", si.isMaintenanceMode());
        m.put("active",        si.isActive());
        m.put("homeFolderOverride", a.getHomeFolderOverride());
        m.put("canRead",    a.getCanRead());
        m.put("canWrite",   a.getCanWrite());
        m.put("canDelete",  a.getCanDelete());
        m.put("enabled",    a.isEnabled());
        return m;
    }

    private static Boolean toBool(Object o) { return o instanceof Boolean b ? b : o != null ? Boolean.parseBoolean(o.toString()) : null; }
    private static Integer toInt(Object o)  { return o instanceof Number n ? n.intValue() : null; }
    private static Long    toLong(Object o)  { return o instanceof Number n ? n.longValue() : null; }
}
