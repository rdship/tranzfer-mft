package com.filetransfer.license.controller;

import com.filetransfer.license.catalog.ComponentCatalog;
import com.filetransfer.license.catalog.ProductTier;
import com.filetransfer.license.dto.*;
import com.filetransfer.license.entity.LicenseActivation;
import com.filetransfer.license.entity.LicenseRecord;
import com.filetransfer.license.service.LicenseService;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/licenses")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize(Roles.USER)
public class LicenseController {

    private final LicenseService licenseService;

    @Value("${license.admin-key:license_admin_secret_key}")
    private String adminKey;

    @PostMapping("/validate")
    public ResponseEntity<LicenseValidationResponse> validate(@RequestBody LicenseValidationRequest request) {
        return ResponseEntity.ok(licenseService.validateLicense(request));
    }

    @PostMapping("/trial")
    public ResponseEntity<LicenseValidationResponse> activateTrial(@RequestBody TrialActivationRequest request) {
        LicenseValidationRequest valReq = new LicenseValidationRequest(
                null, request.getServiceType(), request.getHostId(),
                request.getFingerprint(), request.getCustomerId(), request.getCustomerName());
        return ResponseEntity.ok(licenseService.validateLicense(valReq));
    }

    @PostMapping("/issue")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, String>> issue(
            @RequestHeader("X-Admin-Key") String key,
            @Valid @RequestBody LicenseIssueRequest request) {
        if (!adminKey.equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid admin key"));
        }
        String licenseKey = licenseService.issueLicense(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("licenseKey", licenseKey));
    }

    @GetMapping
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<List<LicenseRecord>> getAllLicenses(
            @RequestHeader("X-Admin-Key") String key) {
        if (!adminKey.equals(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(licenseService.getAllLicenses());
    }

    @DeleteMapping("/{licenseId}/revoke")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Void> revoke(
            @RequestHeader("X-Admin-Key") String key,
            @PathVariable String licenseId) {
        if (!adminKey.equals(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        licenseService.revokeLicense(licenseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{licenseId}/activations")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<List<LicenseActivation>> getActivations(
            @RequestHeader("X-Admin-Key") String key,
            @PathVariable String licenseId) {
        if (!adminKey.equals(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(licenseService.getActivations(licenseId));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "license-service"));
    }

    // ── Product Catalog Endpoints (public — used by CLI installer) ──────

    /** List all licensable components grouped by category */
    @GetMapping("/catalog/components")
    public ResponseEntity<Map<String, Object>> getCatalog() {
        Map<String, Object> result = new LinkedHashMap<>();

        for (ComponentCatalog.Category cat : ComponentCatalog.Category.values()) {
            List<ComponentCatalog.Component> components = ComponentCatalog.getByCategory(cat);
            if (components.isEmpty()) continue;

            List<Map<String, Object>> items = components.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("description", c.getDescription());
                m.put("coreRequired", c.isCoreRequired());
                m.put("minimumTier", c.getDefaultTier());
                m.put("helmKey", c.getHelmKey());
                return m;
            }).collect(Collectors.toList());

            Map<String, Object> categoryMap = new LinkedHashMap<>();
            categoryMap.put("displayName", cat.getDisplayName());
            categoryMap.put("description", cat.getDescription());
            categoryMap.put("components", items);
            result.put(cat.name(), categoryMap);
        }
        return ResponseEntity.ok(result);
    }

    /** List available product tiers with included components */
    @GetMapping("/catalog/tiers")
    public ResponseEntity<List<Map<String, Object>>> getTiers() {
        List<Map<String, Object>> tiers = ProductTier.getAll().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("maxInstances", t.getMaxInstances());
            m.put("maxConcurrentConnections", t.getMaxConcurrentConnections());
            m.put("componentCount", t.getComponentIds().size());
            m.put("componentIds", t.getComponentIds());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(tiers);
    }

    /** Get components for a specific license key (what the customer is entitled to) */
    @PostMapping("/catalog/entitled")
    public ResponseEntity<Map<String, Object>> getEntitledComponents(@RequestBody LicenseValidationRequest request) {
        LicenseValidationResponse response = licenseService.validateLicense(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", response.isValid());
        result.put("edition", response.getEdition());
        result.put("mode", response.getMode());

        if (response.isValid()) {
            List<String> features = response.getFeatures() != null ? response.getFeatures() : List.of();
            // Map features to component IDs — features list doubles as component entitlements
            List<Map<String, Object>> entitled = new ArrayList<>();
            // Core always included
            for (ComponentCatalog.Component c : ComponentCatalog.getCoreComponents()) {
                entitled.add(Map.of("id", c.getId(), "name", c.getName(), "category", c.getCategory().name()));
            }
            // Licensed components from features list
            for (String feature : features) {
                ComponentCatalog.findById(feature).ifPresent(c ->
                    entitled.add(Map.of("id", c.getId(), "name", c.getName(), "category", c.getCategory().name()))
                );
            }
            // Also include tier-default components if edition matches
            if (response.getEdition() != null && !"TRIAL".equals(response.getEdition())) {
                for (ComponentCatalog.Component c : ComponentCatalog.getComponentsForTier(response.getEdition())) {
                    boolean already = entitled.stream().anyMatch(e -> e.get("id").equals(c.getId()));
                    if (!already) {
                        entitled.add(Map.of("id", c.getId(), "name", c.getName(), "category", c.getCategory().name()));
                    }
                }
            }
            result.put("entitledComponents", entitled);
            result.put("maxInstances", response.getMaxInstances());
            result.put("maxConcurrentConnections", response.getMaxConcurrentConnections());
            result.put("expiresAt", response.getExpiresAt());
        } else {
            result.put("message", response.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
