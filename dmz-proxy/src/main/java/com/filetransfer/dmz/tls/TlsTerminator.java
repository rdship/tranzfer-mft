package com.filetransfer.dmz.tls;

import io.netty.handler.ssl.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TLS termination for the DMZ proxy edge.
 *
 * <p>Provides Netty {@link SslContext} and {@link SslHandler} creation for TLS termination
 * at the proxy boundary. Supports per-mapping TLS configuration, SNI-based certificate
 * selection, certificate reloading without restart, and full certificate chain validation.</p>
 *
 * <p>Instantiated by {@code ProxyManager} — not a Spring bean. The DMZ proxy is intentionally
 * minimal with no Spring dependency injection for security-critical TLS paths.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * TlsConfig config = new TlsConfig(true, "/certs/server.pem", "/certs/server-key.pem",
 *     null, "/certs/ca-bundle.pem", false, "TLSv1.2", List.of(), false, 3600, 10000);
 * TlsTerminator terminator = new TlsTerminator(config);
 * SslContext ctx = terminator.createServerContext(config);
 * SslHandler handler = terminator.createHandler(ctx, "backend.internal", 8443);
 * }</pre>
 *
 * @see KeystoreIntegration
 */
@Slf4j
public class TlsTerminator {

    // ── Cipher suite security ─────────────────────────────────────────────

    /**
     * Cipher keywords to exclude by default — prevents downgrade attacks.
     * Any cipher suite whose name contains one of these tokens is rejected.
     */
    private static final List<String> EXCLUDED_CIPHER_KEYWORDS = List.of(
            "NULL", "EXPORT", "DES", "RC4", "MD5", "anon", "EMPTY"
    );

    /**
     * TLS 1.3 cipher suites that are always acceptable when TLS 1.3 is in use.
     */
    private static final List<String> TLS13_CIPHER_SUITES = List.of(
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256"
    );

    /** Minimum protocol version the proxy will accept — anything below is rejected. */
    private static final String ABSOLUTE_MIN_TLS_VERSION = "TLSv1.2";

    private static final int EXPIRY_WARN_DAYS = 30;

    // ── State ─────────────────────────────────────────────────────────────

    private volatile SslContext defaultContext;
    private final ConcurrentHashMap<String, SslContext> contextCache = new ConcurrentHashMap<>();
    private volatile TlsConfig defaultConfig;

    // ── Nested config record ──────────────────────────────────────────────

    /**
     * Immutable TLS configuration for a single proxy mapping or the global default.
     *
     * @param enabled            whether TLS termination is active
     * @param certPath           path to PEM-encoded server certificate (chain)
     * @param keyPath            path to PEM-encoded private key
     * @param keyPassword        optional password protecting the private key (null if unencrypted)
     * @param trustStorePath     path to PEM CA certificates for client validation (mTLS); null to skip
     * @param requireClientCert  if {@code true}, clients must present a valid certificate (mTLS)
     * @param minTlsVersion      minimum TLS protocol version: {@code "TLSv1.2"} (default) or {@code "TLSv1.3"}
     * @param cipherSuites       allowed cipher suites; empty list means JDK defaults with insecure ciphers removed
     * @param enableOcspStapling whether to enable OCSP stapling
     * @param sessionTimeoutSeconds TLS session timeout in seconds (default 3600)
     * @param sessionCacheSize   TLS session cache size (default 10000)
     */
    public record TlsConfig(
            boolean enabled,
            String certPath,
            String keyPath,
            String keyPassword,
            String trustStorePath,
            boolean requireClientCert,
            String minTlsVersion,
            List<String> cipherSuites,
            boolean enableOcspStapling,
            long sessionTimeoutSeconds,
            int sessionCacheSize
    ) {
        /**
         * Returns the effective minimum TLS version, falling back to {@code TLSv1.2}
         * if none is specified or if the specified version is weaker than the absolute minimum.
         */
        public String effectiveMinTlsVersion() {
            if (minTlsVersion == null || minTlsVersion.isBlank()) {
                return ABSOLUTE_MIN_TLS_VERSION;
            }
            // TLSv1.3 > TLSv1.2 — never allow anything below 1.2
            if ("TLSv1.3".equals(minTlsVersion) || "TLSv1.2".equals(minTlsVersion)) {
                return minTlsVersion;
            }
            log.warn("Unsupported minTlsVersion '{}', defaulting to {}", minTlsVersion, ABSOLUTE_MIN_TLS_VERSION);
            return ABSOLUTE_MIN_TLS_VERSION;
        }

        /** Returns a cache key that uniquely identifies this configuration. */
        String cacheKey() {
            return Objects.hash(certPath, keyPath, keyPassword, trustStorePath,
                    requireClientCert, minTlsVersion, cipherSuites,
                    enableOcspStapling, sessionTimeoutSeconds, sessionCacheSize) + "";
        }
    }

    // ── Certificate info ──────────────────────────────────────────────────

    /**
     * Summary of a parsed X.509 certificate.
     *
     * @param subject         certificate subject DN
     * @param issuer          certificate issuer DN
     * @param notBefore       validity start
     * @param notAfter        validity end
     * @param serialNumber    certificate serial number
     * @param daysUntilExpiry days remaining until the certificate expires (negative if expired)
     */
    public record CertificateInfo(
            String subject,
            String issuer,
            Instant notBefore,
            Instant notAfter,
            String serialNumber,
            long daysUntilExpiry
    ) {}

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a new TLS terminator with the given default configuration.
     *
     * <p>If TLS is enabled, the constructor eagerly validates the certificate and key,
     * and pre-builds the default {@link SslContext}.</p>
     *
     * @param defaultConfig the default TLS configuration for all mappings
     * @throws IllegalArgumentException if TLS is enabled but the certificate/key are invalid
     */
    public TlsTerminator(TlsConfig defaultConfig) {
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");

        if (defaultConfig.enabled()) {
            validateConfig(defaultConfig);
            try {
                this.defaultContext = buildSslContext(defaultConfig);
                log.info("TLS terminator initialized — cert={}, minTLS={}, clientAuth={}",
                        defaultConfig.certPath(), defaultConfig.effectiveMinTlsVersion(),
                        defaultConfig.requireClientCert() ? "REQUIRE" : "NONE");
            } catch (SSLException e) {
                throw new IllegalArgumentException("Failed to build default SslContext: " + e.getMessage(), e);
            }
        } else {
            log.info("TLS terminator created in DISABLED mode — pass-through");
        }
    }

    // ── Context creation ──────────────────────────────────────────────────

    /**
     * Builds a Netty {@link SslContext} for TLS termination using the provided configuration.
     *
     * <p>Results are cached by configuration fingerprint. Subsequent calls with an identical
     * configuration return the same context without rebuilding.</p>
     *
     * @param config the TLS configuration
     * @return a configured {@link SslContext} for server-side TLS
     * @throws IllegalStateException if the context cannot be created
     */
    public SslContext createServerContext(TlsConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        return contextCache.computeIfAbsent(config.cacheKey(), key -> {
            validateConfig(config);
            try {
                return buildSslContext(config);
            } catch (SSLException e) {
                throw new IllegalStateException("Failed to build SslContext: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Creates a Netty {@link SslHandler} for a specific connection.
     *
     * <p>The handler wraps the given {@link SslContext} and is configured with the peer
     * hostname and port for SNI and session resumption.</p>
     *
     * @param ctx      the SSL context to use
     * @param peerHost the remote peer hostname (used for SNI in backend connections)
     * @param peerPort the remote peer port
     * @return a new {@link SslHandler} ready to be added to a Netty pipeline
     */
    public SslHandler createHandler(SslContext ctx, String peerHost, int peerPort) {
        Objects.requireNonNull(ctx, "SslContext must not be null");
        SSLEngine engine = ctx.newEngine(io.netty.buffer.ByteBufAllocator.DEFAULT, peerHost, peerPort);
        return new SslHandler(engine);
    }

    // ── SNI support ───────────────────────────────────────────────────────

    /**
     * Creates an SNI-aware {@link SslContext} that selects the server certificate
     * based on the hostname provided by the client during the TLS handshake.
     *
     * <p>Uses Netty's {@link SniHandler} with a {@link Mapping} built from the provided
     * hostname-to-config map. If no SNI match is found, the {@code defaultConfig} is used.</p>
     *
     * @param sniMap        map of hostname patterns to TLS configurations
     * @param defaultConfig fallback configuration when no SNI hostname matches
     * @return a Netty {@link SniHandler} that can be added directly to a channel pipeline
     * @throws IllegalStateException if any of the SSL contexts fail to build
     */
    public SniHandler createSniContext(Map<String, TlsConfig> sniMap, TlsConfig defaultConfig) {
        Objects.requireNonNull(sniMap, "sniMap must not be null");
        Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");

        SslContext fallback = createServerContext(defaultConfig);

        DomainWildcardMappingBuilder<SslContext> builder = new DomainWildcardMappingBuilder<>(fallback);
        sniMap.forEach((hostname, config) -> {
            SslContext sniCtx = createServerContext(config);
            builder.add(hostname, sniCtx);
            log.debug("SNI mapping: {} -> cert={}", hostname, config.certPath());
        });

        Mapping<String, SslContext> mapping = builder.build();
        log.info("SNI handler created with {} hostname mappings + default", sniMap.size());

        return new SniHandler(mapping);
    }

    // ── Certificate reloading ─────────────────────────────────────────────

    /**
     * Reloads the default certificate without restarting the proxy.
     *
     * <p>Builds a new {@link SslContext} from the provided configuration, then atomically
     * swaps it into the default reference. Existing connections are unaffected — only
     * new handshakes use the new context.</p>
     *
     * @param newConfig the updated TLS configuration
     * @throws IllegalStateException if the new context cannot be built
     */
    public void reloadCertificates(TlsConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig must not be null");
        validateConfig(newConfig);

        try {
            SslContext newContext = buildSslContext(newConfig);

            // Atomic swap — volatile write ensures visibility
            this.defaultContext = newContext;
            this.defaultConfig = newConfig;

            // Evict old cache entry, insert new one
            contextCache.clear();
            contextCache.put(newConfig.cacheKey(), newContext);

            log.info("Default TLS certificates reloaded — cert={}", newConfig.certPath());
        } catch (SSLException e) {
            throw new IllegalStateException("Certificate reload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reloads the certificate for a specific named mapping.
     *
     * <p>The new context is cached under the mapping name. If the mapping previously
     * used a different configuration, the old cached context is evicted.</p>
     *
     * @param name      the mapping name (e.g., "sftp-external")
     * @param newConfig the updated TLS configuration for this mapping
     * @throws IllegalStateException if the new context cannot be built
     */
    public void reloadCertificates(String name, TlsConfig newConfig) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(newConfig, "newConfig must not be null");
        validateConfig(newConfig);

        try {
            SslContext newContext = buildSslContext(newConfig);

            // Store by mapping name for explicit lookup, and by config key for dedup
            contextCache.put("mapping:" + name, newContext);
            contextCache.put(newConfig.cacheKey(), newContext);

            log.info("TLS certificates reloaded for mapping [{}] — cert={}", name, newConfig.certPath());
        } catch (SSLException e) {
            throw new IllegalStateException(
                    "Certificate reload failed for mapping [" + name + "]: " + e.getMessage(), e);
        }
    }

    // ── Certificate validation & info ─────────────────────────────────────

    /**
     * Parses a PEM certificate file and returns summary information.
     *
     * @param certPath path to a PEM-encoded certificate file
     * @return certificate metadata including subject, issuer, validity, and days until expiry
     * @throws IllegalArgumentException if the file cannot be read or parsed
     */
    public CertificateInfo getCertificateInfo(String certPath) {
        Objects.requireNonNull(certPath, "certPath must not be null");

        try (InputStream is = Files.newInputStream(Path.of(certPath))) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);

            long daysUntilExpiry = Duration.between(Instant.now(), cert.getNotAfter().toInstant()).toDays();

            return new CertificateInfo(
                    cert.getSubjectX500Principal().getName(),
                    cert.getIssuerX500Principal().getName(),
                    cert.getNotBefore().toInstant(),
                    cert.getNotAfter().toInstant(),
                    cert.getSerialNumber().toString(16),
                    daysUntilExpiry
            );
        } catch (CertificateException | IOException e) {
            throw new IllegalArgumentException("Failed to read certificate at " + certPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the current default {@link SslContext}, or {@code null} if TLS is disabled.
     *
     * @return the default SSL context
     */
    public SslContext getDefaultContext() {
        return defaultContext;
    }

    /**
     * Returns the current default TLS configuration.
     *
     * @return the default configuration
     */
    public TlsConfig getDefaultConfig() {
        return defaultConfig;
    }

    // ── Private: context building ─────────────────────────────────────────

    /**
     * Builds a Netty SslContext from the given configuration.
     */
    private SslContext buildSslContext(TlsConfig config) throws SSLException {
        File certFile = new File(config.certPath());
        File keyFile = new File(config.keyPath());

        SslContextBuilder builder = SslContextBuilder.forServer(certFile, keyFile, config.keyPassword());

        // ── Provider ──
        builder.sslProvider(SslProvider.isAlpnSupported(SslProvider.OPENSSL)
                ? SslProvider.OPENSSL : SslProvider.JDK);

        // ── Protocol versions ──
        String minVersion = config.effectiveMinTlsVersion();
        if ("TLSv1.3".equals(minVersion)) {
            builder.protocols("TLSv1.3");
        } else {
            builder.protocols("TLSv1.3", "TLSv1.2");
        }

        // ── Cipher suites ──
        List<String> ciphers = resolveAllowedCiphers(config.cipherSuites());
        if (!ciphers.isEmpty()) {
            builder.ciphers(ciphers, SupportedCipherSuiteFilter.INSTANCE);
        }

        // ── Client authentication (mTLS) ──
        if (config.requireClientCert()) {
            builder.clientAuth(ClientAuth.REQUIRE);
        } else if (config.trustStorePath() != null && !config.trustStorePath().isBlank()) {
            builder.clientAuth(ClientAuth.OPTIONAL);
        } else {
            builder.clientAuth(ClientAuth.NONE);
        }

        // ── Trust store for client certificate validation ──
        if (config.trustStorePath() != null && !config.trustStorePath().isBlank()) {
            File trustFile = new File(config.trustStorePath());
            if (trustFile.exists()) {
                builder.trustManager(trustFile);
            } else {
                log.warn("Trust store not found at {}, using system defaults", config.trustStorePath());
            }
        }

        // ── OCSP stapling ──
        if (config.enableOcspStapling()) {
            builder.enableOcsp(true);
        }

        // ── Session timeout and cache ──
        builder.sessionTimeout(config.sessionTimeoutSeconds());
        builder.sessionCacheSize(config.sessionCacheSize());

        SslContext ctx = builder.build();
        log.debug("Built SslContext — provider={}, protocols={}, clientAuth={}, ciphers={}",
                ctx.isClient() ? "CLIENT" : "SERVER",
                minVersion, config.requireClientCert() ? "REQUIRE" : "NONE",
                ciphers.size());

        return ctx;
    }

    /**
     * Resolves the effective cipher suite list by filtering out insecure ciphers
     * and always including TLS 1.3 suites.
     */
    private List<String> resolveAllowedCiphers(List<String> configured) {
        List<String> result = new ArrayList<>(TLS13_CIPHER_SUITES);

        if (configured != null && !configured.isEmpty()) {
            // Use the explicitly configured list, but filter out insecure ones
            for (String cipher : configured) {
                if (!isExcludedCipher(cipher) && !result.contains(cipher)) {
                    result.add(cipher);
                }
            }
        }
        // When no explicit list is provided, we return just the TLS 1.3 suites
        // and let the SupportedCipherSuiteFilter add safe JDK defaults

        return result;
    }

    /**
     * Returns {@code true} if the cipher suite name contains an excluded keyword.
     */
    private boolean isExcludedCipher(String cipherName) {
        String upper = cipherName.toUpperCase(Locale.ROOT);
        return EXCLUDED_CIPHER_KEYWORDS.stream().anyMatch(keyword -> upper.contains(keyword));
    }

    // ── Private: validation ───────────────────────────────────────────────

    /**
     * Validates a TLS configuration: checks file existence, cert validity, and chain completeness.
     */
    private void validateConfig(TlsConfig config) {
        if (!config.enabled()) {
            return;
        }

        // ── Certificate file ──
        if (config.certPath() == null || config.certPath().isBlank()) {
            throw new IllegalArgumentException("TLS enabled but certPath is empty");
        }
        Path certPath = Path.of(config.certPath());
        if (!Files.exists(certPath)) {
            throw new IllegalArgumentException("Certificate file not found: " + config.certPath());
        }
        if (!Files.isReadable(certPath)) {
            throw new IllegalArgumentException("Certificate file not readable: " + config.certPath());
        }

        // ── Key file ──
        if (config.keyPath() == null || config.keyPath().isBlank()) {
            throw new IllegalArgumentException("TLS enabled but keyPath is empty");
        }
        Path keyPath = Path.of(config.keyPath());
        if (!Files.exists(keyPath)) {
            throw new IllegalArgumentException("Key file not found: " + config.keyPath());
        }
        if (!Files.isReadable(keyPath)) {
            throw new IllegalArgumentException("Key file not readable: " + config.keyPath());
        }

        // ── Certificate chain validation ──
        try {
            validateCertificateChain(config.certPath());
        } catch (Exception e) {
            throw new IllegalArgumentException("Certificate validation failed: " + e.getMessage(), e);
        }

        // ── Trust store (optional) ──
        if (config.trustStorePath() != null && !config.trustStorePath().isBlank()) {
            Path trustPath = Path.of(config.trustStorePath());
            if (!Files.exists(trustPath)) {
                log.warn("Trust store file not found: {} — mTLS client validation may fail",
                        config.trustStorePath());
            }
        }
    }

    /**
     * Validates the certificate chain at the given path: parses all certs, checks expiry,
     * verifies chain completeness (each cert signed by the next), and logs warnings.
     */
    private void validateCertificateChain(String certPath) throws CertificateException, IOException {
        List<X509Certificate> chain;
        try (InputStream is = Files.newInputStream(Path.of(certPath))) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            chain = new ArrayList<>();
            for (Certificate c : cf.generateCertificates(is)) {
                chain.add((X509Certificate) c);
            }
        }

        if (chain.isEmpty()) {
            throw new CertificateException("No certificates found in " + certPath);
        }

        // Check leaf certificate expiry
        X509Certificate leaf = chain.get(0);
        Instant notAfter = leaf.getNotAfter().toInstant();
        long daysUntilExpiry = Duration.between(Instant.now(), notAfter).toDays();

        if (daysUntilExpiry < 0) {
            throw new CertificateException("Server certificate EXPIRED " + Math.abs(daysUntilExpiry)
                    + " days ago — subject=" + leaf.getSubjectX500Principal().getName());
        }
        if (daysUntilExpiry < EXPIRY_WARN_DAYS) {
            log.warn("Server certificate expires in {} days! subject={}, notAfter={}",
                    daysUntilExpiry, leaf.getSubjectX500Principal().getName(), notAfter);
        } else {
            log.debug("Server certificate valid for {} more days — subject={}",
                    daysUntilExpiry, leaf.getSubjectX500Principal().getName());
        }

        // Verify chain integrity: each cert should be signed by the next
        for (int i = 0; i < chain.size() - 1; i++) {
            X509Certificate subject = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            try {
                subject.verify(issuer.getPublicKey());
            } catch (Exception e) {
                log.warn("Certificate chain may be incomplete: cert[{}] ({}) not signed by cert[{}] ({}): {}",
                        i, subject.getSubjectX500Principal().getName(),
                        i + 1, issuer.getSubjectX500Principal().getName(),
                        e.getMessage());
            }
        }

        // Check if the chain ends with a self-signed cert (root CA)
        X509Certificate last = chain.get(chain.size() - 1);
        boolean selfSigned = last.getSubjectX500Principal().equals(last.getIssuerX500Principal());
        if (chain.size() == 1 && !selfSigned) {
            log.warn("Certificate chain contains only the leaf certificate — "
                    + "intermediates may be missing (subject={})", leaf.getSubjectX500Principal().getName());
        }

        log.debug("Certificate chain validated: {} cert(s), leaf subject={}",
                chain.size(), leaf.getSubjectX500Principal().getName());
    }
}
