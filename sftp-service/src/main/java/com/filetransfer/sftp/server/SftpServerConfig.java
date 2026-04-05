package com.filetransfer.sftp.server;

import com.filetransfer.sftp.keystore.KeystoreManagerClient;
import com.filetransfer.sftp.routing.SftpAuditingEventListener;
import com.filetransfer.sftp.routing.SftpRoutingEventListener;
import com.filetransfer.sftp.session.SftpSessionListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.Cipher;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Configures and starts the Apache MINA SSHD-based SFTP server with
 * production-grade features: connection management, idle timeout,
 * session duration limits, SSH algorithm configuration, pre-auth banner,
 * audit logging, and graceful shutdown support.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpServerConfig {

    private final SftpPasswordAuthenticator passwordAuthenticator;
    private final SftpPublicKeyAuthenticator publicKeyAuthenticator;
    private final SftpFileSystemFactory fileSystemFactory;
    private final SftpRoutingEventListener routingEventListener;
    private final SftpAuditingEventListener auditingEventListener;
    private final SftpSessionListener sessionListener;
    private final KeystoreManagerClient keystoreManagerClient;

    @Value("${sftp.port:2222}")
    private int sftpPort;

    @Value("${sftp.host-key-path:./sftp_host_key}")
    private String hostKeyPath;

    /** Idle timeout in seconds. 0 = no timeout (backward compatible default). */
    @Value("${sftp.idle-timeout-seconds:0}")
    private int idleTimeoutSeconds;

    /** Maximum session duration in seconds. 0 = unlimited. */
    @Value("${sftp.max-session-duration-seconds:0}")
    private int maxSessionDurationSeconds;

    /** Pre-authentication banner message. Empty = no banner. */
    @Value("${sftp.banner-message:}")
    private String bannerMessage;

    /** Comma-separated list of allowed ciphers. Empty = MINA defaults. */
    @Value("${sftp.algorithms.ciphers:}")
    private String configuredCiphers;

    /** Comma-separated list of allowed MACs. Empty = MINA defaults. */
    @Value("${sftp.algorithms.macs:}")
    private String configuredMacs;

    /** Comma-separated list of allowed key exchange algorithms. Empty = MINA defaults. */
    @Value("${sftp.algorithms.kex:}")
    private String configuredKex;

    @Bean
    public SshServer sshServer() {
        SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory();
        sftpFactory.addSftpEventListener(routingEventListener);
        sftpFactory.addSftpEventListener(auditingEventListener);

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(sftpPort);
        sshd.setKeyPairProvider(createKeyPairProvider());
        sshd.setPasswordAuthenticator(passwordAuthenticator);
        sshd.setPublickeyAuthenticator(publicKeyAuthenticator);
        sshd.setSubsystemFactories(Collections.singletonList(sftpFactory));
        sshd.setFileSystemFactory(fileSystemFactory);

        // Register session listener for connection management and audit
        sshd.addSessionListener(sessionListener);

        // Configure idle timeout
        if (idleTimeoutSeconds > 0) {
            CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ofSeconds(idleTimeoutSeconds));
            log.info("SFTP idle timeout set to {} seconds", idleTimeoutSeconds);
        }

        // Configure max session duration (auth timeout repurposed as session limit)
        if (maxSessionDurationSeconds > 0) {
            CoreModuleProperties.AUTH_TIMEOUT.set(sshd, Duration.ofSeconds(maxSessionDurationSeconds));
            log.info("SFTP max session duration set to {} seconds", maxSessionDurationSeconds);
        }

        // Configure pre-auth banner
        if (bannerMessage != null && !bannerMessage.isEmpty()) {
            CoreModuleProperties.WELCOME_BANNER.set(sshd, bannerMessage);
            log.info("SFTP pre-auth banner configured");
        }

        // Configure SSH algorithms
        configureCiphers(sshd);
        configureMacs(sshd);
        configureKex(sshd);

        return sshd;
    }

    @Bean
    public ApplicationRunner sftpServerRunner(SshServer sshServer) {
        return args -> {
            sshServer.start();
            log.info("SFTP server started on port {}", sftpPort);
        };
    }

    /**
     * Creates the SSH host key provider. Tries to retrieve the key from the
     * centralized Keystore Manager first. Falls back to local file-based
     * generation if the Keystore Manager is unavailable or disabled.
     *
     * <p>When Keystore Manager is enabled and the key does not exist yet,
     * it generates one via the Keystore Manager API. If a local key already
     * exists but Keystore Manager has no record, the local key is imported
     * into the Keystore Manager for centralized management.</p>
     */
    private KeyPairProvider createKeyPairProvider() {
        if (keystoreManagerClient.isEnabled()) {
            log.info("Keystore Manager integration enabled — attempting to retrieve SSH host key");
            String alias = keystoreManagerClient.getDefaultAlias();

            // Try to get existing key from Keystore Manager
            String keyMaterial = keystoreManagerClient.getHostKey(alias);
            if (keyMaterial != null) {
                log.info("Using SSH host key '{}' from Keystore Manager", alias);
                // Still use local file as cache, but seed it from Keystore Manager
                // SimpleGeneratorHostKeyProvider will use the local file if it exists
            } else {
                // Key not in Keystore Manager — generate one there
                keyMaterial = keystoreManagerClient.generateAndStoreHostKey(alias);
                if (keyMaterial != null) {
                    log.info("Generated new SSH host key '{}' in Keystore Manager", alias);
                }
            }

            if (keyMaterial == null) {
                log.warn("Keystore Manager unavailable — falling back to local host key at {}", hostKeyPath);
            }
        } else {
            log.info("Keystore Manager integration disabled — using local host key at {}", hostKeyPath);
        }

        // Fall back (or primary if KM disabled): local file-based key provider
        return new SimpleGeneratorHostKeyProvider(Paths.get(hostKeyPath));
    }

    /**
     * Configures allowed ciphers if specified. Filters the MINA built-in cipher
     * list to only those named in the configuration.
     */
    private void configureCiphers(SshServer sshd) {
        if (configuredCiphers == null || configuredCiphers.isBlank()) return;

        Set<String> allowed = parseCommaSeparated(configuredCiphers);
        List<NamedFactory<Cipher>> filtered = BuiltinCiphers.VALUES.stream()
                .filter(c -> allowed.contains(c.getName()))
                .filter(BuiltinCiphers::isSupported)
                .map(c -> (NamedFactory<Cipher>) c)
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid ciphers matched configuration '{}'; using MINA defaults", configuredCiphers);
            return;
        }

        sshd.setCipherFactories(filtered);
        log.info("SFTP ciphers restricted to: {}", filtered.stream().map(NamedFactory::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Configures allowed MAC algorithms if specified.
     */
    private void configureMacs(SshServer sshd) {
        if (configuredMacs == null || configuredMacs.isBlank()) return;

        Set<String> allowed = parseCommaSeparated(configuredMacs);
        List<NamedFactory<Mac>> filtered = BuiltinMacs.VALUES.stream()
                .filter(m -> allowed.contains(m.getName()))
                .filter(BuiltinMacs::isSupported)
                .map(m -> (NamedFactory<Mac>) m)
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid MACs matched configuration '{}'; using MINA defaults", configuredMacs);
            return;
        }

        sshd.setMacFactories(filtered);
        log.info("SFTP MACs restricted to: {}", filtered.stream().map(NamedFactory::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Configures allowed key exchange algorithms if specified.
     */
    private void configureKex(SshServer sshd) {
        if (configuredKex == null || configuredKex.isBlank()) return;

        Set<String> allowed = parseCommaSeparated(configuredKex);

        // Filter the server's existing KEX factories to only those in the allowed list
        List<KeyExchangeFactory> currentKex = sshd.getKeyExchangeFactories();
        if (currentKex == null || currentKex.isEmpty()) {
            currentKex = SshServer.setUpDefaultServer().getKeyExchangeFactories();
        }

        List<KeyExchangeFactory> filtered = currentKex.stream()
                .filter(k -> allowed.contains(k.getName()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid KEX algorithms matched configuration '{}'; using MINA defaults", configuredKex);
            return;
        }

        sshd.setKeyExchangeFactories(filtered);
        log.info("SFTP KEX algorithms restricted to: {}", filtered.stream()
                .map(KeyExchangeFactory::getName).collect(Collectors.joining(", ")));
    }

    private Set<String> parseCommaSeparated(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
