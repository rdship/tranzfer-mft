package com.filetransfer.shared.spiffe;

import io.spiffe.svid.jwtsvid.JwtSvid;
import io.spiffe.workloadapi.DefaultJwtSource;
import io.spiffe.workloadapi.JwtSource;
import io.spiffe.workloadapi.JwtSourceOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SPIFFE Workload API client for issuing and validating JWT-SVIDs.
 *
 * <p><strong>Outbound calls (service → service):</strong>
 * {@link #getJwtSvidFor(String)} returns a cached JWT-SVID for the given audience.
 * Tokens are cached in memory and proactively refreshed at 50% of their TTL
 * (± 5-minute per-instance jitter) to eliminate per-request SPIRE agent round-trips.
 * The hot path is always a ConcurrentHashMap lookup — O(1), zero I/O. The cold path
 * (first call per audience, or post-expiry miss) fetches synchronously and schedules
 * background renewal. With 20 known services, the cache holds at most 20 entries.
 *
 * <p><strong>Why cache?</strong> The SPIRE agent signs a new JWT on every
 * {@code FetchJWTSVID} call (SPIRE issue #4829, closed "not planned"). Without
 * application-level caching, N concurrent file transfers × M flow steps =
 * N×M serial gRPC round-trips to the agent socket, making SPIRE the throughput
 * bottleneck at scale.
 *
 * <p><strong>Inbound validation:</strong>
 * {@link #validate(String, String)} verifies signature via the SPIRE trust bundle
 * (streamed via Workload API) and checks audience. Returns true iff valid and unexpired.
 *
 * <p><strong>Caller identity:</strong>
 * {@link #getCallerId(String)} extracts the {@code sub} claim from a validated token,
 * e.g. {@code "spiffe://filetransfer.io/gateway-service"}.
 *
 * <p>Graceful degradation: if the SPIRE agent socket is unavailable, methods
 * return {@code null} / {@code false} and the caller proceeds without a workload
 * identity token.
 */
@Slf4j
public class SpiffeWorkloadClient {

    /**
     * Proactive refresh at 50% of token lifetime — the same fraction SPIRE uses
     * internally for X.509-SVID rotation (hardcoded in SPIRE issue #1754).
     */
    private static final double REFRESH_FRACTION = 0.50;

    /**
     * Per-instance jitter window (±5 min). Distributes renewal across instances
     * to prevent thundering-herd bursts toward the SPIRE server at TTL boundaries
     * (SPIRE issue #4268).
     */
    private static final long JITTER_RANGE_SECONDS = 300;

    /**
     * Treat the cached token as expired this many seconds before its actual {@code exp}
     * claim to absorb clock skew between caller and receiver.
     */
    private static final long GRACE_SECONDS = 60;

    /**
     * In-memory token cache keyed by SPIFFE audience string.
     * At most 20 entries (one per service in the platform).
     */
    private final ConcurrentHashMap<String, CachedSvid> tokenCache = new ConcurrentHashMap<>();

    /**
     * Virtual-thread-backed scheduler for proactive background renewal.
     * Virtual threads are ideal here: each renewal task blocks briefly on the
     * SPIRE agent gRPC socket without consuming a platform thread.
     */
    private final ScheduledExecutorService refresher =
            Executors.newScheduledThreadPool(2,
                    Thread.ofVirtual().name("spiffe-refresh-", 0).factory());

    private final SpiffeProperties props;
    /**
     * Volatile so the reconnect thread's write is immediately visible to request
     * threads without locking. Set once at startup on success, or by the background
     * reconnect thread once SPIRE becomes reachable.
     */
    private volatile JwtSource jwtSource;
    private final AtomicBoolean available = new AtomicBoolean(false);

    /**
     * R111: released the moment {@code available} first flips true. Hot-path
     * outbound callers block on this for a bounded interval so the first few
     * S2S calls after boot don't race the async init and go out header-less
     * to a SPIFFE-gated peer (→ 403). Once released, future
     * {@link #awaitAvailable(Duration)} calls return immediately.
     */
    private final CountDownLatch availableLatch = new CountDownLatch(1);

    /**
     * Background reconnect thread — runs only when the initial connection fails.
     * Retries every 15 seconds until SPIRE agent is available (e.g., after
     * {@code bash spire/bootstrap.sh} is run on a fresh install). Once connected,
     * the thread exits and the service self-heals without a restart.
     */
    private volatile Thread reconnectThread;

    public SpiffeWorkloadClient(SpiffeProperties props) {
        this.props = props;
        // R112: runtime gate. The bean is now always registered (AOT can't evaluate
        // @ConditionalOnProperty correctly at build time — see SpiffeAutoConfiguration
        // class doc). When spiffe.enabled=false we skip the workload-API dial and
        // release the latch so any awaitAvailable callers return false immediately
        // rather than waiting out the 5 s default.
        if (!props.isEnabled()) {
            log.info("[SPIFFE] spiffe.enabled=false — client created in disabled state "
                    + "(no workload-API dial; outbound S2S calls proceed without a JWT-SVID)");
            availableLatch.countDown();
            return;
        }
        // R109 (F5): boot-time optimization — always kick off the SPIRE workload-API
        // connect on a virtual thread so the synchronous gRPC dial never blocks
        // Spring context refresh. Before: every service waited up to initTimeoutMs
        // (typically 5 s) for the workload socket on cold boot where SPIRE agent
        // comes up in parallel. After: each service becomes HTTP-ready immediately
        // and self-heals to SPIFFE-enabled the moment the agent answers.
        Thread.ofVirtual().name("spiffe-init-" + (props.getServiceName() != null ? props.getServiceName() : "svc"))
                .start(this::initialConnectWithRetry);
    }

    /**
     * R109 (F5): unified initial-connect + retry loop. Runs on a virtual thread
     * started from the constructor, so boot is never blocked on SPIRE dial.
     * Exits as soon as the connection succeeds; if destroyed before success,
     * the interrupt cleanly halts the loop.
     */
    private void initialConnectWithRetry() {
        if (tryConnect()) {
            log.info("[SPIFFE] Workload API connected — socket={} identity={}",
                    props.getSocket(), props.selfSpiffeId());
            return;
        }
        log.warn("[SPIFFE] Workload API unavailable ({}). " +
                "Retrying every 15s — run `bash spire/bootstrap.sh` if this is a fresh install.",
                props.getSocket());
        reconnectThread = Thread.currentThread();
        reconnectLoop();
    }

    /** Attempt a single connection to the SPIRE Workload API. Returns true on success. */
    private boolean tryConnect() {
        try {
            JwtSourceOptions options = JwtSourceOptions.builder()
                    .spiffeSocketPath(props.getSocket())
                    .initTimeout(Duration.ofMillis(props.getInitTimeoutMs()))
                    .build();
            this.jwtSource = DefaultJwtSource.newSource(options);
            this.available.set(true);
            this.availableLatch.countDown();
            return true;
        } catch (Exception ex) {
            // R111: promoted from DEBUG → WARN. Previous silent-debug log level
            // hid R110's S2S 403 regression — failures now always visible in
            // docker logs so ops can diagnose SPIRE socket / agent issues without
            // enabling debug logging.
            log.warn("[SPIFFE] Workload API connection attempt failed: {}", ex.getMessage());
            return false;
        }
    }

    /** Background loop: retries every 15 s until SPIRE is reachable or the bean is destroyed. */
    private void reconnectLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (tryConnect()) {
                log.info("[SPIFFE] Workload API reconnected — socket={} identity={}",
                        props.getSocket(), props.selfSpiffeId());
                return; // success — exit the loop
            }
        }
    }

    /** True iff the SPIRE agent socket is reachable. */
    public boolean isAvailable() {
        return available.get() && jwtSource != null;
    }

    /**
     * R112: runtime feature-flag query. True when {@code spiffe.enabled=true}
     * was set in the service's runtime environment. Callers use this to
     * distinguish "SPIFFE intentionally off" (silent no-op expected) from
     * "SPIFFE on but not yet connected" (worth logging, may retry).
     */
    public boolean isEnabled() {
        return props != null && props.isEnabled();
    }

    /**
     * R111: block up to {@code timeout} for the async init to succeed. Returns
     * immediately once available. If the timeout elapses without availability,
     * returns false and the caller decides how to proceed (skip header, fail
     * the request, or fall back to another auth method).
     *
     * <p>Hot-path S2S callers use this to close the R109→R110 silent-fallback
     * gap: outbound calls made during the ~1–5 s window between Spring context
     * refresh and SPIRE agent handshake previously went out header-less and
     * got 403 from SPIFFE-gated peers. A 3–5 s bounded wait absorbs the race.
     */
    public boolean awaitAvailable(Duration timeout) {
        if (isAvailable()) return true;
        try {
            return availableLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    && isAvailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** This service's full SPIFFE ID, e.g. {@code spiffe://filetransfer.io/gateway-service}. */
    public String getSelfSpiffeId() {
        return props.selfSpiffeId();
    }

    /**
     * Return a JWT-SVID token for authenticating a call to {@code targetServiceName}.
     * The audience is {@code spiffe://<trustDomain>/<targetServiceName>}.
     *
     * <p><strong>Hot path (cache hit):</strong> O(1) hashmap lookup — zero I/O, sub-microsecond.
     * <p><strong>Cold path (first call or post-expiry):</strong> synchronous fetch from SPIRE agent
     * (~1–10 ms), stores result, schedules background proactive refresh.
     *
     * @return the raw JWT string, or {@code null} if SPIRE is unavailable
     */
    public String getJwtSvidFor(String targetServiceName) {
        if (!isAvailable()) return null;
        String audience = props.audienceFor(targetServiceName);
        CachedSvid cached = tokenCache.get(audience);
        if (cached != null && cached.isUsable()) {
            return cached.token;
        }
        return fetchAndCache(audience, targetServiceName);
    }

    private String fetchAndCache(String audience, String targetServiceName) {
        try {
            JwtSvid svid = jwtSource.fetchJwtSvid(audience);
            Instant expiresAt = svid.getExpiry().toInstant();
            CachedSvid entry = new CachedSvid(svid.getToken(), expiresAt);
            tokenCache.put(audience, entry);
            scheduleRefresh(audience, targetServiceName, expiresAt);
            log.debug("[SPIFFE] Cached JWT-SVID for audience={} expires={}", audience, expiresAt);
            return svid.getToken();
        } catch (Exception ex) {
            log.warn("[SPIFFE] Failed to fetch JWT-SVID for {}: {}", targetServiceName, ex.getMessage());
            return null;
        }
    }

    private void scheduleRefresh(String audience, String targetServiceName, Instant expiresAt) {
        long ttlMillis = Duration.between(Instant.now(), expiresAt).toMillis();
        long jitterMillis = ThreadLocalRandom.current()
                .nextLong(-JITTER_RANGE_SECONDS * 1000L, JITTER_RANGE_SECONDS * 1000L);
        long delayMillis = Math.max(1_000L, (long)(ttlMillis * REFRESH_FRACTION) + jitterMillis);
        refresher.schedule(() -> {
            log.debug("[SPIFFE] Proactive refresh — audience={}", audience);
            fetchAndCache(audience, targetServiceName);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Validate an inbound JWT-SVID Bearer token.
     *
     * @param token        raw JWT string from Authorization header
     * @param selfSpiffeId this service's SPIFFE ID (used as expected audience)
     * @return true iff signature, expiry, and audience are all valid
     */
    public boolean validate(String token, String selfSpiffeId) {
        if (!isAvailable() || token == null) return false;
        try {
            JwtSvid.parseAndValidate(token, jwtSource, Set.of(selfSpiffeId));
            return true;
        } catch (Exception ex) {
            // R132: promoted from DEBUG → WARN. The R131/R132 audit caught
            // repeat S2S 403s (BUG 1, 11, 12, 13) where the caller attached
            // a JWT-SVID but this side silently rejected it. The rejection
            // reason (audience mismatch, signature failure, expiry) was
            // invisible at INFO. Surface the expected audience + the library
            // message so ops can diagnose in one grep across all services,
            // without enabling debug logging on every service.
            log.warn("[SPIFFE] JWT-SVID rejected (expected audience={}): {}",
                    selfSpiffeId, ex.getMessage());
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
        if (reconnectThread != null) reconnectThread.interrupt();
        refresher.shutdownNow();
        if (jwtSource != null) {
            try { jwtSource.close(); } catch (Exception ignored) {}
        }
    }

    /** Immutable cache entry — token string + expiry instant. */
    private record CachedSvid(String token, Instant expiresAt) {
        /** True if the token is still valid with {@code GRACE_SECONDS} buffer. */
        boolean isUsable() {
            return Instant.now().isBefore(expiresAt.minusSeconds(GRACE_SECONDS));
        }
    }
}
