package com.filetransfer.config.controller;

import com.filetransfer.shared.client.DmzProxyClient;
import com.filetransfer.shared.entity.security.ListenerSecurityPolicy;
import com.filetransfer.shared.repository.ListenerSecurityPolicyRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Listener Security Policy API — manage network, rate-limit, file, and transfer-window
 * policies for server instances and external destinations.
 *
 * GET    /api/listener-security-policies                     list active policies
 * GET    /api/listener-security-policies/{id}                get one
 * GET    /api/listener-security-policies/server/{serverId}   get policy for server instance
 * GET    /api/listener-security-policies/destination/{destId} get policy for external destination
 * POST   /api/listener-security-policies                     create
 * PUT    /api/listener-security-policies/{id}                update
 * DELETE /api/listener-security-policies/{id}                soft-delete (deactivate)
 */
@Slf4j
@RestController
@RequestMapping("/api/listener-security-policies")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ListenerSecurityPolicyController {

    private final ListenerSecurityPolicyRepository policyRepo;
    private final DmzProxyClient dmzProxyClient;

    @GetMapping
    public List<ListenerSecurityPolicy> list() {
        return policyRepo.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public ListenerSecurityPolicy get(@PathVariable UUID id) {
        return policyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/server/{serverId}")
    public ResponseEntity<ListenerSecurityPolicy> getForServer(@PathVariable UUID serverId) {
        return policyRepo.findByServerInstanceIdAndActiveTrue(serverId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/destination/{destId}")
    public ResponseEntity<ListenerSecurityPolicy> getForDestination(@PathVariable UUID destId) {
        return policyRepo.findByExternalDestinationIdAndActiveTrue(destId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListenerSecurityPolicy create(@Valid @RequestBody ListenerSecurityPolicy policy) {
        validatePolicy(policy);
        policy.setId(null);
        policy.setActive(true);
        ListenerSecurityPolicy saved = policyRepo.save(policy);
        pushToProxyIfNeeded(saved);
        log.info("Created security policy '{}' tier={}", saved.getName(), saved.getSecurityTier());
        return saved;
    }

    @PutMapping("/{id}")
    public ListenerSecurityPolicy update(@PathVariable UUID id, @Valid @RequestBody ListenerSecurityPolicy updates) {
        ListenerSecurityPolicy existing = policyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Update fields
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getSecurityTier() != null) existing.setSecurityTier(updates.getSecurityTier());
        if (updates.getIpWhitelist() != null) existing.setIpWhitelist(updates.getIpWhitelist());
        if (updates.getIpBlacklist() != null) existing.setIpBlacklist(updates.getIpBlacklist());
        if (updates.getGeoAllowedCountries() != null) existing.setGeoAllowedCountries(updates.getGeoAllowedCountries());
        if (updates.getGeoBlockedCountries() != null) existing.setGeoBlockedCountries(updates.getGeoBlockedCountries());
        existing.setRateLimitPerMinute(updates.getRateLimitPerMinute());
        existing.setMaxConcurrent(updates.getMaxConcurrent());
        existing.setMaxBytesPerMinute(updates.getMaxBytesPerMinute());
        existing.setMaxAuthAttempts(updates.getMaxAuthAttempts());
        existing.setIdleTimeoutSeconds(updates.getIdleTimeoutSeconds());
        existing.setRequireEncryption(updates.isRequireEncryption());
        existing.setConnectionLogging(updates.isConnectionLogging());
        if (updates.getAllowedFileExtensions() != null) existing.setAllowedFileExtensions(updates.getAllowedFileExtensions());
        if (updates.getBlockedFileExtensions() != null) existing.setBlockedFileExtensions(updates.getBlockedFileExtensions());
        existing.setMaxFileSizeBytes(updates.getMaxFileSizeBytes());
        if (updates.getTransferWindows() != null) existing.setTransferWindows(updates.getTransferWindows());

        ListenerSecurityPolicy saved = policyRepo.save(existing);
        pushToProxyIfNeeded(saved);
        log.info("Updated security policy '{}' tier={}", saved.getName(), saved.getSecurityTier());
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        ListenerSecurityPolicy existing = policyRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setActive(false);
        policyRepo.save(existing);
        log.info("Deactivated security policy '{}'", existing.getName());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void validatePolicy(ListenerSecurityPolicy policy) {
        if (policy.getName() == null || policy.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy name is required");
        }
        // Ensure exactly one FK is set
        boolean hasServer = policy.getServerInstance() != null;
        boolean hasDest = policy.getExternalDestination() != null;
        if (hasServer == hasDest) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Policy must be linked to exactly one server instance or external destination");
        }
    }

    /** Push security policy to DMZ proxy if the linked server uses proxy routing. */
    private void pushToProxyIfNeeded(ListenerSecurityPolicy policy) {
        try {
            if (policy.getServerInstance() != null && policy.getServerInstance().isUseProxy()) {
                Map<String, Object> policyMap = buildProxyPolicyMap(policy);
                String mappingName = policy.getServerInstance().getInstanceId();
                dmzProxyClient.updateMappingSecurityPolicy(mappingName, policyMap);
                log.info("Pushed security policy to proxy for mapping '{}'", mappingName);
            }
        } catch (Exception e) {
            log.warn("Failed to push security policy to proxy: {}", e.getMessage());
            // Don't fail the API call — proxy push is best-effort
        }
    }

    private Map<String, Object> buildProxyPolicyMap(ListenerSecurityPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("securityTier", policy.getSecurityTier().name());
        map.put("ipWhitelist", policy.getIpWhitelist() != null ? policy.getIpWhitelist() : List.of());
        map.put("ipBlacklist", policy.getIpBlacklist() != null ? policy.getIpBlacklist() : List.of());
        map.put("geoAllowedCountries", policy.getGeoAllowedCountries() != null ? policy.getGeoAllowedCountries() : List.of());
        map.put("geoBlockedCountries", policy.getGeoBlockedCountries() != null ? policy.getGeoBlockedCountries() : List.of());
        map.put("rateLimitPerMinute", policy.getRateLimitPerMinute());
        map.put("maxConcurrent", policy.getMaxConcurrent());
        map.put("maxBytesPerMinute", policy.getMaxBytesPerMinute());
        map.put("maxAuthAttempts", policy.getMaxAuthAttempts());
        map.put("idleTimeoutSeconds", policy.getIdleTimeoutSeconds());
        map.put("requireEncryption", policy.isRequireEncryption());
        map.put("connectionLogging", policy.isConnectionLogging());
        map.put("allowedFileExtensions", policy.getAllowedFileExtensions() != null ? policy.getAllowedFileExtensions() : List.of());
        map.put("blockedFileExtensions", policy.getBlockedFileExtensions() != null ? policy.getBlockedFileExtensions() : List.of());
        map.put("maxFileSizeBytes", policy.getMaxFileSizeBytes());
        map.put("transferWindows", policy.getTransferWindows() != null ? policy.getTransferWindows() : "[]");
        return map;
    }
}
