package com.filetransfer.license.service;

import com.filetransfer.license.crypto.LicenseCrypto;
import com.filetransfer.license.dto.*;
import com.filetransfer.license.entity.InstallationFingerprint;
import com.filetransfer.license.entity.LicenseActivation;
import com.filetransfer.license.entity.LicenseRecord;
import com.filetransfer.license.repository.InstallationFingerprintRepository;
import com.filetransfer.license.repository.LicenseActivationRepository;
import com.filetransfer.license.repository.LicenseRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseService {

    private final LicenseRecordRepository licenseRecordRepository;
    private final LicenseActivationRepository licenseActivationRepository;
    private final InstallationFingerprintRepository fingerprintRepository;
    private final LicenseCrypto licenseCrypto;

    @Value("${license.trial-days:30}")
    private int trialDays;

    @Value("${license.issuer-name:TranzFer MFT Platform}")
    private String issuerName;

    @Transactional
    public LicenseValidationResponse validateLicense(LicenseValidationRequest request) {
        // Trial mode: no license key provided
        if (request.getLicenseKey() == null || request.getLicenseKey().isBlank()) {
            return handleTrialValidation(request);
        }
        return handleLicensedValidation(request);
    }

    private LicenseValidationResponse handleTrialValidation(LicenseValidationRequest request) {
        String fingerprint = request.getInstallationFingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            return LicenseValidationResponse.builder()
                    .valid(false).mode("TRIAL").message("Installation fingerprint required for trial mode").build();
        }

        InstallationFingerprint fp = fingerprintRepository.findByFingerprintAndActiveTrue(fingerprint)
                .orElseGet(() -> {
                    InstallationFingerprint newFp = InstallationFingerprint.builder()
                            .fingerprint(fingerprint)
                            .customerId(request.getCustomerId())
                            .customerName(request.getCustomerName())
                            .trialStarted(Instant.now())
                            .trialExpires(Instant.now().plus(trialDays, ChronoUnit.DAYS))
                            .build();
                    return fingerprintRepository.save(newFp);
                });

        Instant now = Instant.now();
        if (now.isAfter(fp.getTrialExpires())) {
            return LicenseValidationResponse.builder()
                    .valid(false).mode("TRIAL").edition("TRIAL")
                    .message("Trial period expired. Please purchase a license.")
                    .expiresAt(fp.getTrialExpires()).trialDaysRemaining(0).build();
        }

        long daysRemaining = ChronoUnit.DAYS.between(now, fp.getTrialExpires());
        log.info("Trial validation OK for fingerprint {} ({}d remaining)", fingerprint, daysRemaining);

        return LicenseValidationResponse.builder()
                .valid(true).mode("TRIAL").edition("TRIAL")
                .trialDaysRemaining((int) daysRemaining)
                .expiresAt(fp.getTrialExpires())
                .maxInstances(1)
                .maxConcurrentConnections(10)
                .features(List.of("BASIC_SFTP", "BASIC_FTP", "ADMIN_UI"))
                .message("Trial active. " + daysRemaining + " days remaining.")
                .build();
    }

    private LicenseValidationResponse handleLicensedValidation(LicenseValidationRequest request) {
        LicensePayload payload;
        try {
            payload = licenseCrypto.verify(request.getLicenseKey());
        } catch (Exception e) {
            log.warn("Invalid license key: {}", e.getMessage());
            return LicenseValidationResponse.builder()
                    .valid(false).message("Invalid license key: " + e.getMessage()).build();
        }

        if (Instant.now().isAfter(payload.getExpiresAt())) {
            return LicenseValidationResponse.builder()
                    .valid(false).edition(payload.getEdition())
                    .expiresAt(payload.getExpiresAt())
                    .message("License expired on " + payload.getExpiresAt()).build();
        }

        LicenseRecord record = licenseRecordRepository.findByLicenseIdAndActiveTrue(payload.getLicenseId())
                .orElse(null);
        if (record == null) {
            return LicenseValidationResponse.builder()
                    .valid(false).message("License not found or revoked").build();
        }

        // Update activation check-in
        LicenseActivation activation = licenseActivationRepository
                .findByLicenseRecordAndServiceTypeAndHostId(record, request.getServiceType(), request.getHostId())
                .orElseGet(() -> LicenseActivation.builder()
                        .licenseRecord(record)
                        .serviceType(request.getServiceType())
                        .hostId(request.getHostId())
                        .build());
        activation.setLastCheckIn(Instant.now());
        activation.setActive(true);
        licenseActivationRepository.save(activation);

        // Find service-specific limits
        LicensePayload.ServiceLicense svcLicense = payload.getServices() == null ? null :
                payload.getServices().stream()
                        .filter(s -> s.getServiceType().equalsIgnoreCase(request.getServiceType()))
                        .findFirst().orElse(null);

        int maxInstances = svcLicense != null ? svcLicense.getMaxInstances() : 3;
        int maxConns = svcLicense != null ? svcLicense.getMaxConcurrentConnections() : 500;
        List<String> features = svcLicense != null ? svcLicense.getFeatures() : List.of();

        return LicenseValidationResponse.builder()
                .valid(true).mode("LICENSED").edition(payload.getEdition())
                .expiresAt(payload.getExpiresAt())
                .maxInstances(maxInstances)
                .maxConcurrentConnections(maxConns)
                .features(features)
                .message("License valid for " + payload.getCustomerName())
                .build();
    }

    @Transactional
    public String issueLicense(LicenseIssueRequest request) {
        String licenseId = "LIC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Instant now = Instant.now();
        Instant expires = now.plus(request.getValidDays(), ChronoUnit.DAYS);

        List<LicensePayload.ServiceLicense> services = new ArrayList<>();
        if (request.getServices() != null) {
            for (var s : request.getServices()) {
                services.add(LicensePayload.ServiceLicense.builder()
                        .serviceType(s.getServiceType())
                        .maxInstances(s.getMaxInstances())
                        .maxConcurrentConnections(s.getMaxConcurrentConnections())
                        .features(s.getFeatures() != null ? s.getFeatures() : List.of())
                        .build());
            }
        }

        LicensePayload payload = LicensePayload.builder()
                .licenseId(licenseId)
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName())
                .edition(request.getEdition())
                .issuedAt(now)
                .expiresAt(expires)
                .services(services)
                .build();

        String licenseKey = licenseCrypto.sign(payload);

        List<Map<String, Object>> servicesJson = new ArrayList<>();
        for (var s : services) {
            Map<String, Object> m = new HashMap<>();
            m.put("serviceType", s.getServiceType());
            m.put("maxInstances", s.getMaxInstances());
            m.put("maxConcurrentConnections", s.getMaxConcurrentConnections());
            m.put("features", s.getFeatures());
            servicesJson.add(m);
        }

        LicenseRecord record = LicenseRecord.builder()
                .licenseId(licenseId)
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName())
                .edition(LicenseRecord.LicenseEdition.valueOf(request.getEdition()))
                .issuedAt(now)
                .expiresAt(expires)
                .services(servicesJson)
                .notes(request.getNotes())
                .build();

        licenseRecordRepository.save(record);
        log.info("Issued license {} for customer {} ({})", licenseId, request.getCustomerName(), request.getEdition());
        return licenseKey;
    }

    @Transactional
    public void revokeLicense(String licenseId) {
        LicenseRecord record = licenseRecordRepository.findByLicenseIdAndActiveTrue(licenseId)
                .orElseThrow(() -> new EntityNotFoundException("License not found: " + licenseId));
        record.setActive(false);
        licenseRecordRepository.save(record);
        log.warn("Revoked license {}", licenseId);
    }

    public List<LicenseRecord> getAllLicenses() {
        return licenseRecordRepository.findAll();
    }

    public List<LicenseActivation> getActivations(String licenseId) {
        LicenseRecord record = licenseRecordRepository.findByLicenseIdAndActiveTrue(licenseId)
                .orElseThrow(() -> new EntityNotFoundException("License not found: " + licenseId));
        return licenseActivationRepository.findByLicenseRecord(record);
    }
}
