package com.filetransfer.storage.config;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.security.PlatformJwtAuthFilter;
import com.filetransfer.shared.util.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Storage-manager security config — internal service, not exposed via API gateway.
 *
 * <p>All storage API endpoints ({@code /api/v1/storage/**}) are permitted without
 * authentication. Storage-manager is an internal-only service — Docker port 8096
 * is NOT exposed through the API gateway. Only other platform services call it
 * via the Docker network. SPIFFE auth provides defense-in-depth but must not
 * block the pipeline when the SPIRE agent is unavailable.
 */
@Configuration
@EnableWebSecurity
public class StorageSecurityConfig {

    @Bean
    @Primary
    public SecurityFilterChain storageSecurityFilterChain(HttpSecurity http,
                                                          JwtUtil jwtUtil,
                                                          PlatformConfig platformConfig) throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(jwtUtil, platformConfig);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ant("/actuator/**")).permitAll()
                        .requestMatchers(ant("/api/v1/storage/**")).permitAll()
                        .requestMatchers(ant("/api/*/health"), ant("/health")).permitAll()
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
