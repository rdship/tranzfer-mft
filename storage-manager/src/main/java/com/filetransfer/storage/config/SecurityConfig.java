package com.filetransfer.storage.config;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.security.PermissionService;
import com.filetransfer.shared.security.PlatformJwtAuthFilter;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Service-local SecurityFilterChain for storage-manager.
 *
 * <p><b>R134N:</b> Replaces the shared
 * {@link com.filetransfer.shared.security.InternalServiceSecurityConfig}
 * for storage-manager. The shared config is class-level
 * {@code @ConditionalOnProperty(havingValue="true")} with no
 * {@code matchIfMissing}, so AOT property resolution of the YAML
 * placeholder {@code ${PLATFORM_SECURITY_INTERNAL_SERVICE:true}} was
 * silently excluding the bean from storage-manager's frozen graph (see
 * {@code docs/AOT-SAFETY.md} and {@code docs/run-reports/R134M-runtime-verification.md}).
 *
 * <p>Runtime symptom: {@code PlatformJwtAuthFilter} never fired on
 * {@code /api/v1/coordination/locks/**}, so Spring's default
 * {@code SecurityAutoConfiguration} returned 403 before SPIFFE JWT-SVIDs
 * could be evaluated. This blocked the R134z primary path for
 * {@code VirtualFileSystem.lockPath} and forced the pg_advisory fallback.
 *
 * <p>Unconditional {@code @Configuration} + service-local scope = AOT-safe
 * and deterministic. Mirrors the shared chain's permit-list; the 4-arg
 * {@link PlatformJwtAuthFilter} constructor is used so SPIFFE identity
 * continues to populate {@code ROLE_INTERNAL} for downstream authz.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private PermissionService permissionService;

    @Autowired(required = false)
    private SpiffeWorkloadClient spiffeWorkloadClient;

    @Bean
    public SecurityFilterChain storageManagerSecurityFilterChain(HttpSecurity http,
                                                                   JwtUtil jwtUtil,
                                                                   PlatformConfig platformConfig) throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(
                jwtUtil, platformConfig, permissionService, spiffeWorkloadClient);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ant("/actuator/**")).permitAll()
                        .requestMatchers(ant("/api/**")).permitAll()
                        .requestMatchers(ant("/health")).permitAll()
                        .requestMatchers(ant("/v3/api-docs/**"), ant("/swagger-ui/**")).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static RequestMatcher ant(String pattern) {
        return new AntPathRequestMatcher(pattern);
    }
}
