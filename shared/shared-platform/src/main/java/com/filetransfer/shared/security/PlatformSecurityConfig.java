package com.filetransfer.shared.security;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.ratelimit.ApiRateLimitFilter;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Default security configuration for all platform services.
 *
 * Activates when platform.security.shared-config=true (default).
 * Services with their own SecurityConfig (onboarding-api, ftp-web-service)
 * should set platform.security.shared-config=false.
 *
 * Authentication modes:
 *   - SPIFFE JWT-SVID (for service-to-service; zero-trust, auto-rotating)
 *   - Platform JWT Bearer token (for admin/partner/CLI access)
 *
 * Open endpoints:
 *   - /actuator/** — health checks and management
 *
 * <p><b>N47 fix:</b> Spring Boot 3.4's PathPatternParser rejects {@code **}
 * in the middle of patterns. All matchers use {@link AntPathRequestMatcher}
 * explicitly to avoid PatternParseException.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "platform.security.shared-config", havingValue = "true", matchIfMissing = true)
public class PlatformSecurityConfig {

    @Autowired(required = false)
    private PermissionService permissionService;

    @Autowired(required = false)
    private ApiRateLimitFilter apiRateLimitFilter;

    @Autowired(required = false)
    private SpiffeWorkloadClient spiffeWorkloadClient;

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain platformSecurityFilterChain(HttpSecurity http,
                                                           JwtUtil jwtUtil,
                                                           PlatformConfig platformConfig) throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(
                jwtUtil, platformConfig, permissionService, spiffeWorkloadClient);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ant("/actuator/**")).permitAll()
                        .requestMatchers(ant("/internal/health")).permitAll()
                        .requestMatchers(ant("/internal/**")).hasRole("INTERNAL")
                        .requestMatchers(ant("/v3/api-docs/**"), ant("/swagger-ui/**"), ant("/swagger-ui.html")).permitAll()
                        // Health endpoints should never require auth — UI probes these to detect
                        // which services are running (ServiceContext.detectServices).
                        .requestMatchers(ant("/api/**/health"), ant("/health")).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);

        if (apiRateLimitFilter != null) {
            // Rate limiter runs AFTER JWT filter so ROLE_INTERNAL is already in SecurityContext
            http.addFilterAfter(apiRateLimitFilter, PlatformJwtAuthFilter.class);
        }

        return http.build();
    }

    private static RequestMatcher ant(String pattern) {
        return new AntPathRequestMatcher(pattern);
    }
}
