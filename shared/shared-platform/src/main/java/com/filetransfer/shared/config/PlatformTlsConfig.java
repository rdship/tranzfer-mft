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

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

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

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tlsCustomizer() {
        return factory -> {
            String ksPath = resolveKeystore();
            if (ksPath == null) {
                log.warn("TLS enabled but no keystore available — falling back to HTTP");
                return;
            }

            Ssl ssl = new Ssl();
            ssl.setEnabled(true);
            ssl.setKeyStore("file:" + ksPath);
            ssl.setKeyStorePassword(keystorePassword);
            ssl.setKeyAlias(keyAlias);
            ssl.setKeyStoreType("PKCS12");
            ssl.setProtocol("TLS");
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            ssl.setCiphers(new String[]{
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_CHACHA20_POLY1305_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            });

            factory.setSsl(ssl);
            log.info("HTTPS enabled on {} (keystore={}, alias={}, protocols=TLSv1.2+1.3)",
                    serviceType, ksPath, keyAlias);
        };
    }

    private String resolveKeystore() {
        // Priority 1: Explicit keystore path
        if (keystorePath != null && !keystorePath.isBlank()) {
            if (Files.exists(Path.of(keystorePath))) {
                log.info("Using external TLS keystore: {}", keystorePath);
                return keystorePath;
            }
            log.warn("Configured keystore not found: {}", keystorePath);
        }

        // Priority 2: Auto-generate self-signed PKCS12 keystore
        Path autoKeystore = Path.of(System.getProperty("java.io.tmpdir"), "platform-tls-" + serviceType + ".p12");
        if (Files.exists(autoKeystore)) {
            log.info("Using cached auto-generated TLS keystore: {}", autoKeystore);
            return autoKeystore.toString();
        }

        try {
            log.info("Generating self-signed TLS certificate for {}...", serviceType);
            generateSelfSignedKeystore(autoKeystore);
            return autoKeystore.toString();
        } catch (Exception e) {
            log.error("Failed to generate TLS keystore: {}", e.getMessage());
            return null;
        }
    }

    private void generateSelfSignedKeystore(Path output) throws Exception {
        String cn = serviceType.toLowerCase() + ".filetransfer.local";
        Files.createDirectories(output.getParent());

        // Use keytool (JDK built-in) — portable across JDK versions, no internal API
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
                "-dname", "CN=" + cn + ",O=TranzFer MFT,C=US",
                "-ext", "SAN=dns:localhost,dns:" + serviceType.toLowerCase() + ",ip:127.0.0.1"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("keytool failed (exit=" + exit + "): " + out);
        }
        log.info("Self-signed TLS certificate generated: CN={}, valid=365d, keystore={}", cn, output);
    }
}
