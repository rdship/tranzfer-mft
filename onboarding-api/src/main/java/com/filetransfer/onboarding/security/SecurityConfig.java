package com.filetransfer.onboarding.security;

import com.filetransfer.shared.ratelimit.ApiRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Autowired(required = false)
    private ApiRateLimitFilter apiRateLimitFilter;

    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:3002}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ant("/api/auth/**")).permitAll()
                        .requestMatchers(ant("/api/partner/login")).permitAll()
                        .requestMatchers(ant("/api/2fa/verify")).permitAll()
                        .requestMatchers(ant("/actuator/health/**"), ant("/actuator/health")).permitAll()
                        // R132: health/liveness endpoints polled every few seconds by the UI
                        // sidebar — no sensitive data, returns only "UP" + counts. Keeping
                        // them under .authenticated() caused 192×403/min spam in network log
                        // when the browser-side Authorization header failed to attach for any
                        // reason (expired JWT, CORS preflight, etc.). Matches the platform-wide
                        // pattern set by PlatformSecurityConfig for /api/*\/health.
                        .requestMatchers(ant("/api/pipeline/health"), ant("/api/*/health"), ant("/api/v1/*/health")).permitAll()
                        .requestMatchers(ant("/v3/api-docs/**"), ant("/swagger-ui/**"), ant("/swagger-ui.html")).permitAll()
                        .requestMatchers(ant("/api/cli/**")).hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (apiRateLimitFilter != null) {
            // Run the rate limiter AFTER JwtAuthFilter so the per-user bucket
            // uses the authenticated principal (200/min) and the ROLE_INTERNAL
            // bypass sees the Authentication that SPIFFE / JWT-SVID set.
            // R93: used to be addFilterBefore(..., JwtAuthFilter.class) — that
            // meant onboarding-api only ever applied the 100/min IP bucket
            // regardless of who was calling, and S2S SPIFFE calls were treated
            // as external. Matches PlatformSecurityConfig (config-service etc).
            http.addFilterAfter(apiRateLimitFilter, JwtAuthFilter.class);
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    private static RequestMatcher ant(String pattern) {
        return new AntPathRequestMatcher(pattern);
    }
}
