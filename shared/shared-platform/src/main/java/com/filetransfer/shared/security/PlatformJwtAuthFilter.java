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

        // R134 diagnostic (per R133 acceptance directive): noisy log at the
        // first statement of doFilter so tester can verify — on any 403 from
        // this service — whether this filter is even on the chain for that
        // request. Five releases of adding fallback code paths with no
        // runtime delta is the signature of a wiring bug, not a logic bug.
        // If this line DOES NOT appear for a 403 request, the filter isn't
        // mounted; if it DOES, we can see every branch taken.
        if (log.isInfoEnabled()) {
            log.info("[PlatformJwtAuthFilter] entered method={} uri={} authz={} xfwd={}",
                    request.getMethod(), request.getRequestURI(),
                    headerSummary(request.getHeader("Authorization")),
                    headerSummary(request.getHeader("X-Forwarded-Authorization")));
        }

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

        // R134 — Path 0.5: X-Forwarded-Authorization is checked ahead of
        // the SPIFFE branch because BaseServiceClient sets it whenever the
        // outbound call originated from an inbound user request. Presence
        // of this header means "a human admin clicked a button" — their
        // ROLE_<role> authority should win over the system ROLE_INTERNAL
        // that a valid SPIFFE SVID would grant (SPIFFE is a cluster-peer
        // identity, not a user one). This is the only path that reliably
        // authorizes an admin action when the downstream SPIFFE rejects or
        // the downstream's @PreAuthorize demands ADMIN/OPERATOR rather
        // than INTERNAL. Root cause of R133 no-runtime-delta: the fallback
        // fired only inside the SPIFFE-failed sub-branch, which does not
        // run at all if SPIFFE succeeds (and the R133 trust-domain
        // fallback made it succeed) — so ROLE_INTERNAL ended up set and
        // downstream @PreAuthorize rejected.
        if (tryForwardedAuthFallback(request)) {
            log.info("[PlatformJwtAuthFilter] authorized via X-Forwarded-Authorization");
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
                    boolean validationOk = StringUtils.hasText(selfId)
                            && spiffeWorkloadClient.validate(bearerToken, selfId);
                    // R134L — tester R134J/K need evidence of why the SFTP
                    // callback → storage-coord call gets 403. Log the validate
                    // result so the next runtime sweep pinpoints whether the
                    // SVID is rejected (audience mismatch, expiry, trust
                    // bundle) OR just never validated.
                    log.info("[PlatformJwtAuthFilter] SPIFFE validate result={} selfId={} callerSub={}",
                            validationOk, selfId,
                            spiffeWorkloadClient.getCallerId(bearerToken));
                    if (validationOk) {
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
     * Redacted one-liner for Authorization / X-Forwarded-Authorization diag logs.
     * Never dump a full JWT — just the scheme + token length + first 6 chars
     * so the log is tamper-resistant but enough to correlate with upstream.
     */
    private static String headerSummary(String header) {
        if (header == null || header.isBlank()) return "(none)";
        if (!header.startsWith("Bearer ")) return "(no-bearer:" + header.length() + ")";
        String token = header.substring(7);
        int preview = Math.min(6, token.length());
        return "Bearer " + token.substring(0, preview) + "...(" + token.length() + ")";
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
