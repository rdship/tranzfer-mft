package com.filetransfer.shared.config;

import com.filetransfer.shared.kms.VaultKmsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared TLS configuration for all Spring Boot services.
 *
 * <p>When {@code platform.tls.enabled=true}, configures embedded Tomcat to listen
 * on HTTPS with a self-signed or keystore-managed certificate. All 22 services
 * get HTTPS from this single config class.
 *
 * <p>Certificate sources (in priority order):
 * <ol>
 *   <li>External keystore file: {@code platform.tls.keystore-path}
 *   <li>Auto-generated self-signed: created on first boot, cached to disk
 * </ol>
 *
 * <p>Enable: set {@code PLATFORM_TLS_ENABLED=true} in docker-compose or application.yml
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "platform.tls.enabled", havingValue = "true")
public class PlatformTlsConfig {

    @Value("${platform.tls.keystore-path:#{null}}")
    private String keystorePath;

    @Value("${platform.tls.keystore-password:changeit}")
    private String keystorePassword;

    @Value("${platform.tls.key-alias:platform-tls}")
    private String keyAlias;

    @Value("${platform.tls.port-offset:443}")
    private int httpsPortOffset;

    @Value("${server.port:8080}")
    private int httpPort;

    @Value("${cluster.service-type:UNKNOWN}")
    private String serviceType;

    @Autowired(required = false)
    @Nullable
    private VaultKmsClient vaultKms;

    /**
     * Adds an HTTPS connector alongside the default HTTP connector.
     * HTTP stays on server.port (health checks, internal calls).
     * HTTPS on server.port + 1000 (application traffic, external).
     * Example: HTTP :8080 + HTTPS :9080
     */
    /**
     * Adds HTTPS alongside HTTP. Both available. Admin chooses what to use.
     * HTTP stays on server.port. HTTPS on server.port + 1000.
     * Health probes use HTTP. Application traffic uses HTTPS.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tlsCustomizer() {
        return factory -> {
            String ksPath = resolveKeystore();
            if (ksPath == null) {
                log.warn("TLS enabled but no keystore — HTTPS not available, HTTP only");
                return;
            }

            int httpsPort = httpPort + 1000;

            org.apache.catalina.connector.Connector httpsConnector =
                    new org.apache.catalina.connector.Connector("org.apache.coyote.http11.Http11NioProtocol");
            httpsConnector.setScheme("https");
            httpsConnector.setSecure(true);
            httpsConnector.setPort(httpsPort);
            httpsConnector.setProperty("SSLEnabled", "true");

            org.apache.tomcat.util.net.SSLHostConfig sslConfig = new org.apache.tomcat.util.net.SSLHostConfig();
            sslConfig.setProtocols("TLSv1.2+TLSv1.3");
            sslConfig.setCiphers("TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256,"
                    + "TLS_CHACHA20_POLY1305_SHA256,"
                    + "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,"
                    + "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

            org.apache.tomcat.util.net.SSLHostConfigCertificate cert =
                    new org.apache.tomcat.util.net.SSLHostConfigCertificate(sslConfig,
                            org.apache.tomcat.util.net.SSLHostConfigCertificate.Type.UNDEFINED);
            cert.setCertificateKeystoreFile(ksPath);
            cert.setCertificateKeystorePassword(keystorePassword);
            cert.setCertificateKeyAlias(keyAlias);
            cert.setCertificateKeystoreType("PKCS12");
            sslConfig.addCertificate(cert);
            httpsConnector.addSslHostConfig(sslConfig);

            factory.addAdditionalTomcatConnectors(httpsConnector);
            log.info("{} — HTTP:{} + HTTPS:{} (keystore={}, TLSv1.2+1.3)",
                    serviceType, httpPort, httpsPort, ksPath);
        };
    }

    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.client.KeystoreServiceClient keystoreClient;

    /**
     * Resolves the single platform-wide TLS keystore.
     * One key for the entire system — stored in keystore-manager as "platform-tls".
     * All services share it. Simple, auditable, one place to rotate.
     */
    private String resolveKeystore() {
        // Priority 1: Operator-mounted keystore (production)
        if (keystorePath != null && !keystorePath.isBlank() && Files.exists(Path.of(keystorePath))) {
            log.info("Using operator-mounted TLS keystore: {}", keystorePath);
            return keystorePath;
        }

        // Shared path — /tls volume mounted from Docker, persists across restarts
        // Falls back to /tmp if volume not mounted (dev mode)
        Path tlsDir = Files.isDirectory(Path.of("/tls")) ? Path.of("/tls") : Path.of(System.getProperty("java.io.tmpdir"));
        Path sharedKeystore = tlsDir.resolve("platform-tls.p12");

        // Priority 2: Already generated (cached across service restarts)
        if (Files.exists(sharedKeystore)) {
            log.info("Using shared platform TLS keystore: {}", sharedKeystore);
            return sharedKeystore.toString();
        }

        // Priority 3: Fetch from Vault KV (cert generated by vault-init)
        if (vaultKms != null) {
            try {
                String ksBase64 = vaultKms.getSecret("platform-tls", "keystore");
                if (ksBase64 != null && !ksBase64.isBlank()) {
                    Files.createDirectories(sharedKeystore.getParent());
                    Files.write(sharedKeystore, java.util.Base64.getDecoder().decode(ksBase64));
                    log.info("TLS keystore fetched from Vault and cached: {}", sharedKeystore);
                    return sharedKeystore.toString();
                }
            } catch (Exception e) {
                log.warn("Could not fetch TLS keystore from Vault: {}", e.getMessage());
            }
        }

        // Priority 4: Generate once — shared by all services (fallback)
        try {
            log.info("Generating shared platform TLS certificate (CN=*.filetransfer.local)...");
            generateSelfSignedKeystore(sharedKeystore);
            return sharedKeystore.toString();
        } catch (Exception e) {
            log.error("Failed to generate platform TLS keystore: {}", e.getMessage());
            return null;
        }
    }

    private void generateSelfSignedKeystore(Path output) throws Exception {
        Files.createDirectories(output.getParent());

        // One wildcard cert for the entire platform — all services, one key
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", keyAlias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", output.toString(),
                "-storepass", keystorePassword,
                "-keypass", keystorePassword,
                "-dname", "CN=*.filetransfer.local,O=TranzFer MFT,C=US",
                "-ext", "SAN=dns:*.filetransfer.local,dns:localhost,"
                        + "dns:onboarding-api,dns:config-service,dns:sftp-service,"
                        + "dns:ftp-service,dns:gateway-service,dns:encryption-service,"
                        + "dns:screening-service,dns:ai-engine,dns:keystore-manager,"
                        + "dns:storage-manager,dns:platform-sentinel,dns:edi-converter,"
                        + "dns:license-service,dns:analytics-service,dns:notification-service,"
                        + "dns:as2-service,dns:dmz-proxy,dns:external-forwarder-service,"
                        + "dns:ftp-web-service,ip:127.0.0.1"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("keytool failed (exit=" + exit + "): " + out);
        }
        log.info("Platform TLS certificate generated: CN=*.filetransfer.local, valid=365d, keystore={}", output);
    }
}
