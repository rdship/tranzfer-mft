package com.filetransfer.license.controller;

import com.filetransfer.license.dto.*;
import com.filetransfer.license.entity.LicenseActivation;
import com.filetransfer.license.entity.LicenseRecord;
import com.filetransfer.license.service.LicenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/licenses")
@RequiredArgsConstructor
@Slf4j
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
    public ResponseEntity<List<LicenseRecord>> getAllLicenses(
            @RequestHeader("X-Admin-Key") String key) {
        if (!adminKey.equals(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(licenseService.getAllLicenses());
    }

    @DeleteMapping("/{licenseId}/revoke")
    public ResponseEntity<Void> revoke(
            @RequestHeader("X-Admin-Key") String key,
            @PathVariable String licenseId) {
        if (!adminKey.equals(key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        licenseService.revokeLicense(licenseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{licenseId}/activations")
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
}
