package com.filetransfer.shared.spiffe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Spring Boot auto-configuration for SPIFFE/SPIRE workload identity.
 *
 * <p>Activated only when {@code spiffe.enabled=true} in application properties.
 * When disabled, nothing is created and all existing X-Internal-Key paths
 * continue to work unchanged — safe for incremental rollout.
 *
 * <p>The {@link SpiffeWorkloadClient} bean is registered in the application
 * context and auto-wired (as optional) into:
 * <ul>
 *   <li>{@link com.filetransfer.shared.client.BaseServiceClient} — outbound JWT-SVID auth
 *   <li>{@link com.filetransfer.shared.security.PlatformJwtAuthFilter} — inbound validation
 * </ul>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "spiffe.enabled", havingValue = "true")
@EnableConfigurationProperties(SpiffeProperties.class)
public class SpiffeAutoConfiguration {

    /**
     * If {@code spiffe.service-name} is not set explicitly, derive it from
     * {@code spring.application.name}.
     */
    @Bean
    @ConditionalOnMissingBean
    public SpiffeWorkloadClient spiffeWorkloadClient(
            SpiffeProperties props,
            @Value("${spring.application.name:unknown}") String appName) {

        if (!StringUtils.hasText(props.getServiceName())) {
            props.setServiceName(appName);
            log.debug("[SPIFFE] service-name not set; derived from spring.application.name='{}'", appName);
        }
        return new SpiffeWorkloadClient(props);
    }
}
