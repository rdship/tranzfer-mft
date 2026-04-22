package com.filetransfer.sftp.service;

import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.AuditLogRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final TransferAccountRepository accountRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @org.springframework.beans.factory.annotation.Value("${sftp.instance-id:#{null}}")
    private String defaultInstanceId;

    // In-memory cache with TTL: (username + listener instanceId) → (account, expiry).
    // Key includes instance so two listeners with overlapping account namespaces
    // don't collide. H7 fix: 60s TTL.
    private static final long CACHE_TTL_MS = 60_000;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(TransferAccount account, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /**
     * R134AB — BCrypt library self-test on boot.
     *
     * <p>Rules out the "library itself is broken" hypothesis for the R134V
     * password-mismatch regression. If this self-test FAILS at boot,
     * {@link #authenticatePassword} will always fail too, for any account.
     * If this PASSES but a partner account still mismatches at runtime,
     * the blame moves to password-transport (wire → handler) or the
     * stored hash itself (charset, trim, accidental re-encode on insert).
     *
     * <p>Zero secret exposure — uses a fixed in-code literal, encodes it,
     * verifies the encoder round-trips against its own output. No partner
     * data touched.
     */
    @PostConstruct
    void bcryptSelfTest() {
        String probe = "bcrypt-self-test-v1";
        try {
            String encoded = passwordEncoder.encode(probe);
            boolean roundTrip = passwordEncoder.matches(probe, encoded);
            log.info("[R134AB][CredentialService] BCrypt self-test: encoderClass={} encodedPrefix='{}' roundTripOk={}",
                    passwordEncoder.getClass().getSimpleName(),
                    prefix(encoded, 7),
                    roundTrip);
            if (!roundTrip) {
                log.error("[R134AB][CredentialService] BCrypt self-test FAILED — library round-trip "
                        + "broken; ALL password authentications will be rejected");
            }
        } catch (Exception e) {
            log.error("[R134AB][CredentialService] BCrypt self-test threw: {}", e.toString(), e);
        }
    }

    /** First N chars of s (null-safe, bounds-safe). Used to surface the BCrypt variant + cost. */
    private static String prefix(String s, int n) {
        if (s == null) return "<null>";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /**
     * R134AB — one-way fingerprint of the raw password for log diagnostics.
     * SHA-256, first 8 hex chars only — not reversible in practice, but
     * differs if the received bytes differ (trim / charset / NFC-vs-NFD).
     * Safe to log at WARN level on auth failures.
     */
    private static String sha256Head(String s) {
        if (s == null) return "<null>";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "<sha256-unavailable>";
        }
    }

    public boolean authenticatePassword(String username, String password, String ipAddress,
                                         String listenerInstanceId) {
        Optional<TransferAccount> opt = findAccount(username, listenerInstanceId);
        if (opt.isEmpty()) {
            // R134W — diagnostic: SftpPasswordAuthenticator collapses both
            // "user not found" and "wrong password" into one generic
            // invalid_credentials log. Without this, the R134V SFTP auth
            // regression cannot be narrowed: is the instance-scoped lookup
            // orphaning globalbank-sftp post-rotation, or is the hash on
            // disk actually diverging? This line makes the first case
            // observable without a DB round-trip into audit_log.
            String resolvedInstance = listenerInstanceId != null ? listenerInstanceId : defaultInstanceId;
            log.warn("[CredentialService] auth DENIED — no SFTP account for username='{}' "
                    + "resolvedInstance='{}' (listenerInstanceId='{}' defaultInstanceId='{}')",
                    username, resolvedInstance, listenerInstanceId, defaultInstanceId);
            logAudit(null, "LOGIN_FAIL", null, ipAddress, Map.of("reason", "user not found"));
            return false;
        }

        TransferAccount account = opt.get();
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            // R134W — baseline: distinguishes hash-mismatch from no-account.
            // R134AB — bytes-level diagnostic for the R134V regression that
            // R134AA confirmed is server-side: Python's bcrypt.checkpw()
            // returns True for the seed hash + `partner123`, Java's
            // BCryptPasswordEncoder.matches() returns False on the same
            // pair. Next logical question is "what bytes are being
            // compared?" — so this line now also emits the received
            // password's length + a SHA-256-prefix fingerprint (one-way,
            // non-reversible, safe in logs) AND the stored hash prefix
            // (reveals BCrypt variant + cost factor, e.g. `$2a$10$`).
            //
            // A fingerprint divergence between expected `partner123` and
            // the logged value would prove a transport-level mangling
            // (charset, trim, Unicode normalisation) upstream of the
            // BCrypt call. A matching fingerprint with the `$2a$` variant
            // and the correct stored hash would narrow the blame to the
            // BCrypt library itself (see @PostConstruct self-test below).
            String storedHash = account.getPasswordHash();
            log.warn("[CredentialService] auth DENIED — password mismatch for username='{}' "
                    + "accountId={} storedHashLen={} storedHashPrefix='{}' "
                    + "passwordLen={} passwordSha256Head={} active={} instance='{}'",
                    username, account.getId(),
                    storedHash != null ? storedHash.length() : -1,
                    prefix(storedHash, 7),
                    password != null ? password.length() : -1,
                    sha256Head(password),
                    account.isActive(),
                    listenerInstanceId != null ? listenerInstanceId : defaultInstanceId);
            logAudit(account, "LOGIN_FAIL", null, ipAddress, Map.of("reason", "wrong password"));
            return false;
        }

        logAudit(account, "LOGIN", null, ipAddress, null);
        return true;
    }

    public boolean authenticatePublicKey(String username, String providedKey, String ipAddress,
                                          String listenerInstanceId) {
        Optional<TransferAccount> opt = findAccount(username, listenerInstanceId);
        if (opt.isEmpty()) return false;

        TransferAccount account = opt.get();
        if (account.getPublicKey() == null) return false;

        // Compare trimmed keys (ignore trailing whitespace/newline differences)
        boolean match = account.getPublicKey().trim().equals(providedKey.trim());
        if (match) {
            logAudit(account, "LOGIN", null, ipAddress, Map.of("authMethod", "publickey"));
        } else {
            logAudit(account, "LOGIN_FAIL", null, ipAddress, Map.of("reason", "key mismatch"));
        }
        return match;
    }

    /**
     * Resolve an account scoped to a specific listener. Falls back to the
     * default (env-var primary) instance when {@code listenerInstanceId} is
     * null — preserves backwards compatibility for code paths that haven't
     * been threaded yet.
     */
    public Optional<TransferAccount> findAccount(String username, String listenerInstanceId) {
        String resolvedInstance = listenerInstanceId != null ? listenerInstanceId : defaultInstanceId;
        String cacheKey = username + "|" + (resolvedInstance != null ? resolvedInstance : "*");

        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.account());
        }
        if (entry != null && entry.isExpired()) {
            cache.remove(cacheKey);
        }

        Optional<TransferAccount> dbAccount;
        if (resolvedInstance != null) {
            dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.SFTP, resolvedInstance);
        } else {
            dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);
        }
        dbAccount.ifPresent(a -> cache.put(cacheKey,
                new CacheEntry(a, System.currentTimeMillis() + CACHE_TTL_MS)));
        return dbAccount;
    }

    /** Backward-compat overload — resolves against the env-var primary. */
    public Optional<TransferAccount> findAccount(String username) {
        return findAccount(username, null);
    }

    public void evictFromCache(String username) {
        log.info("Evicting credential cache for username={}", username);
        // Remove all instance-scoped entries for this username.
        cache.keySet().removeIf(k -> k.startsWith(username + "|"));
    }

    private void logAudit(TransferAccount account, String action, String path,
                           String ipAddress, Map<String, Object> metadata) {
        if (account == null) return;
        try {
            auditLogRepository.save(AuditLog.builder()
                    .account(account)
                    .action(action)
                    .path(path)
                    .ipAddress(ipAddress)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }
}
