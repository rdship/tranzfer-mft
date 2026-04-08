package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.tls.KeystoreIntegration;
import com.filetransfer.dmz.tls.TlsTerminator.TlsConfig;
import com.filetransfer.tunnel.control.ControlMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    private final String internalApiKey;
    private final Path certCacheDir;

    /**
     * @param keystoreManagerUrl     base URL (used by parent for identification only)
     * @param internalApiKey         API key for X-Internal-Key header
     * @param certCacheDir           local directory for caching fetched certificates
     * @param refreshIntervalSeconds interval between certificate refresh checks
     * @param tunnelAcceptor         the tunnel acceptor (handler resolved lazily)
     */
    public TunnelKeystoreIntegration(String keystoreManagerUrl, String internalApiKey,
                                      String certCacheDir, long refreshIntervalSeconds,
                                      TunnelAcceptor tunnelAcceptor) {
        super(keystoreManagerUrl, internalApiKey, certCacheDir, refreshIntervalSeconds);
        this.tunnelAcceptor = tunnelAcceptor;
        this.internalApiKey = internalApiKey;
        this.certCacheDir = Path.of(certCacheDir != null ? certCacheDir : "./cert-cache");
    }

    /**
     * Fetches a certificate by alias, routing through the tunnel instead of direct HTTP.
     * Falls back to parent's disk/memory cache on tunnel failure.
     */
    @Override
    public Optional<TlsConfig> fetchCertificate(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");

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
                Map.of("X-Internal-Key", internalApiKey, "Accept", "application/json"),
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
