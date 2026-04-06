package com.filetransfer.dmz.tls;

import com.filetransfer.dmz.tls.TlsTerminator.TlsConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Integrates with the platform's Keystore Manager service (port 8093) to fetch
 * TLS certificates and refresh them periodically.
 *
 * <p>The Keystore Manager is the single source of truth for all certificates in the
 * platform. This class fetches certificates by alias, caches them locally on disk,
 * and triggers TLS context reloads when certificates change.</p>
 *
 * <h3>Fallback chain (proxy must never crash):</h3>
 * <ol>
 *   <li>Fetch from Keystore Manager REST API</li>
 *   <li>Use local disk cache (from last successful fetch)</li>
 *   <li>Use statically configured file paths ({@link TlsConfig#certPath()})</li>
 * </ol>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * KeystoreIntegration ki = new KeystoreIntegration(
 *     "http://keystore-manager:8093", "my-api-key", "./cert-cache", 3600);
 * ki.setOnCertificateUpdated(alias -> log.info("Cert updated: {}", alias));
 * ki.start();
 *
 * // Fetch and configure a TLS terminator
 * ki.configureTerminator(terminator, "server-cert", "sftp-external");
 *
 * // On shutdown
 * ki.shutdown();
 * }</pre>
 *
 * @see TlsTerminator
 */
@Slf4j
public class KeystoreIntegration {

    // ── Configuration ─────────────────────────────────────────────────────

    private final String keystoreManagerUrl;
    private final String internalApiKey;
    private final Path certCacheDir;
    private final long refreshIntervalSeconds;

    // ── State ─────────────────────────────────────────────────────────────

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, String> certFingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TlsConfig> cachedConfigs = new ConcurrentHashMap<>();
    private volatile Consumer<String> onCertificateUpdated;
    private volatile boolean running;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a new Keystore Manager integration.
     *
     * @param keystoreManagerUrl   base URL of the Keystore Manager service
     *                             (default: {@code "http://keystore-manager:8093"})
     * @param internalApiKey       value for the {@code X-Internal-Key} header
     * @param certCacheDir         local directory for caching fetched certificates
     *                             (default: {@code "./cert-cache"})
     * @param refreshIntervalSeconds interval between certificate refresh checks in seconds
     *                               (default: 3600)
     */
    public KeystoreIntegration(String keystoreManagerUrl, String internalApiKey,
                               String certCacheDir, long refreshIntervalSeconds) {
        this.keystoreManagerUrl = keystoreManagerUrl != null ? keystoreManagerUrl
                : "http://keystore-manager:8093";
        this.internalApiKey = Objects.requireNonNull(internalApiKey, "internalApiKey must not be null");
        this.certCacheDir = Path.of(certCacheDir != null ? certCacheDir : "./cert-cache");
        this.refreshIntervalSeconds = refreshIntervalSeconds > 0 ? refreshIntervalSeconds : 3600;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keystore-refresh");
            t.setDaemon(true);
            return t;
        });

        // Ensure cache directory exists
        try {
            Files.createDirectories(this.certCacheDir);
            log.debug("Certificate cache directory: {}", this.certCacheDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Cannot create cert cache directory {}: {} — caching disabled",
                    this.certCacheDir, e.getMessage());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Starts the periodic certificate refresh scheduler.
     *
     * <p>An initial refresh runs immediately, then repeats at the configured interval.
     * Refresh failures are logged but never propagate — the proxy remains operational.</p>
     */
    public void start() {
        if (running) {
            log.debug("Keystore integration already running — ignoring start()");
            return;
        }
        running = true;

        scheduler.scheduleAtFixedRate(this::refreshAllCertificates,
                0, refreshIntervalSeconds, TimeUnit.SECONDS);

        log.info("Keystore integration started — url={}, refreshInterval={}s, cacheDir={}",
                keystoreManagerUrl, refreshIntervalSeconds, certCacheDir.toAbsolutePath());
    }

    /**
     * Shuts down the refresh scheduler gracefully. Waits up to 5 seconds for
     * in-flight refresh operations to complete.
     */
    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Keystore integration shut down");
    }

    // ── Certificate fetching ──────────────────────────────────────────────

    /**
     * Fetches a certificate and private key by alias from the Keystore Manager.
     *
     * <p>The response is expected to contain PEM-encoded certificate and key data.
     * The files are saved to the local cache directory and a {@link TlsConfig} is
     * returned pointing to those cached files.</p>
     *
     * <p><strong>Fallback chain:</strong> If the Keystore Manager is unreachable,
     * attempts to use locally cached files. Returns empty only if no cached data exists.</p>
     *
     * @param alias the certificate alias in the Keystore Manager
     * @return a {@link TlsConfig} pointing to the cached certificate and key files,
     *         or empty if the certificate cannot be fetched and no cache exists
     */
    public Optional<TlsConfig> fetchCertificate(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");

        // 1. Try fetching from Keystore Manager
        try {
            TlsConfig config = fetchFromKeystoreManager(alias);
            if (config != null) {
                cachedConfigs.put(alias, config);
                return Optional.of(config);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch certificate '{}' from Keystore Manager: {}", alias, e.getMessage());
        }

        // 2. Fallback: local cache
        TlsConfig cached = loadFromLocalCache(alias);
        if (cached != null) {
            log.info("Using locally cached certificate for alias '{}'", alias);
            cachedConfigs.put(alias, cached);
            return Optional.of(cached);
        }

        // 3. Fallback: previously fetched config in memory
        TlsConfig inMemory = cachedConfigs.get(alias);
        if (inMemory != null) {
            log.info("Using in-memory cached config for alias '{}'", alias);
            return Optional.of(inMemory);
        }

        log.warn("No certificate available for alias '{}' — Keystore Manager down and no local cache", alias);
        return Optional.empty();
    }

    // ── Integration with TlsTerminator ────────────────────────────────────

    /**
     * Fetches a certificate by alias and configures it on the given {@link TlsTerminator}.
     *
     * <p>If the certificate is successfully fetched, a new {@link TlsConfig} is built
     * and passed to the terminator via {@link TlsTerminator#reloadCertificates(String, TlsConfig)}.
     * On failure, logs a warning but does not throw — the proxy continues with its
     * existing configuration.</p>
     *
     * @param terminator  the TLS terminator to configure
     * @param alias       the certificate alias in the Keystore Manager
     * @param mappingName the proxy mapping name (used for per-mapping cert registration)
     */
    public void configureTerminator(TlsTerminator terminator, String alias, String mappingName) {
        Objects.requireNonNull(terminator, "terminator must not be null");
        Objects.requireNonNull(alias, "alias must not be null");
        Objects.requireNonNull(mappingName, "mappingName must not be null");

        try {
            Optional<TlsConfig> config = fetchCertificate(alias);
            if (config.isPresent()) {
                terminator.reloadCertificates(mappingName, config.get());
                log.info("Configured TLS terminator for mapping [{}] with alias '{}'", mappingName, alias);
            } else {
                log.warn("Cannot configure TLS for mapping [{}] — certificate '{}' unavailable, "
                        + "terminator retains existing config", mappingName, alias);
            }
        } catch (Exception e) {
            log.error("Failed to configure TLS terminator for mapping [{}] with alias '{}': {}",
                    mappingName, alias, e.getMessage());
            // Never crash — proxy stays operational with existing cert
        }
    }

    // ── Callback ──────────────────────────────────────────────────────────

    /**
     * Registers a callback that fires when a certificate is updated during periodic refresh.
     *
     * @param callback consumer that receives the alias of the updated certificate
     */
    public void setOnCertificateUpdated(Consumer<String> callback) {
        this.onCertificateUpdated = callback;
    }

    // ── Private: Keystore Manager communication ───────────────────────────

    /**
     * Fetches certificate data from the Keystore Manager REST API.
     *
     * @return a TlsConfig with cached file paths, or null if the call fails
     */
    private TlsConfig fetchFromKeystoreManager(String alias) throws IOException, InterruptedException {
        String url = keystoreManagerUrl + "/api/keystore/entries/" + alias + "/export";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Internal-Key", internalApiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Keystore Manager returned {} for alias '{}': {}",
                    response.statusCode(), alias, truncateBody(response.body()));
            return null;
        }

        String body = response.body();
        return parsePemResponse(alias, body);
    }

    /**
     * Parses the PEM response from Keystore Manager and saves cert + key to local cache.
     *
     * <p>Expected response format (JSON with PEM strings):
     * <pre>{@code
     * {
     *   "certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
     *   "privateKey": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
     *   "chain": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----"
     * }
     * }</pre>
     */
    private TlsConfig parsePemResponse(String alias, String responseBody) {
        try {
            // Simple JSON parsing without Jackson — DMZ proxy stays minimal
            String certPem = extractJsonValue(responseBody, "certificate");
            String keyPem = extractJsonValue(responseBody, "privateKey");
            String chainPem = extractJsonValue(responseBody, "chain");

            if (certPem == null || keyPem == null) {
                log.warn("Keystore Manager response for '{}' missing certificate or privateKey", alias);
                return null;
            }

            // Combine cert + chain for full chain PEM
            String fullCertPem = chainPem != null ? certPem + "\n" + chainPem : certPem;

            // Save to local cache
            Path certFile = certCacheDir.resolve(alias + "-cert.pem");
            Path keyFile = certCacheDir.resolve(alias + "-key.pem");

            Files.writeString(certFile, fullCertPem,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(keyFile, keyPem,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Restrict key file permissions (best effort — may not work on all OS)
            try {
                keyFile.toFile().setReadable(false, false);
                keyFile.toFile().setReadable(true, true);
                keyFile.toFile().setWritable(false, false);
                keyFile.toFile().setWritable(true, true);
            } catch (SecurityException e) {
                log.trace("Cannot restrict key file permissions: {}", e.getMessage());
            }

            log.debug("Cached certificate for alias '{}' at {}", alias, certFile);

            return new TlsConfig(
                    true,
                    certFile.toAbsolutePath().toString(),
                    keyFile.toAbsolutePath().toString(),
                    null,    // no key password (Keystore Manager stores unencrypted PEM)
                    null,    // trust store managed separately
                    false,   // mTLS configured at proxy level, not per-alias
                    "TLSv1.2",
                    List.of(),
                    false,
                    3600,
                    10000
            );
        } catch (IOException e) {
            log.warn("Failed to cache certificate for alias '{}': {}", alias, e.getMessage());
            return null;
        }
    }

    // ── Private: local cache ──────────────────────────────────────────────

    /**
     * Loads a previously cached certificate from the local disk cache.
     *
     * @return a TlsConfig with cached file paths, or null if no cache exists
     */
    private TlsConfig loadFromLocalCache(String alias) {
        Path certFile = certCacheDir.resolve(alias + "-cert.pem");
        Path keyFile = certCacheDir.resolve(alias + "-key.pem");

        if (Files.exists(certFile) && Files.exists(keyFile)
                && Files.isReadable(certFile) && Files.isReadable(keyFile)) {
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
        }
        return null;
    }

    // ── Private: periodic refresh ─────────────────────────────────────────

    /**
     * Refreshes all known certificate aliases. Called periodically by the scheduler.
     * Errors are caught and logged — never propagated.
     */
    private void refreshAllCertificates() {
        if (!running) return;

        for (String alias : cachedConfigs.keySet()) {
            try {
                refreshSingleCertificate(alias);
            } catch (Exception e) {
                log.warn("Certificate refresh failed for alias '{}': {}", alias, e.getMessage());
                // Continue with next alias — never abort the entire refresh cycle
            }
        }
    }

    /**
     * Refreshes a single certificate: fetches from Keystore Manager, compares fingerprint,
     * and triggers the update callback if the cert has changed.
     */
    private void refreshSingleCertificate(String alias) {
        TlsConfig newConfig;
        try {
            newConfig = fetchFromKeystoreManager(alias);
        } catch (Exception e) {
            log.debug("Keystore Manager unavailable during refresh for '{}': {}", alias, e.getMessage());
            return;
        }

        if (newConfig == null) {
            return;
        }

        // Compare fingerprint to detect changes
        String newFingerprint = computeCertFingerprint(newConfig.certPath());
        if (newFingerprint == null) {
            return;
        }

        String previousFingerprint = certFingerprints.get(alias);
        if (newFingerprint.equals(previousFingerprint)) {
            log.trace("Certificate for alias '{}' unchanged — fingerprint={}", alias, newFingerprint);
            return;
        }

        // Certificate changed — update state and notify
        certFingerprints.put(alias, newFingerprint);
        cachedConfigs.put(alias, newConfig);

        log.info("Certificate updated for alias '{}' — new fingerprint={}", alias, newFingerprint);

        Consumer<String> callback = onCertificateUpdated;
        if (callback != null) {
            try {
                callback.accept(alias);
            } catch (Exception e) {
                log.warn("Certificate update callback failed for alias '{}': {}", alias, e.getMessage());
            }
        }
    }

    /**
     * Computes a SHA-256 fingerprint of the certificate file content.
     *
     * @return hex fingerprint string, or null on error
     */
    private String computeCertFingerprint(String certPath) {
        try {
            byte[] certBytes = Files.readAllBytes(Path.of(certPath));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certBytes);
            return bytesToHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            log.debug("Cannot compute fingerprint for {}: {}", certPath, e.getMessage());
            return null;
        }
    }

    // ── Private: JSON parsing (minimal, no external deps) ─────────────────

    /**
     * Extracts a string value from a JSON object by key.
     * Simple parser for flat JSON — avoids pulling in Jackson for this one use case.
     *
     * @param json the JSON string
     * @param key  the key to extract
     * @return the unescaped string value, or null if not found
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return null;

        // Find the colon after the key
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return null;

        // Find the opening quote of the value
        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart < 0) return null;

        // Find the closing quote (handle escaped quotes)
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

    /**
     * Converts a byte array to a lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Truncates a response body for logging — avoids dumping large PEM blobs into logs.
     */
    private static String truncateBody(String body) {
        if (body == null) return "<null>";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
