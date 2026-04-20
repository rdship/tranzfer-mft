package com.filetransfer.shared.security;

import com.filetransfer.shared.entity.core.SecurityProfile;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.repository.core.SecurityProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the effective security policy for a listener-bind operation.
 *
 * <p>Closes a load-bearing platform gap: {@code server_instances.security_profile_id}
 * existed as a FK column on every server_instances row but no listener read it
 * at runtime. SFTP's {@code SftpSshServerFactory.applyAlgorithms} reached for
 * {@link SecurityProfile#ALLOWED_SSH_CIPHERS} static constants as the fallback
 * when the per-listener CSV was blank, never consulting the Profile row the
 * admin selected.
 *
 * <p><b>Precedence (most-specific wins):</b>
 * <ol>
 *   <li><b>Per-listener CSV</b> — {@link ServerInstance#getAllowedCiphers()},
 *       {@code getAllowedMacs()}, {@code getAllowedKex()},
 *       {@link ServerInstance#getHttpsAllowedCiphers()}. Highest priority;
 *       when set, overrides any profile.
 *   <li><b>SecurityProfile row</b> — resolved via
 *       {@link ServerInstance#getSecurityProfileId()} against
 *       {@link SecurityProfileRepository}. Medium priority. Non-null profile
 *       fields apply; null profile fields fall through to layer 3.
 *   <li><b>Platform defaults</b> — the {@code SecurityProfile.ALLOWED_*}
 *       static constants (AEAD/CTR ciphers, ETM MACs, ECDH/curve25519 KEX,
 *       TLS 1.3 + strong 1.2 suites). Always usable.
 * </ol>
 *
 * <p><b>Bean is optional</b> — wired via {@code @Autowired(required=false)} so
 * services without the security-profile repository on their classpath still
 * compile. Callers null-check the bean; absence returns layer-3 defaults
 * directly, identical to pre-enforcer behaviour.
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SecurityProfileEnforcer {

    @Nullable
    private final SecurityProfileRepository securityProfileRepository;

    // ── SSH ─────────────────────────────────────────────────────────────────

    public Set<String> resolveSshCiphers(ServerInstance si) {
        return resolveList(perListenerCsv(si == null ? null : si.getAllowedCiphers()),
                profileOf(si), SecurityProfile::getSshCiphers,
                SecurityProfile.ALLOWED_SSH_CIPHERS);
    }

    public Set<String> resolveSshMacs(ServerInstance si) {
        return resolveList(perListenerCsv(si == null ? null : si.getAllowedMacs()),
                profileOf(si), SecurityProfile::getSshMacs,
                SecurityProfile.ALLOWED_SSH_MACS);
    }

    public Set<String> resolveSshKex(ServerInstance si) {
        return resolveList(perListenerCsv(si == null ? null : si.getAllowedKex()),
                profileOf(si), SecurityProfile::getKexAlgorithms,
                SecurityProfile.ALLOWED_SSH_KEX);
    }

    public Set<String> resolveHostKeyAlgorithms(ServerInstance si) {
        return resolveList(null,
                profileOf(si), SecurityProfile::getHostKeyAlgorithms,
                SecurityProfile.ALLOWED_HOST_KEY_ALGORITHMS);
    }

    // ── TLS ─────────────────────────────────────────────────────────────────

    public Set<String> resolveTlsCiphers(ServerInstance si) {
        return resolveList(perListenerCsv(si == null ? null : si.getHttpsAllowedCiphers()),
                profileOf(si), SecurityProfile::getTlsCiphers,
                SecurityProfile.ALLOWED_TLS_CIPHERS);
    }

    /** Minimum TLS version string (e.g. {@code "TLSv1.2"}). Defaults to TLSv1.2. */
    public String resolveTlsMinVersion(ServerInstance si) {
        SecurityProfile p = profileOf(si);
        if (p != null && p.getTlsMinVersion() != null && !p.getTlsMinVersion().isBlank()) {
            return p.getTlsMinVersion();
        }
        return "TLSv1.2";
    }

    /**
     * True iff the listener must require a client certificate.
     * Per-instance {@code httpsClientCertRequired=true} wins over a profile
     * that says false; a profile that says true cannot be overridden down to
     * false via per-listener boolean (principle of least surprise — opting in
     * to mTLS at profile level is binding).
     */
    public boolean resolveClientAuthRequired(ServerInstance si) {
        if (si != null && si.isHttpsClientCertRequired()) return true;
        SecurityProfile p = profileOf(si);
        return p != null && p.isClientAuthRequired();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Merge one precedence layer: per-listener CSV (if non-blank) beats profile
     * list (if non-empty) beats the platform default.
     */
    private Set<String> resolveList(@Nullable Set<String> perListener,
                                     @Nullable SecurityProfile profile,
                                     ProfileAccessor accessor,
                                     Set<String> platformDefault) {
        if (perListener != null && !perListener.isEmpty()) return perListener;
        if (profile != null) {
            Collection<String> fromProfile = accessor.get(profile);
            if (fromProfile != null && !fromProfile.isEmpty()) {
                return new HashSet<>(fromProfile);
            }
        }
        return platformDefault;
    }

    /**
     * Resolve the SecurityProfile row referenced by the listener. Returns null
     * when the FK is unset, the repository isn't wired into this service, or
     * the row is missing/inactive. Single DB round-trip per bind — listener
     * binds are rare events, no caching needed.
     */
    @Nullable
    private SecurityProfile profileOf(@Nullable ServerInstance si) {
        if (si == null || si.getSecurityProfileId() == null) return null;
        if (securityProfileRepository == null) return null;
        UUID id = si.getSecurityProfileId();
        SecurityProfile p = securityProfileRepository.findById(id).orElse(null);
        if (p == null) {
            log.warn("[SecurityProfileEnforcer] server_instance {} references missing security_profile_id={} — falling back to platform defaults",
                    si.getInstanceId(), id);
            return null;
        }
        if (!p.isActive()) {
            log.warn("[SecurityProfileEnforcer] server_instance {} references inactive security_profile '{}' (id={}) — falling back to platform defaults",
                    si.getInstanceId(), p.getName(), id);
            return null;
        }
        return p;
    }

    @Nullable
    private static Set<String> perListenerCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    /** Function handle so the same merge logic applies to every list-typed field. */
    @FunctionalInterface
    private interface ProfileAccessor {
        Collection<String> get(SecurityProfile p);

        default List<String> toList(SecurityProfile p) {
            Collection<String> c = get(p);
            return c == null ? null : new java.util.ArrayList<>(c);
        }
    }
}
