package com.filetransfer.ai.config;

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

@Configuration
@EnableWebSecurity
public class AiSecurityConfig {

    @Bean
    @Primary
    public SecurityFilterChain aiSecurityFilterChain(HttpSecurity http,
                                                     JwtUtil jwtUtil,
                                                     PlatformConfig platformConfig) throws Exception {
        PlatformJwtAuthFilter filter = new PlatformJwtAuthFilter(jwtUtil, platformConfig);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // AI engine inter-service endpoints (called by DMZ proxy, no JWT)
                        .requestMatchers("/api/v1/proxy/**").permitAll()
                        .requestMatchers("/api/v1/intelligence/**").permitAll()
                        .requestMatchers("/api/v1/edi/**").permitAll()
                        .requestMatchers("/api/v1/ai/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
