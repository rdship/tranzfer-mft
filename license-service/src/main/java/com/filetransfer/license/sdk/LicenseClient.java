package com.filetransfer.license.sdk;

import com.filetransfer.license.dto.LicenseValidationRequest;
import com.filetransfer.license.dto.LicenseValidationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SDK component used by OTHER services to validate their license.
 * Drop this into any Spring Boot service that needs license enforcement.
 */
@Component
@Slf4j
public class LicenseClient {

    @Value("${license.service-url:http://license-service:8089}")
    private String licenseServiceUrl;

    @Value("${license.key:}")
    private String licenseKey;

    @Value("${license.enabled:true}")
    private boolean licenseEnabled;

    @Value("${cluster.id:default}")
    private String hostId;

    @Value("${cluster.service-type:UNKNOWN}")
    private String serviceType;

    private final AtomicReference<LicenseValidationResponse> cachedResponse = new AtomicReference<>();
    private volatile Instant lastValidation = Instant.EPOCH;
    private static final long CACHE_HOURS = 6;

    private String fingerprint;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!licenseEnabled) {
            log.info("License validation disabled");
            return;
        }
        this.fingerprint = generateFingerprint();
        validateNow();
    }

    @Scheduled(fixedDelay = 21600000) // every 6 hours
    @SchedulerLock(name = "license_scheduledValidation", lockAtLeastFor = "PT5H", lockAtMostFor = "PT6H")
    public void scheduledValidation() {
        if (!licenseEnabled) return;
        validateNow();
    }

    private void validateNow() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            LicenseValidationRequest req = new LicenseValidationRequest(
                    licenseKey.isBlank() ? null : licenseKey,
                    serviceType, hostId, fingerprint, null, null);

            LicenseValidationResponse response = restTemplate.postForObject(
                    licenseServiceUrl + "/api/v1/licenses/validate", req, LicenseValidationResponse.class);

            if (response != null) {
                cachedResponse.set(response);
                lastValidation = Instant.now();
                if (response.isValid()) {
                    log.info("License valid: {} mode, edition={}, expires={}",
                            response.getMode(), response.getEdition(), response.getExpiresAt());
                } else {
                    log.warn("License INVALID: {}", response.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not reach license service: {}. Using cached response.", e.getMessage());
        }
    }

    public boolean isLicenseValid() {
        if (!licenseEnabled) return true;
        LicenseValidationResponse cached = cachedResponse.get();
        if (cached == null) return true; // grace: not yet validated
        // Allow cached response for 24h if service unreachable
        if (Instant.now().isAfter(lastValidation.plusSeconds(86400))) {
            log.error("License service unreachable for 24h — treating as invalid");
            return false;
        }
        return cached.isValid();
    }

    public boolean isFeatureEnabled(String featureName) {
        if (!licenseEnabled) return true;
        LicenseValidationResponse cached = cachedResponse.get();
        if (cached == null || !cached.isValid()) return false;
        List<String> features = cached.getFeatures();
        return features != null && features.contains(featureName);
    }

    public LicenseValidationResponse getCurrentLicense() {
        return cachedResponse.get();
    }

    private String generateFingerprint() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String os = System.getProperty("os.name");
            return java.util.Base64.getEncoder().encodeToString(
                    (hostname + "|" + os + "|" + serviceType).getBytes());
        } catch (Exception e) {
            return "unknown-" + System.currentTimeMillis();
        }
    }
}
