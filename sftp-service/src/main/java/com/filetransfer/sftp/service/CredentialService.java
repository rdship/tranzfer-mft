package com.filetransfer.sftp.service;

import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.AuditLogRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    public boolean authenticatePassword(String username, String password, String ipAddress,
                                         String listenerInstanceId) {
        Optional<TransferAccount> opt = findAccount(username, listenerInstanceId);
        if (opt.isEmpty()) {
            logAudit(null, "LOGIN_FAIL", null, ipAddress, Map.of("reason", "user not found"));
            return false;
        }

        TransferAccount account = opt.get();
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
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
