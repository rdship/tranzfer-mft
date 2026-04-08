package com.filetransfer.shared.spiffe;

import io.spiffe.svid.jwtsvid.JwtSvid;
import io.spiffe.workloadapi.DefaultJwtSource;
import io.spiffe.workloadapi.JwtSource;
import io.spiffe.workloadapi.JwtSourceOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SPIFFE Workload API client for issuing and validating JWT-SVIDs.
 *
 * <p><strong>Outbound calls (service → service):</strong>
 * {@code getJwtSvidFor("target-service")} returns a short-lived (1h) JWT.
 * Put it in the {@code Authorization: Bearer <token>} header.
 * The JWT auto-rotates — always call this per-request, never cache the token string.
 *
 * <p><strong>Inbound validation:</strong>
 * {@code validate(token, selfSpiffeId)} verifies signature via the SPIRE trust
 * bundle and checks audience. Returns true iff the token is valid and unexpired.
 *
 * <p><strong>Caller identity:</strong>
 * {@code getCallerId(token)} extracts the {@code sub} claim from a validated token,
 * e.g. {@code "spiffe://filetransfer.io/gateway-service"}.
 *
 * <p>Graceful degradation: if the SPIRE agent socket is unavailable, methods
 * return {@code null} / {@code false} and the caller proceeds without a workload identity token.
 */
@Slf4j
public class SpiffeWorkloadClient {

    private final SpiffeProperties props;
    private final JwtSource jwtSource;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public SpiffeWorkloadClient(SpiffeProperties props) {
        this.props = props;
        JwtSource source = null;
        try {
            JwtSourceOptions options = JwtSourceOptions.builder()
                    .spiffeSocketPath(props.getSocket())
                    .initTimeout(Duration.ofMillis(props.getInitTimeoutMs()))
                    .build();
            source = DefaultJwtSource.newSource(options);
            log.info("[SPIFFE] Workload API connected — socket={} identity={}",
                    props.getSocket(), props.selfSpiffeId());
        } catch (Exception ex) {
            available.set(false);
            log.warn("[SPIFFE] Workload API unavailable ({}). " +
                    "Outbound calls will proceed without a workload identity token. Error: {}", props.getSocket(), ex.getMessage());
        }
        this.jwtSource = source;
    }

    /** True iff the SPIRE agent socket is reachable. */
    public boolean isAvailable() {
        return available.get() && jwtSource != null;
    }

    /** This service's full SPIFFE ID, e.g. {@code spiffe://filetransfer.io/gateway-service}. */
    public String getSelfSpiffeId() {
        return props.selfSpiffeId();
    }

    /**
     * Fetch a JWT-SVID token to authenticate a call to {@code targetServiceName}.
     * The audience is {@code spiffe://<trustDomain>/<targetServiceName>}.
     * Returns {@code null} if SPIRE is unavailable.
     */
    public String getJwtSvidFor(String targetServiceName) {
        if (!isAvailable()) return null;
        try {
            String audience = props.audienceFor(targetServiceName);
            JwtSvid svid = jwtSource.fetchJwtSvid(audience); // String + varargs
            return svid.getToken();
        } catch (Exception ex) {
            log.warn("[SPIFFE] Failed to fetch JWT-SVID for {}: {}", targetServiceName, ex.getMessage());
            return null;
        }
    }

    /**
     * Validate an inbound JWT-SVID Bearer token.
     *
     * @param token       raw JWT string from Authorization header
     * @param selfSpiffeId this service's SPIFFE ID (used as expected audience)
     * @return true iff signature, expiry, and audience are all valid
     */
    public boolean validate(String token, String selfSpiffeId) {
        if (!isAvailable() || token == null) return false;
        try {
            // JwtSource extends BundleSource<JwtBundle> — pass directly
            JwtSvid.parseAndValidate(token, jwtSource, Set.of(selfSpiffeId));
            return true;
        } catch (Exception ex) {
            log.debug("[SPIFFE] JWT-SVID validation failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Extract the caller's SPIFFE ID from a (already-validated) JWT-SVID token.
     * Returns null if the token cannot be parsed.
     */
    public String getCallerId(String token) {
        if (token == null) return null;
        try {
            // parseInsecure skips signature check — only call this AFTER validate()
            JwtSvid svid = JwtSvid.parseInsecure(token, Set.of(props.selfSpiffeId()));
            return svid.getSpiffeId().toString();
        } catch (Exception ex) {
            log.debug("[SPIFFE] Could not extract caller ID: {}", ex.getMessage());
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
