package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.security.SpiffeProxyAuth;
import com.filetransfer.dmz.tls.KeystoreIntegration;
import com.filetransfer.dmz.tls.TlsTerminator.TlsConfig;
import com.filetransfer.tunnel.control.ControlMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tunnel-aware keystore integration that fetches certificates from keystore-manager:8093
 * through the DMZ tunnel instead of direct cross-zone HTTP.
 * <p>
 * Overrides {@link #fetchCertificate(String)} since the parent's {@code fetchFromKeystoreManager}
 * is private and cannot be overridden. The fallback chain is preserved:
 * <ol>
 *   <li>Fetch via tunnel CONTROL_REQ to keystore-manager</li>
 *   <li>Local disk cache (from last successful fetch)</li>
 *   <li>In-memory cached config</li>
 * </ol>
 * <p>
 * Certificate data is cached to disk exactly as the parent does, so disk-cache fallback
 * remains functional if the tunnel goes down between refreshes.
 */
@Slf4j
public class TunnelKeystoreIntegration extends KeystoreIntegration {

    private static final long FETCH_TIMEOUT_MS = 15_000;

    private final TunnelAcceptor tunnelAcceptor;
    private final SpiffeProxyAuth spiffeAuth;
    private final Path certCacheDir;
    private final long refreshIntervalSeconds;

    // Track aliases we've fetched so the refresh scheduler knows what to refresh
    private final java.util.Set<String> knownAliases = ConcurrentHashMap.newKeySet();

    private volatile ScheduledExecutorService tunnelRefreshScheduler;
    private volatile boolean tunnelRefreshing;

    /**
     * @param keystoreManagerUrl     base URL (used by parent for identification only)
     * @param spiffeAuth             SPIFFE auth helper for outbound JWT-SVID headers
     * @param certCacheDir           local directory for caching fetched certificates
     * @param refreshIntervalSeconds interval between certificate refresh checks
     * @param tunnelAcceptor         the tunnel acceptor (handler resolved lazily)
     */
    public TunnelKeystoreIntegration(String keystoreManagerUrl, SpiffeProxyAuth spiffeAuth,
                                      String certCacheDir, long refreshIntervalSeconds,
                                      TunnelAcceptor tunnelAcceptor) {
        super(keystoreManagerUrl, spiffeAuth, certCacheDir, refreshIntervalSeconds);
        this.tunnelAcceptor = tunnelAcceptor;
        this.spiffeAuth = spiffeAuth;
        this.certCacheDir = Path.of(certCacheDir != null ? certCacheDir : "./cert-cache");
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    // ── Lifecycle (override parent to use tunnel-aware refresh) ──────────

    /**
     * Overrides parent's start() to prevent the background scheduler from using
     * the parent's private fetchFromKeystoreManager() (direct HTTP).
     * Instead, our scheduler calls the public fetchCertificate() which IS
     * tunnel-aware via our override.
     */
    @Override
    public void start() {
        if (tunnelRefreshing) {
            log.debug("TunnelKeystoreIntegration already running");
            return;
        }
        tunnelRefreshing = true;

        tunnelRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tunnel-keystore-refresh");
            t.setDaemon(true);
            return t;
        });

        tunnelRefreshScheduler.scheduleAtFixedRate(
                this::tunnelRefreshAllCertificates, 0, refreshIntervalSeconds, TimeUnit.SECONDS);

        log.info("TunnelKeystoreIntegration started — refreshInterval={}s, cacheDir={}",
                refreshIntervalSeconds, certCacheDir.toAbsolutePath());
    }

    @Override
    public void shutdown() {
        tunnelRefreshing = false;
        if (tunnelRefreshScheduler != null) {
            tunnelRefreshScheduler.shutdown();
            try {
                if (!tunnelRefreshScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    tunnelRefreshScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                tunnelRefreshScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            tunnelRefreshScheduler = null;
        }
        log.info("TunnelKeystoreIntegration stopped");
    }

    /**
     * Tunnel-aware refresh: calls the public fetchCertificate() which routes
     * through the tunnel, falling back to disk/memory cache on tunnel failure.
     */
    private void tunnelRefreshAllCertificates() {
        if (!tunnelRefreshing) return;

        if (knownAliases.isEmpty()) {
            log.trace("No cached certificate aliases to refresh via tunnel");
            return;
        }

        for (String alias : knownAliases) {
            try {
                fetchCertificate(alias); // tunnel-aware override
            } catch (Exception e) {
                log.warn("Tunnel certificate refresh failed for alias '{}': {}", alias, e.getMessage());
            }
        }
    }

    /**
     * Fetches a certificate by alias, routing through the tunnel instead of direct HTTP.
     * Falls back to parent's disk/memory cache on tunnel failure.
     */
    @Override
    public Optional<TlsConfig> fetchCertificate(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        knownAliases.add(alias);

        // 1. Try fetching via tunnel
        DmzTunnelHandler th = tunnelAcceptor.getHandler();
        if (th != null && th.isConnected()) {
            try {
                TlsConfig config = fetchViaTunnel(alias);
                if (config != null) {
                    return Optional.of(config);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch certificate '{}' via tunnel: {}", alias, e.getMessage());
            }
        } else {
            log.warn("Tunnel disconnected — cannot fetch certificate '{}' from Keystore Manager", alias);
        }

        // 2. Fallback to parent's disk-cache and in-memory cache logic
        return super.fetchCertificate(alias);
    }

    // ── Private ──────────────────────────────────────────────────────────

    /**
     * Sends a CONTROL_REQ through the tunnel to fetch certificate data from Keystore Manager,
     * then writes the PEM files to disk cache and returns a TlsConfig.
     *
     * @return TlsConfig pointing to cached PEM files, or null if the call fails
     */
    private TlsConfig fetchViaTunnel(String alias) throws Exception {
        ControlMessage request = ControlMessage.request(
                UUID.randomUUID().toString(),
                "GET",
                "/api/keystore/entries/" + alias + "/export",
                keystoreHeaders(),
                null
        );

        DmzTunnelHandler handler = tunnelAcceptor.getHandler();
        if (handler == null) throw new IllegalStateException("Tunnel handler not available");
        ControlMessage response = handler
                .sendControlRequest(request, FETCH_TIMEOUT_MS)
                .orTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();

        if (response.getStatusCode() != 200 || response.getBody() == null) {
            log.warn("Keystore Manager returned {} via tunnel for alias '{}'",
                    response.getStatusCode(), alias);
            return null;
        }

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        return parsePemAndCache(alias, body);
    }

    /**
     * Parses the PEM JSON response and writes cert + key to disk cache.
     * Mirrors the parent's private parsePemResponse() logic.
     */
    private TlsConfig parsePemAndCache(String alias, String responseBody) {
        try {
            String certPem = extractJsonValue(responseBody, "certificate");
            String keyPem = extractJsonValue(responseBody, "privateKey");
            String chainPem = extractJsonValue(responseBody, "chain");

            if (certPem == null || keyPem == null) {
                log.warn("Tunnel response for '{}' missing certificate or privateKey", alias);
                return null;
            }

            String fullCertPem = chainPem != null ? certPem + "\n" + chainPem : certPem;

            Files.createDirectories(certCacheDir);
            Path certFile = certCacheDir.resolve(alias + "-cert.pem");
            Path keyFile = certCacheDir.resolve(alias + "-key.pem");

            Files.writeString(certFile, fullCertPem,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(keyFile, keyPem,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Restrict key file permissions (best effort)
            try {
                keyFile.toFile().setReadable(false, false);
                keyFile.toFile().setReadable(true, true);
                keyFile.toFile().setWritable(false, false);
                keyFile.toFile().setWritable(true, true);
            } catch (SecurityException e) {
                log.trace("Cannot restrict key file permissions: {}", e.getMessage());
            }

            log.debug("Cached certificate for alias '{}' via tunnel at {}", alias, certFile);

            return new TlsConfig(
                    true,
                    certFile.toAbsolutePath().toString(),
                    keyFile.toAbsolutePath().toString(),
                    null,
                    null,
                    false,
                    "TLSv1.2",
                    List.of(),
                    false,
                    3600,
                    10000
            );
        } catch (IOException e) {
            log.warn("Failed to cache certificate for alias '{}' from tunnel: {}", alias, e.getMessage());
            return null;
        }
    }

    private Map<String, String> keystoreHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        if (spiffeAuth != null && spiffeAuth.isAvailable()) {
            String token = spiffeAuth.getJwtSvidFor("keystore-manager");
            if (token != null) h.put("Authorization", "Bearer " + token);
        }
        h.put("Accept", "application/json");
        return h;
    }

    /**
     * Extracts a string value from a simple JSON object (handles escaped chars in PEM data).
     * Mirrors parent's private extractJsonValue().
     */
    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart < 0) return null;

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    default -> { value.append('\\'); value.append(c); }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }
}
