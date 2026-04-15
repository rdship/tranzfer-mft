package com.filetransfer.shared.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates that critical security secrets are not using default/placeholder values.
 * Logs WARN in dev, throws in production.
 */
@Component
@Slf4j
public class SecurityConfigValidator {

    @Value("${platform.security.jwt-secret:change_me_in_production_256bit_secret_key!!}")
    private String jwtSecret;

    @Value("${platform.security.control-api-key:internal_control_secret}")
    private String controlApiKey;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${platform.version:UNKNOWN}")
    private String platformVersion;

    @Value("${platform.build-timestamp:UNKNOWN}")
    private String buildTimestamp;

    @Value("${cluster.service-type:UNKNOWN}")
    private String serviceType;

    private static final java.util.Set<String> INSECURE_DEFAULTS = java.util.Set.of(
        "change_me_in_production_256bit_secret_key!!",
        "internal_control_secret",
        "0000000000000000000000000000000000000000000000000000000000000000"
    );

    @PostConstruct
    public void validate() {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║  TranzFer MFT — {} v{}", serviceType, platformVersion);
        log.info("║  Built: {}", buildTimestamp);
        log.info("╚══════════════════════════════════════════════════════╝");

        boolean isProduction = activeProfile.contains("prod");

        if (INSECURE_DEFAULTS.contains(jwtSecret)) {
            String msg = "JWT secret is using the default insecure value! Set JWT_SECRET environment variable.";
            if (isProduction) throw new IllegalStateException(msg);
            log.warn("SECURITY: {} (acceptable in dev, FATAL in production)", msg);
        }

        if (INSECURE_DEFAULTS.contains(controlApiKey)) {
            String msg = "Control API key is using the default value! Set CONTROL_API_KEY environment variable.";
            if (isProduction) throw new IllegalStateException(msg);
            log.warn("SECURITY: {} (acceptable in dev, FATAL in production)", msg);
        }
    }
}
