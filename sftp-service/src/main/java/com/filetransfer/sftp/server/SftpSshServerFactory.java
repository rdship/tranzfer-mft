package com.filetransfer.sftp.server;

import com.filetransfer.shared.entity.core.SecurityProfile;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.security.SecurityProfileEnforcer;
import com.filetransfer.sftp.routing.SftpAuditingEventListener;
import com.filetransfer.sftp.routing.SftpRoutingEventListener;
import com.filetransfer.sftp.session.SftpSessionListener;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.Cipher;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a configured but not-yet-started {@link SshServer} for a given
 * {@link ServerInstance}. Shared between the boot-time env-var bean and the
 * runtime {@link SftpListenerRegistry}.
 *
 * <p>All per-listener settings (port, idle timeout, banner, ciphers, MACs,
 * KEX, host key) come from the ServerInstance. ENV-driven defaults are used
 * only when a field is null/blank.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpSshServerFactory {

    private final SftpPasswordAuthenticator passwordAuthenticator;
    private final SftpPublicKeyAuthenticator publicKeyAuthenticator;
    private final SftpFileSystemFactory fileSystemFactory;
    private final SftpRoutingEventListener routingEventListener;
    private final SftpAuditingEventListener auditingEventListener;
    private final SftpSessionListener sessionListener;

    /**
     * R134n — optional; when present, per-listener SSH algorithm allowlists
     * are resolved via the precedence chain:
     *   per-listener CSV → SecurityProfile row (server_instance.securityProfileId)
     *   → platform static defaults.
     * Null-safe: if the bean is absent (e.g., unit tests) we fall back to the
     * pre-enforcer behaviour (per-listener CSV or SecurityProfile static
     * defaults), so the listener still binds with hardened algorithms.
     */
    @Autowired(required = false)
    @Nullable
    private SecurityProfileEnforcer securityProfileEnforcer;

    @Value("${sftp.host-key-path:./sftp_host_key}")
    private String defaultHostKeyPath;

    /** Build a fully-configured SshServer from a ServerInstance. Call start() separately. */
    public SshServer build(ServerInstance si) {
        SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory();
        sftpFactory.setFileSystemAccessor(new VfsSftpFileSystemAccessor());
        sftpFactory.addSftpEventListener(routingEventListener);
        sftpFactory.addSftpEventListener(auditingEventListener);

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(si.getInternalPort());
        sshd.setKeyPairProvider(keyPairProvider(si));
        sshd.setPasswordAuthenticator(passwordAuthenticator);
        sshd.setPublickeyAuthenticator(publicKeyAuthenticator);
        sshd.setSubsystemFactories(Collections.singletonList(sftpFactory));
        sshd.setFileSystemFactory(fileSystemFactory);
        sshd.addSessionListener(sessionListener);
        // Tag every session with THIS listener's identity so shared auth and
        // filesystem callbacks can resolve the right ServerInstance.
        sshd.addSessionListener(new ListenerContext.Tagger(
                si.getInstanceId(), si.getDefaultStorageMode()));

        if (si.getIdleTimeoutSeconds() > 0) {
            CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ofSeconds(si.getIdleTimeoutSeconds()));
        }
        if (si.getSessionMaxDurationSeconds() > 0) {
            CoreModuleProperties.AUTH_TIMEOUT.set(sshd, Duration.ofSeconds(si.getSessionMaxDurationSeconds()));
        }
        if (si.getSshBannerMessage() != null && !si.getSshBannerMessage().isBlank()) {
            CoreModuleProperties.WELCOME_BANNER.set(sshd, si.getSshBannerMessage());
        }

        applyAlgorithms(sshd, si);
        return sshd;
    }

    private KeyPairProvider keyPairProvider(ServerInstance si) {
        // Per-listener key file derived from instanceId to avoid collisions across listeners.
        String path = defaultHostKeyPath;
        if (si.getInstanceId() != null && !si.getInstanceId().isBlank()) {
            path = defaultHostKeyPath + "_" + si.getInstanceId().replaceAll("[^a-zA-Z0-9_-]", "_");
        }
        return new SimpleGeneratorHostKeyProvider(Paths.get(path));
    }

    private void applyAlgorithms(SshServer sshd, ServerInstance si) {
        // R134n — route every allowlist through SecurityProfileEnforcer so the
        // SecurityProfile row the admin selected on the ServerInstance is
        // actually honoured at bind time. When the enforcer bean is absent
        // (unit tests, reduced classpath), fall back to the legacy code path
        // that resolves from per-listener CSV or platform static defaults —
        // behaviour identical to pre-R134n.
        applyCiphers(sshd, si);
        applyMacs(sshd, si);
        applyKex(sshd, si);
    }

    private void applyCiphers(SshServer sshd, ServerInstance si) {
        Set<String> allowed = securityProfileEnforcer != null
                ? securityProfileEnforcer.resolveSshCiphers(si)
                : legacyCsvOrDefault(si.getAllowedCiphers(), SecurityProfile.ALLOWED_SSH_CIPHERS);
        List<NamedFactory<Cipher>> filtered = BuiltinCiphers.VALUES.stream()
                .filter(c -> allowed.contains(c.getName()))
                .filter(BuiltinCiphers::isSupported)
                .map(c -> (NamedFactory<Cipher>) c)
                .collect(Collectors.toList());
        if (!filtered.isEmpty()) sshd.setCipherFactories(filtered);
    }

    private void applyMacs(SshServer sshd, ServerInstance si) {
        Set<String> allowed = securityProfileEnforcer != null
                ? securityProfileEnforcer.resolveSshMacs(si)
                : legacyCsvOrDefault(si.getAllowedMacs(), SecurityProfile.ALLOWED_SSH_MACS);
        List<NamedFactory<Mac>> filtered = BuiltinMacs.VALUES.stream()
                .filter(m -> allowed.contains(m.getName()))
                .filter(BuiltinMacs::isSupported)
                .map(m -> (NamedFactory<Mac>) m)
                .collect(Collectors.toList());
        if (!filtered.isEmpty()) sshd.setMacFactories(filtered);
    }

    private void applyKex(SshServer sshd, ServerInstance si) {
        Set<String> allowed = securityProfileEnforcer != null
                ? securityProfileEnforcer.resolveSshKex(si)
                : legacyCsvOrDefault(si.getAllowedKex(), SecurityProfile.ALLOWED_SSH_KEX);
        List<KeyExchangeFactory> current = sshd.getKeyExchangeFactories();
        if (current == null || current.isEmpty()) {
            current = SshServer.setUpDefaultServer().getKeyExchangeFactories();
        }
        List<KeyExchangeFactory> filtered = current.stream()
                .filter(k -> allowed.contains(k.getName()))
                .collect(Collectors.toList());
        if (!filtered.isEmpty()) sshd.setKeyExchangeFactories(filtered);
    }

    /** Pre-R134n fallback when the enforcer bean isn't wired in this context. */
    private static Set<String> legacyCsvOrDefault(String csv, Set<String> platformDefault) {
        return csv != null && !csv.isBlank() ? parse(csv) : platformDefault;
    }

    private static Set<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

}
