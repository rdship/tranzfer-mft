package com.filetransfer.shared.spiffe;

import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.workloadapi.DefaultX509Source;
import io.spiffe.workloadapi.X509Source;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages SPIFFE X.509-SVIDs for mutual TLS (mTLS) transport.
 *
 * <p>Creates a {@link DefaultX509Source} that connects to the SPIRE agent Workload API
 * and receives X.509 certificate updates via <em>streaming push</em>. Certificates
 * auto-rotate without polling or per-request SPIRE calls — SPIRE pushes new SVIDs to
 * the agent at 50% of their TTL, and {@link DefaultX509Source} keeps the in-memory
 * key material current transparently.
 *
 * <p>The {@link SSLContext} from {@link #createSslContext()} is built via
 * {@link SpiffeSslContextFactory}, which uses:
 * <ul>
 *   <li>{@code SpiffeKeyManager} — always serves the current SVID; auto-rotates in-place.
 *   <li>{@code SpiffeTrustManager} — validates peers against the live SPIFFE trust bundle.
 * </ul>
 *
 * <p>Attaching this SSLContext to an HTTP client (see {@code SharedConfig}) eliminates
 * all SPIRE calls from the outbound request path — identity lives in the TLS channel,
 * not in a JWT header. Compared to Phase 1 (JWT-SVID cache), Phase 2 removes even the
 * ~1 ms cold-path fetch and provides cryptographic binding of identity to the transport.
 *
 * <p>Graceful degradation: if the SPIRE agent socket is unreachable at startup,
 * {@link #isAvailable()} returns {@code false} and callers fall back to JWT-SVIDs.
 *
 * <p>Activated only when {@code spiffe.enabled=true} and {@code spiffe.mtls-enabled=true}.
 */
@Slf4j
public class SpiffeX509Manager {

    private final AtomicBoolean available = new AtomicBoolean(false);
    private X509Source x509Source;

    public SpiffeX509Manager(SpiffeProperties props) {
        // R117: runtime gate — the bean is now unconditionally registered by
        // SpiffeAutoConfiguration (AOT can't evaluate @ConditionalOnProperty
        // correctly at build time). When either spiffe.enabled or
        // spiffe.mtls-enabled is false, skip the workload-API dial and stay
        // isAvailable()=false. SharedConfig falls back to JWT-SVID gracefully.
        if (!props.isEnabled() || !props.isMtlsEnabled()) {
            log.debug("[SPIFFE] X.509 source not activated — enabled={}, mtlsEnabled={}",
                    props.isEnabled(), props.isMtlsEnabled());
            return;
        }
        try {
            // X509SourceOptions is a static nested class of DefaultX509Source
            DefaultX509Source.X509SourceOptions options = DefaultX509Source.X509SourceOptions.builder()
                    .spiffeSocketPath(props.getSocket())
                    .initTimeout(Duration.ofMillis(props.getInitTimeoutMs()))
                    .build();
            this.x509Source = DefaultX509Source.newSource(options);
            this.available.set(true);
            log.info("[SPIFFE] X.509 source connected — socket={} identity={}",
                    props.getSocket(), props.selfSpiffeId());
        } catch (Exception ex) {
            log.warn("[SPIFFE] X.509 source unavailable — mTLS disabled, falling back to JWT-SVID. " +
                    "Error: {}", ex.getMessage());
        }
    }

    /** True iff the SPIRE X.509 Workload API is reachable. */
    public boolean isAvailable() {
        return available.get() && x509Source != null;
    }

    /**
     * Create a thread-safe {@link SSLContext} for mTLS via {@link SpiffeSslContextFactory}.
     *
     * <p>The returned context delegates live rotation to the underlying {@link X509Source}:
     * on every TLS handshake the key manager fetches the current cert from the already-in-memory
     * source — zero SPIRE agent I/O on the hot path.
     * Reuse this context across all connections (it is stateless and thread-safe).
     *
     * <p>Accepts any SPIFFE ID from the registered trust domain — application-level
     * authorization (ROLE_INTERNAL) is enforced by {@code PlatformJwtAuthFilter}.
     *
     * @throws IllegalStateException if the X.509 source is unavailable or SSLContext init fails
     */
    public SSLContext createSslContext() {
        if (!isAvailable()) {
            throw new IllegalStateException("[SPIFFE] X.509 source not available — cannot build mTLS SSLContext");
        }
        try {
            SpiffeSslContextFactory.SslContextOptions sslOptions =
                    SpiffeSslContextFactory.SslContextOptions.builder()
                            .x509Source(x509Source)
                            .acceptAnySpiffeId()     // authorization via ROLE_INTERNAL, not SPIFFE allowlist
                            .build();
            return SpiffeSslContextFactory.getSslContext(sslOptions);
        } catch (Exception ex) {
            throw new IllegalStateException("[SPIFFE] Failed to build mTLS SSLContext: " + ex.getMessage(), ex);
        }
    }

    /** The underlying streaming X.509 source (for advanced consumers). */
    public X509Source getX509Source() {
        return x509Source;
    }

    @PreDestroy
    public void close() {
        if (x509Source != null) {
            try { x509Source.close(); } catch (Exception ignored) {}
        }
    }
}
