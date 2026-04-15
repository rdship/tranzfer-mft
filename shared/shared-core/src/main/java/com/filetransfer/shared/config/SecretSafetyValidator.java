package com.filetransfer.shared.config;

import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.client.ServiceClientProperties.ServiceEndpoint;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates at startup that no default or weak secrets are present in
 * production-like environments (PROD, STAGING, CERT).
 *
 * <p>In DEV/TEST the same checks run but only emit WARN-level log messages,
 * allowing local development to proceed with default values.
 *
 * <p>Checked secrets:
 * <ul>
 *   <li>JWT secret from {@link PlatformConfig}</li>
 *   <li>Datasource password (spring.datasource.password)</li>
 * </ul>
 *
 * <p>Additionally, all {@code platform.services.*} URLs are scanned for
 * plain HTTP usage in production-like environments (logged as ERROR but
 * does not block startup, since mTLS is on the roadmap).
 */
@Slf4j
@Component
public class SecretSafetyValidator {

    private static final String DEFAULT_JWT_SECRET = "change_me_in_production_256bit_secret_key!!";
    private static final String DEFAULT_DB_PASSWORD = "postgres";

    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private final PlatformConfig platformConfig;

    @Autowired(required = false)
    private ServiceClientProperties serviceClientProperties;

    @Value("${platform.environment:PROD}")
    private String environment;

    @Value("${spring.datasource.password:#{null}}")
    private String datasourcePassword;

    public SecretSafetyValidator(PlatformConfig platformConfig) {
        this.platformConfig = platformConfig;
    }

    @PostConstruct
    public void validate() {
        boolean productionLike = isProductionLike();
        String envLabel = environment.toUpperCase();

        List<String> violations = new ArrayList<>();

        // --- Default-secret checks ---

        String jwtSecret = platformConfig.getSecurity().getJwtSecret();
        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            violations.add("platform.security.jwt-secret is still the default value");
        }

        if (datasourcePassword != null && DEFAULT_DB_PASSWORD.equals(datasourcePassword)) {
            violations.add("spring.datasource.password is still the default value ('postgres')");
        }

        // --- Weak-secret checks (length) ---

        if (jwtSecret != null && jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            violations.add(String.format(
                    "platform.security.jwt-secret is too short (%d chars); minimum is %d",
                    jwtSecret.length(), MIN_JWT_SECRET_LENGTH));
        }

        // --- Report secret violations ---

        if (!violations.isEmpty()) {
            if (productionLike) {
                for (String v : violations) {
                    log.error("SECRET VIOLATION [{}]: {}", envLabel, v);
                }
                throw new IllegalStateException(
                        "Startup blocked: " + violations.size()
                                + " secret safety violation(s) detected in " + envLabel
                                + " environment. See log for details.");
            } else {
                for (String v : violations) {
                    log.warn("SECRET WARNING [{}]: {} (allowed in non-production)", envLabel, v);
                }
            }
        }

        // --- Service-URL transport check (never blocks startup) ---

        checkServiceUrls(envLabel, productionLike);

        if (violations.isEmpty()) {
            log.info("SecretSafetyValidator: all checks passed for environment {}", envLabel);
        }
    }

    /**
     * Scans all {@link ServiceClientProperties} endpoint URLs for plain HTTP
     * usage. In production-like environments this is logged at ERROR level;
     * in DEV/TEST at WARN level. Startup is never blocked by this check.
     */
    private void checkServiceUrls(String envLabel, boolean productionLike) {
        if (serviceClientProperties == null) {
            return;
        }
        List<String> httpUrls = new ArrayList<>();

        for (Field field : ServiceClientProperties.class.getDeclaredFields()) {
            if (!ServiceEndpoint.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            try {
                ServiceEndpoint endpoint = (ServiceEndpoint) field.get(serviceClientProperties);
                if (endpoint != null && endpoint.getUrl() != null
                        && endpoint.getUrl().toLowerCase().startsWith("http://")) {
                    httpUrls.add(field.getName() + " -> " + endpoint.getUrl());
                }
            } catch (IllegalAccessException e) {
                log.debug("Could not inspect service endpoint field: {}", field.getName(), e);
            }
        }

        if (!httpUrls.isEmpty()) {
            if (productionLike) {
                log.error("TRANSPORT WARNING [{}]: {} service URL(s) use plain HTTP instead of HTTPS. "
                        + "mTLS is on the roadmap; switch to HTTPS when available:", envLabel, httpUrls.size());
            } else {
                log.warn("TRANSPORT WARNING [{}]: {} service URL(s) use plain HTTP (acceptable in {}):",
                        envLabel, httpUrls.size(), envLabel);
            }
            for (String entry : httpUrls) {
                if (productionLike) {
                    log.error("  - {}", entry);
                } else {
                    log.warn("  - {}", entry);
                }
            }
        }
    }

    private boolean isProductionLike() {
        String env = environment.toUpperCase();
        return "PROD".equals(env) || "STAGING".equals(env) || "CERT".equals(env);
    }
}
