package com.filetransfer.shared.security;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Combined authentication filter that supports both:
 *   1. JWT Bearer token — for admin UI / partner portal / CLI access
 *   2. X-Internal-Key header — for inter-service communication
 *
 * If neither is present, the filter chain continues unauthenticated
 * and Spring Security will reject with 401.
 */
@Slf4j
@RequiredArgsConstructor
public class PlatformJwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final PlatformConfig platformConfig;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. Try JWT Bearer token
        String token = extractBearerToken(request);
        if (token != null && jwtUtil.isValid(token)) {
            String email = jwtUtil.getSubject(token);
            String role = jwtUtil.getRole(token);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            email, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Try X-Internal-Key (inter-service)
        String apiKey = request.getHeader("X-Internal-Key");
        if (StringUtils.hasText(apiKey)
                && apiKey.equals(platformConfig.getSecurity().getControlApiKey())) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "internal-service", null,
                            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
            filterChain.doFilter(request, response);
            return;
        }

        // Neither — continue unauthenticated (Spring Security will handle 401)
        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
