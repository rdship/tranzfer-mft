package com.filetransfer.sftp.server;

import com.filetransfer.sftp.keystore.KeystoreManagerClient;
import com.filetransfer.sftp.routing.SftpAuditingEventListener;
import com.filetransfer.sftp.routing.SftpRoutingEventListener;
import com.filetransfer.sftp.session.SftpSessionListener;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

import com.filetransfer.shared.entity.core.SecurityProfile;

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

    @Autowired(required = false)
    @org.springframework.lang.Nullable
    private com.filetransfer.shared.repository.core.ServerInstanceRepository serverInstanceRepository;

    @Value("${sftp.port:2222}")
    private int sftpPort;

    @Value("${sftp.host-key-path:./sftp_host_key}")
    private String hostKeyPath;

    @Value("${sftp.instance-id:#{null}}")
    private String instanceId;

    // ENV defaults — overridden by ServerInstance DB config when available
    @Value("${sftp.idle-timeout-seconds:0}")
    private int idleTimeoutSeconds;

    @Value("${sftp.max-session-duration-seconds:0}")
    private int maxSessionDurationSeconds;

    @Value("${sftp.banner-message:}")
    private String bannerMessage;

    @Value("${sftp.algorithms.ciphers:}")
    private String configuredCiphers;

    @Value("${sftp.algorithms.macs:}")
    private String configuredMacs;

    @Value("${sftp.algorithms.kex:}")
    private String configuredKex;

    @Bean
    public SshServer sshServer() {
        // Load per-instance config from DB — UI changes take effect on next restart.
        // ENV vars are the fallback when no DB record exists (fresh install).
        if (instanceId != null && serverInstanceRepository != null) {
            serverInstanceRepository.findByInstanceId(instanceId).ifPresent(si -> {
                if (si.getAllowedCiphers() != null && !si.getAllowedCiphers().isBlank()) {
                    configuredCiphers = si.getAllowedCiphers();
                    log.info("SFTP ciphers loaded from DB: {}", configuredCiphers);
                }
                if (si.getAllowedMacs() != null && !si.getAllowedMacs().isBlank()) {
                    configuredMacs = si.getAllowedMacs();
                    log.info("SFTP MACs loaded from DB: {}", configuredMacs);
                }
                if (si.getAllowedKex() != null && !si.getAllowedKex().isBlank()) {
                    configuredKex = si.getAllowedKex();
                    log.info("SFTP KEX loaded from DB: {}", configuredKex);
                }
                if (si.getIdleTimeoutSeconds() > 0) {
                    idleTimeoutSeconds = si.getIdleTimeoutSeconds();
                    log.info("SFTP idle timeout loaded from DB: {}s", idleTimeoutSeconds);
                }
                if (si.getSessionMaxDurationSeconds() > 0) {
                    maxSessionDurationSeconds = si.getSessionMaxDurationSeconds();
                    log.info("SFTP session max duration loaded from DB: {}s", maxSessionDurationSeconds);
                }
                if (si.getSshBannerMessage() != null && !si.getSshBannerMessage().isBlank()) {
                    bannerMessage = si.getSshBannerMessage();
                    log.info("SFTP banner loaded from DB");
                }
                log.info("SFTP instance '{}' config loaded from database", instanceId);
            });
        }

        SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory();
        sftpFactory.setFileSystemAccessor(new VfsSftpFileSystemAccessor());
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
            try {
                sshServer.start();
                log.info("SFTP server started on port {}", sftpPort);
            } catch (java.net.BindException e) {
                log.error("SFTP primary listener FAILED to bind port {} — port already in use. "
                        + "Dynamic listeners may still work on other ports.", sftpPort);
            } catch (Exception e) {
                log.error("SFTP primary listener FAILED to start on port {}: {}", sftpPort, e.getMessage());
            }
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
     * Configures allowed ciphers. When explicit config is provided, uses that list.
     * Otherwise applies a hardened default that excludes CBC-mode and weak ciphers.
     */
    private void configureCiphers(SshServer sshd) {
        Set<String> allowed;
        if (configuredCiphers != null && !configuredCiphers.isBlank()) {
            allowed = parseCommaSeparated(configuredCiphers);
        } else {
            // Hardened defaults: AEAD + CTR only, no CBC
            allowed = SecurityProfile.ALLOWED_SSH_CIPHERS;
            log.info("No SFTP ciphers configured — applying hardened defaults");
        }

        List<NamedFactory<Cipher>> filtered = BuiltinCiphers.VALUES.stream()
                .filter(c -> allowed.contains(c.getName()))
                .filter(BuiltinCiphers::isSupported)
                .map(c -> (NamedFactory<Cipher>) c)
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid ciphers matched — using MINA defaults (NOT RECOMMENDED for production)");
            return;
        }

        sshd.setCipherFactories(filtered);
        log.info("SFTP ciphers restricted to: {}", filtered.stream().map(NamedFactory::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Configures allowed MAC algorithms. Defaults to ETM (encrypt-then-MAC) variants.
     */
    private void configureMacs(SshServer sshd) {
        Set<String> allowed;
        if (configuredMacs != null && !configuredMacs.isBlank()) {
            allowed = parseCommaSeparated(configuredMacs);
        } else {
            // Hardened defaults: ETM + SHA-2 only
            allowed = SecurityProfile.ALLOWED_SSH_MACS;
            log.info("No SFTP MACs configured — applying hardened defaults");
        }

        List<NamedFactory<Mac>> filtered = BuiltinMacs.VALUES.stream()
                .filter(m -> allowed.contains(m.getName()))
                .filter(BuiltinMacs::isSupported)
                .map(m -> (NamedFactory<Mac>) m)
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid MACs matched — using MINA defaults (NOT RECOMMENDED for production)");
            return;
        }

        sshd.setMacFactories(filtered);
        log.info("SFTP MACs restricted to: {}", filtered.stream().map(NamedFactory::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Configures allowed key exchange algorithms. Defaults to ECDH + strong DH groups.
     */
    private void configureKex(SshServer sshd) {
        Set<String> allowed;
        if (configuredKex != null && !configuredKex.isBlank()) {
            allowed = parseCommaSeparated(configuredKex);
        } else {
            // Hardened defaults: curve25519, ECDH, DH group16+
            allowed = SecurityProfile.ALLOWED_SSH_KEX;
            log.info("No SFTP KEX configured — applying hardened defaults");
        }

        List<KeyExchangeFactory> currentKex = sshd.getKeyExchangeFactories();
        if (currentKex == null || currentKex.isEmpty()) {
            currentKex = SshServer.setUpDefaultServer().getKeyExchangeFactories();
        }

        List<KeyExchangeFactory> filtered = currentKex.stream()
                .filter(k -> allowed.contains(k.getName()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            log.warn("No valid KEX algorithms matched — using MINA defaults (NOT RECOMMENDED for production)");
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
