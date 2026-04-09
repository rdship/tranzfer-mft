package com.filetransfer.shared.actuator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * EnvironmentPostProcessor that ensures the {@code circuit-breakers} actuator endpoint
 * is included in the management exposure list for all platform services.
 *
 * <p>Added at the lowest property source priority — overridden by any service-specific
 * {@code application.yml} that explicitly sets {@code management.endpoints.web.exposure.include}.
 * Services with an explicit include list must manually add {@code circuit-breakers}.
 *
 * <p>Registered via {@code META-INF/spring.factories}.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class CircuitBreakerEndpointExposer implements EnvironmentPostProcessor {

    private static final String EXPOSURE_PROPERTY = "management.endpoints.web.exposure.include";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.containsProperty(EXPOSURE_PROPERTY)) {
            env.getPropertySources().addLast(
                    new MapPropertySource("cb-endpoint-defaults", Map.of(
                            EXPOSURE_PROPERTY, "health,info,circuit-breakers,liveness,readiness"
                    ))
            );
        }
    }
}
