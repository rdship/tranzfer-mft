package com.filetransfer.https.server;

import com.filetransfer.shared.client.KeystoreServiceClient;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.security.SecurityProfileEnforcer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a per-listener Tomcat {@link Connector} for a {@link ServerInstance}
 * row with {@code protocol=HTTPS}.
 *
 * <p><b>End-to-end wiring:</b>
 * <ol>
 *   <li><b>TLS cert material</b> — pulled from Keystore Manager via
 *       {@link KeystoreServiceClient#getTlsCertificate(String)} using the
 *       listener's {@code httpsTlsCertAlias}. The PEM string (cert + private
 *       key concatenated) is written to {@code /data/https-keystores/<alias>.pem}
 *       with 0600 permissions; Tomcat's JSSE implementation reads PEM directly.</li>
 *   <li><b>Ciphers + min TLS version</b> — resolved via
 *       {@link SecurityProfileEnforcer#resolveTlsCiphers(ServerInstance)} and
 *       {@link SecurityProfileEnforcer#resolveTlsMinVersion(ServerInstance)},
 *       which apply the precedence chain (per-listener CSV → SecurityProfile
 *       row → platform default).</li>
 *   <li><b>Client cert (mTLS)</b> — resolved via
 *       {@link SecurityProfileEnforcer#resolveClientAuthRequired(ServerInstance)};
 *       profile-level opt-in cannot be downgraded by per-listener false.</li>
 * </ol>
 *
 * <p>When Keystore Manager is unreachable or has no cert for the alias, the
 * connector build FAILS LOUDLY (caller marks {@code bind_state=BIND_FAILED}).
 * We do NOT silently self-sign — admins explicitly select a cert alias so
 * they expect that cert, not a placeholder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpsConnectorFactory {

    private final KeystoreServiceClient keystoreServiceClient;
    private final SecurityProfileEnforcer securityProfileEnforcer;

    @Value("${https.keystore-dir:/data/https-keystores}")
    private String keystoreDir;

    /**
     * Build a configured HTTPS {@link Connector}. Caller adds it to the
     * embedded Tomcat instance via {@code tomcat.getService().addConnector(c)}.
     *
     * @throws IllegalStateException when the cert alias can't be resolved —
     *         the listener row should be marked BIND_FAILED at the call site.
     */
    public Connector build(ServerInstance si) {
        if (si.getHttpsTlsCertAlias() == null || si.getHttpsTlsCertAlias().isBlank()) {
            throw new IllegalStateException(
                    "HTTPS listener '" + si.getInstanceId() + "' has no httpsTlsCertAlias set");
        }

        Path pemPath = materializeCert(si.getHttpsTlsCertAlias(), si.getInstanceId());
        String minVersion = securityProfileEnforcer.resolveTlsMinVersion(si);
        Set<String> ciphers = securityProfileEnforcer.resolveTlsCiphers(si);
        boolean clientAuthRequired = securityProfileEnforcer.resolveClientAuthRequired(si);

        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(si.getInternalPort());
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");

        SSLHostConfig host = new SSLHostConfig();
        host.setHostName("_default_");
        // Tomcat accepts comma-separated list; order matters (preferred first).
        host.setProtocols(enabledProtocolsFor(minVersion));
        host.setCiphers(String.join(":", ciphers));
        host.setCertificateVerification(clientAuthRequired
                ? SSLHostConfig.CertificateVerification.REQUIRED.toString()
                : SSLHostConfig.CertificateVerification.NONE.toString());

        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(
                host, SSLHostConfigCertificate.Type.UNDEFINED);
        // Tomcat's JSSE SSL impl reads cert + key from the same PEM when both
        // are present; we write a combined file for operational simplicity.
        cert.setCertificateFile(pemPath.toAbsolutePath().toString());
        cert.setCertificateKeyFile(pemPath.toAbsolutePath().toString());
        host.addCertificate(cert);

        connector.addSslHostConfig(host);

        log.info("[HTTPS] Built connector for listener '{}' port={} tls-min={} ciphers={} mtls={} pem={}",
                si.getInstanceId(), si.getInternalPort(), minVersion, ciphers.size(),
                clientAuthRequired, pemPath);
        return connector;
    }

    /**
     * Pulls the cert from Keystore Manager and writes it to a stable per-alias
     * path so multiple listeners sharing an alias reuse the same file. Perms
     * are 0600 where the filesystem supports POSIX attrs.
     */
    private Path materializeCert(String alias, String instanceId) {
        if (!keystoreServiceClient.isEnabled()) {
            throw new IllegalStateException(
                    "HTTPS listener '" + instanceId + "' requires Keystore Manager but it is disabled");
        }
        String pem = keystoreServiceClient.getTlsCertificate(alias);
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "Keystore Manager has no TLS cert for alias '" + alias + "' (listener '" + instanceId + "')");
        }
        Path dir = Path.of(keystoreDir);
        Path pemPath = dir.resolve(sanitizeAlias(alias) + ".pem");
        try {
            Files.createDirectories(dir);
            Files.writeString(pemPath, pem,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(pemPath,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX FS (e.g. Windows dev); skip chmod.
            }
            return pemPath;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to materialize cert PEM for alias '" + alias + "': " + e.getMessage(), e);
        }
    }

    /**
     * Convert "TLSv1.2" to the Tomcat protocols string that enables that
     * version AND every higher one (TLSv1.3 is always enabled). Tomcat wants
     * the enabled-list explicit; we never enable TLSv1.1 / 1.0 / SSLv3.
     */
    private static String enabledProtocolsFor(String minVersion) {
        Set<String> enabled = new HashSet<>();
        enabled.add("TLSv1.3");
        if ("TLSv1.2".equalsIgnoreCase(minVersion)) {
            enabled.add("TLSv1.2");
        }
        return String.join("+", enabled);
    }

    private static String sanitizeAlias(String alias) {
        return alias.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
