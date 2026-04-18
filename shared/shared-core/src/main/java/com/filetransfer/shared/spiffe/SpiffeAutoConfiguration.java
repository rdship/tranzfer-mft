package com.filetransfer.shared.spiffe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/**
 * Spring Boot auto-configuration for SPIFFE/SPIRE workload identity.
 *
 * <p>R112: {@code @ConditionalOnProperty(spiffe.enabled)} removed from the
 * class level because Spring Boot's AOT processor evaluates conditions at
 * <em>build time</em>, not runtime. R111 failed when the AOT build was done
 * without {@code spiffe.enabled=true} in the processor's environment — the
 * frozen bean graph simply omitted {@link SpiffeWorkloadClient}, making the
 * runtime {@code SPIFFE_ENABLED=true} env var a no-op and regressing S2S
 * auth silently. The bean is now always registered; its constructor
 * no-ops itself at runtime when {@code spiffe.enabled=false} (skipping the
 * workload-API dial), preserving the "disabled-is-zero-cost" guarantee
 * without fighting AOT.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link SpiffeWorkloadClient} — JWT-SVID outbound auth (Phase 1: cached, proactive refresh).
 *       Auto-wired (optional) into {@code BaseServiceClient} and {@code PlatformJwtAuthFilter}.
 *       Always present at runtime; self-disables when {@code spiffe.enabled=false}.
 *   <li>{@link SpiffeX509Manager} — X.509-SVID mTLS transport (Phase 2). Activated only when
 *       {@code spiffe.mtls-enabled=true}. Auto-wired (optional) into {@code SharedConfig}
 *       to build an mTLS-capable RestTemplate, and into {@code BaseServiceClient} to skip the
 *       JWT header when the target URL uses {@code https://}.
 *   <li>{@link SpiffeMtlsAuthFilter} — Pre-security filter that extracts the SPIFFE ID from
 *       TLS peer cert (direct Tomcat mTLS or Nginx offload header) and stores it in a request
 *       attribute for {@code PlatformJwtAuthFilter} to authenticate as {@code ROLE_INTERNAL}.
 *       Activated only when {@code spiffe.mtls-enabled=true}.
 * </ul>
 */
@Slf4j
@AutoConfiguration
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

    /**
     * X.509 source manager for mTLS transport — Phase 2.
     *
     * <p>Created only when {@code spiffe.mtls-enabled=true}. If the SPIRE agent socket is
     * unavailable at startup, the manager marks itself unavailable and {@code SharedConfig}
     * falls back to the plain JWT-SVID RestTemplate gracefully.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "spiffe.mtls-enabled", havingValue = "true")
    public SpiffeX509Manager spiffeX509Manager(SpiffeProperties props) {
        return new SpiffeX509Manager(props);
    }

    /**
     * Pre-security servlet filter that extracts the SPIFFE peer identity from
     * the TLS client certificate and stores it in a request attribute.
     *
     * <p>Runs at {@code HIGHEST_PRECEDENCE + 1} — before Spring Security's FilterChainProxy.
     * {@code PlatformJwtAuthFilter} (inside the Security chain) reads the attribute as Path 0
     * and grants {@code ROLE_INTERNAL} without requiring a JWT Bearer token.
     */
    @Bean
    @ConditionalOnProperty(name = "spiffe.mtls-enabled", havingValue = "true")
    public FilterRegistrationBean<SpiffeMtlsAuthFilter> spiffeMtlsAuthFilter() {
        FilterRegistrationBean<SpiffeMtlsAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SpiffeMtlsAuthFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("spiffeMtlsAuthFilter");
        log.info("[SPIFFE] SpiffeMtlsAuthFilter registered at order={}", Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
