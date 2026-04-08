package com.filetransfer.dmz.security;

import io.spiffe.svid.jwtsvid.JwtSvid;
import io.spiffe.workloadapi.DefaultJwtSource;
import io.spiffe.workloadapi.JwtSource;
import io.spiffe.workloadapi.JwtSourceOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SPIFFE JWT-SVID validator for the DMZ Proxy management API.
 *
 * <p>The DMZ proxy is intentionally isolated (no shared-platform, no DB),
 * so SPIFFE support is implemented directly here rather than via shared-core.
 *
 * <p><b>Activation:</b> set {@code SPIFFE_ENABLED=true} (env var).
 * When disabled, outbound calls proceed without a workload identity token.
 *
 * <p><b>What it validates:</b>
 * <ul>
 *   <li>JWT signature — verified against SPIRE trust bundle (via Workload API)
 *   <li>Expiry — rejected if {@code exp} is in the past
 *   <li>Audience — must equal {@code spiffe://<trustDomain>/dmz-proxy}
 *   <li>Subject — must start with {@code spiffe://<trustDomain>/} (same trust domain)
 * </ul>
 */
@Slf4j
@Component
public class SpiffeProxyAuth {

    @Value("${spiffe.enabled:false}")
    private boolean enabled;

    @Value("${spiffe.socket:unix:/run/spire/sockets/agent.sock}")
    private String socketPath;

    @Value("${spiffe.trust-domain:filetransfer.io}")
    private String trustDomain;

    private static final String SERVICE_NAME = "dmz-proxy";

    private JwtSource jwtSource;
    private final AtomicBoolean available = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[SPIFFE] DMZ Proxy: SPIFFE disabled — outbound calls will have no workload identity token");
            return;
        }
        try {
            JwtSourceOptions options = JwtSourceOptions.builder()
                    .spiffeSocketPath(socketPath)
                    .initTimeout(Duration.ofSeconds(10))
                    .build();
            jwtSource = DefaultJwtSource.newSource(options);
            available.set(true);
            log.info("[SPIFFE] DMZ Proxy connected to Workload API — identity=spiffe://{}/{}",
                    trustDomain, SERVICE_NAME);
        } catch (Exception ex) {
            log.warn("[SPIFFE] DMZ Proxy: Workload API unavailable ({}). " +
                    "Outbound calls will proceed without a workload identity token. Error: {}", socketPath, ex.getMessage());
        }
    }

    /** True iff SPIFFE is enabled and the SPIRE agent socket is reachable. */
    public boolean isAvailable() {
        return available.get() && jwtSource != null;
    }

    /**
     * Validates an inbound Bearer token as a SPIFFE JWT-SVID.
     *
     * @param bearerToken raw JWT string (without "Bearer " prefix)
     * @return true iff the token is a valid, unexpired JWT-SVID for this proxy
     */
    public boolean validate(String bearerToken) {
        if (!isAvailable() || bearerToken == null) return false;
        try {
            String selfAudience = "spiffe://" + trustDomain + "/" + SERVICE_NAME;
            // JwtSource extends BundleSource<JwtBundle> — pass directly
            JwtSvid svid = JwtSvid.parseAndValidate(bearerToken, jwtSource, Set.of(selfAudience));
            // Extra check: caller must be in the same trust domain
            String callerTrustDomain = svid.getSpiffeId().getTrustDomain().getName();
            if (!trustDomain.equals(callerTrustDomain)) {
                log.warn("[SPIFFE] DMZ Proxy: rejected token from foreign trust domain '{}'",
                        callerTrustDomain);
                return false;
            }
            log.debug("[SPIFFE] DMZ Proxy: accepted call from {}", svid.getSpiffeId());
            return true;
        } catch (Exception ex) {
            log.debug("[SPIFFE] DMZ Proxy: JWT-SVID validation failed — {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Fetches a JWT-SVID to authenticate an outbound call from this proxy to
     * {@code targetServiceName} (e.g. "ai-engine", "screening-service").
     *
     * @return Bearer token string, or null if SPIFFE is unavailable
     */
    public String getJwtSvidFor(String targetServiceName) {
        if (!isAvailable()) return null;
        try {
            String audience = "spiffe://" + trustDomain + "/" + targetServiceName;
            JwtSvid svid = jwtSource.fetchJwtSvid(audience);
            return svid.getToken();
        } catch (Exception ex) {
            log.warn("[SPIFFE] DMZ Proxy: Failed to fetch JWT-SVID for '{}': {}",
                    targetServiceName, ex.getMessage());
            return null;
        }
    }

    /**
     * Extracts the caller's SPIFFE ID from a token (call only after {@link #validate}).
     */
    public String getCallerId(String bearerToken) {
        if (bearerToken == null) return null;
        try {
            String selfAudience = "spiffe://" + trustDomain + "/" + SERVICE_NAME;
            JwtSvid svid = JwtSvid.parseInsecure(bearerToken, Set.of(selfAudience));
            return svid.getSpiffeId().toString();
        } catch (Exception ex) {
            return null;
        }
    }

    @PreDestroy
    public void close() {
        if (jwtSource != null) {
            try { jwtSource.close(); } catch (Exception ignored) {}
        }
    }
}
