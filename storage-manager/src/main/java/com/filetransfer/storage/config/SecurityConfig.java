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
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
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
 * <p><b>R134O:</b> Relaxes the default {@link StrictHttpFirewall} to allow
 * URL-encoded slashes in request URIs. {@code StorageCoordinationClient}
 * URL-encodes lock keys that legitimately contain slashes (e.g.
 * {@code vfs:write:<uuid>:/inbox/x.xml} → {@code ...%2Finbox%2Fx.xml}).
 * Spring Security's default firewall rejects {@code %2F} with 403 before
 * the {@link PlatformJwtAuthFilter} chain ever runs — exactly the R134N
 * verdict symptom: filter never enters for {@code /api/v1/coordination/locks/**}
 * while {@code /api/v1/storage/**} (no encoded slashes) passes cleanly.
 * Also opens {@code %25} (percent, for double-encoding safety) and
 * {@code %2E} (period). Scoped to storage-manager only.
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

    /**
     * R134O — allow URL-encoded slashes so the coordination-lock path pattern
     * {@code /api/v1/coordination/locks/{urlEncodedKey}/acquire} reaches the
     * Spring Security filter chain. Default {@code StrictHttpFirewall} 403s
     * {@code %2F} before the chain runs.
     */
    @Bean
    public HttpFirewall storageManagerHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        firewall.setAllowUrlEncodedPercent(true);
        firewall.setAllowUrlEncodedPeriod(true);
        return firewall;
    }

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
