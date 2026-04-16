package com.filetransfer.shared.security;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.util.JwtUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Security config for internal-only services (storage-manager, screening-service,
 * encryption-service, edi-converter, external-forwarder-service).
 *
 * <p>These services are NOT exposed through the API gateway — only other platform
 * services call them via the Docker network. All API endpoints are permitted.
 * SPIFFE JWT-SVID auth is still attempted (defense-in-depth) but not required.
 *
 * <p>Activated by {@code PLATFORM_SECURITY_INTERNAL_SERVICE=true} in docker-compose.
 * This disables the shared {@link PlatformSecurityConfig} via
 * {@code PLATFORM_SECURITY_SHARED_CONFIG=false}.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "platform.security.internal-service", havingValue = "true")
public class InternalServiceSecurityConfig {

    @Bean
    public SecurityFilterChain internalServiceSecurityFilterChain(HttpSecurity http,
                                                                    JwtUtil jwtUtil,
                                                                    PlatformConfig platformConfig) throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(jwtUtil, platformConfig);

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
