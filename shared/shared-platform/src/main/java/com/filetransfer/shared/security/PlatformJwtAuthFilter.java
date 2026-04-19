package com.filetransfer.shared.security;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.repository.security.RolePermissionRepository;
import com.filetransfer.shared.repository.security.UserPermissionRepository;
import com.filetransfer.shared.repository.core.UserRepository;
import com.filetransfer.shared.spiffe.SpiffeMtlsAuthFilter;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Combined authentication filter supporting three identity paths, evaluated in order:
 *
 * <ol>
 *   <li><b>SPIFFE mTLS peer certificate (Phase 2)</b> — {@link SpiffeMtlsAuthFilter}
 *       (pre-security) extracted the caller's SPIFFE ID from the TLS client certificate
 *       and stored it in the {@code spiffe.mtls.peer-id} request attribute. This path
 *       reads that attribute and grants {@code ROLE_INTERNAL} with zero JWT overhead.
 *       Active when {@code spiffe.mtls-enabled=true} and the caller presents a cert.
 *   <li><b>SPIFFE JWT-SVID (Phase 1)</b> — Bearer token whose {@code sub} claim starts
 *       with {@code spiffe://}. Validated via the SPIRE Workload API trust bundle.
 *       Grants {@code ROLE_INTERNAL}. Fallback for {@code http://} service-to-service
 *       calls and environments without full mTLS.
 *   <li><b>Platform JWT</b> — Bearer token issued by the platform's auth service.
 *       Grants {@code ROLE_<role>} + fine-grained {@code PERM_*} authorities.
 *       Used by admin UI, partner portal, and CLI.
 * </ol>
 *
 * <p>If none match, the filter chain continues unauthenticated and Spring Security
 * enforces 401.
 */
@Slf4j
public class PlatformJwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final PlatformConfig platformConfig;
    @Nullable private final PermissionService permissionService;
    @Nullable private final SpiffeWorkloadClient spiffeWorkloadClient;

    /** Full constructor — SPIFFE + RBAC enabled. */
    public PlatformJwtAuthFilter(JwtUtil jwtUtil, PlatformConfig platformConfig,
                                  @Nullable PermissionService permissionService,
                                  @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        this.jwtUtil = jwtUtil;
        this.platformConfig = platformConfig;
        this.permissionService = permissionService;
        this.spiffeWorkloadClient = spiffeWorkloadClient;
    }

    /** Backwards-compatible constructor for services without RBAC */
    public PlatformJwtAuthFilter(JwtUtil jwtUtil, PlatformConfig platformConfig) {
        this(jwtUtil, platformConfig, null, null);
    }

    /** Constructor for services with RBAC but without SPIFFE (legacy). */
    public PlatformJwtAuthFilter(JwtUtil jwtUtil, PlatformConfig platformConfig,
                                  @Nullable PermissionService permissionService) {
        this(jwtUtil, platformConfig, permissionService, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // ── Path 0: SPIFFE mTLS peer certificate ────────────────────────────
        // SpiffeMtlsAuthFilter (pre-security) extracted the SPIFFE ID from the TLS
        // client certificate and stored it as a request attribute. Authenticate here
        // inside the Spring Security chain so the context is not overwritten.
        String peerSpiffeId = (String) request.getAttribute(SpiffeMtlsAuthFilter.PEER_SPIFFE_ID_ATTR);
        if (peerSpiffeId != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            peerSpiffeId, null,
                            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
            filterChain.doFilter(request, response);
            return;
        }

        String bearerToken = extractBearerToken(request);

        // ── Path 1: SPIFFE JWT-SVID ──────────────────────────────────────────
        // Bearer token whose sub claim is a spiffe:// URI — issued by SPIRE agent,
        // short-lived (1h), automatically rotated. Zero static secrets.
        if (bearerToken != null && bearerToken.startsWith("eyJ") && isSpiffeToken(bearerToken)) {
            if (spiffeWorkloadClient != null && spiffeWorkloadClient.isEnabled()) {
                // R133: bounded wait closes the downstream-side SPIRE boot race.
                // If the caller sends a valid SPIFFE JWT-SVID during the window
                // when this service's own SpiffeWorkloadClient is still dialing
                // its agent, we previously rejected the call unauthenticated
                // (→ 403). A 5 s one-time wait absorbs the race, matching the
                // caller-side R111 pattern in BaseServiceClient.
                if (!spiffeWorkloadClient.isAvailable()) {
                    spiffeWorkloadClient.awaitAvailable(java.time.Duration.ofSeconds(5));
                }
                if (spiffeWorkloadClient.isAvailable()) {
                    String selfId = spiffeWorkloadClient.getSelfSpiffeId();
                    if (StringUtils.hasText(selfId) && spiffeWorkloadClient.validate(bearerToken, selfId)) {
                        String callerId = spiffeWorkloadClient.getCallerId(bearerToken);
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        callerId != null ? callerId : "spiffe-service", null,
                                        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
                        filterChain.doFilter(request, response);
                        return;
                    }
                }
            }
            // R133: SPIFFE validation failed — try X-Forwarded-Authorization
            // fallback before rejecting. BaseServiceClient attaches the inbound
            // admin JWT on this secondary header so user-initiated S2S calls
            // (BUG 12 class) still authorize when SPIFFE-validation is broken
            // (audience mismatch, trust-bundle, or expiry). Background calls
            // that have no inbound request attach nothing here → fall through
            // to unauthenticated as before.
            if (tryForwardedAuthFallback(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // ── Path 2: Platform JWT (user / CLI / partner) ──────────────────────
        if (bearerToken != null && jwtUtil.isValid(bearerToken)) {
            String email = jwtUtil.getSubject(bearerToken);
            String role = jwtUtil.getRole(bearerToken);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));

            if (permissionService != null) {
                try {
                    Set<String> perms = permissionService.getEffectivePermissions(email);
                    perms.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
                } catch (Exception e) {
                    log.debug("Could not load permissions for {}: {}", email, e.getMessage());
                }
            }

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(email, null, authorities));
            filterChain.doFilter(request, response);
            return;
        }

        // None matched — continue unauthenticated; Spring Security enforces 401
        filterChain.doFilter(request, response);
    }

    /**
     * A quick pre-check: does the JWT payload's sub claim look like a SPIFFE ID?
     * Avoids calling JwtUtil.isValid() on SPIFFE tokens (wrong key / wrong claims).
     * We decode the payload without verification — validation happens in SpiffeWorkloadClient.
     */
    private boolean isSpiffeToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return false;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(
                    parts[1].length() % 4 == 0 ? parts[1] : parts[1] + "==".substring(parts[1].length() % 4)));
            return payload.contains("\"spiffe://");
        } catch (Exception e) {
            return false;
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    /**
     * R133: parse the {@code X-Forwarded-Authorization} header (set by
     * {@code BaseServiceClient} on every user-initiated S2S call) as a
     * Platform JWT. Returns true iff the token validated and an
     * authentication was set in the context.
     */
    private boolean tryForwardedAuthFallback(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) return false;
        String token = header.substring(7);
        if (!jwtUtil.isValid(token)) return false;
        String email = jwtUtil.getSubject(token);
        String role = jwtUtil.getRole(token);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        if (permissionService != null) {
            try {
                Set<String> perms = permissionService.getEffectivePermissions(email);
                perms.forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
            } catch (Exception e) {
                log.debug("Forwarded-auth: could not load permissions for {}: {}", email, e.getMessage());
            }
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, authorities));
        log.debug("[PlatformJwtAuthFilter] SPIFFE rejected — fell back to X-Forwarded-Authorization (user={}, role={})", email, role);
        return true;
    }
}
