package com.filetransfer.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces {@code PLATFORM_VERSION} + {@code PLATFORM_BUILD_TIMESTAMP}
 * (and this service's {@code spring.application.name}) under
 * {@code /actuator/info.build.*} so ops and tester can correlate the
 * running container image with the git commit that was shipped.
 *
 * <p>R134h — per the R134g verification, pom.xml artifact versions are
 * static (jars never publish to Nexus; only feed Docker builds) so the
 * runtime version is canonically in {@code PLATFORM_VERSION}. Tester
 * asked for a way to read that value at runtime alongside the commit
 * SHA. This contributor is the single bean that every service inherits
 * via the shared-platform classpath.
 *
 * <p>Response shape:
 * <pre>{@code
 * {
 *   "build": {
 *     "version": "1.0.0-R134h",
 *     "timestamp": "2026-04-19T16:00:00Z",
 *     "service": "encryption-service",
 *     "retrievedAt": "2026-04-19T16:02:14.372Z"
 *   }
 * }
 * }</pre>
 */
@Component
public class PlatformVersionInfoContributor implements InfoContributor {

    @Value("${platform.version:unknown}")
    private String platformVersion;

    @Value("${platform.build.timestamp:unknown}")
    private String buildTimestamp;

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("version", platformVersion);
        build.put("timestamp", buildTimestamp);
        build.put("service", serviceName);
        build.put("retrievedAt", Instant.now().toString());
        builder.withDetail("build", build);
    }
}
