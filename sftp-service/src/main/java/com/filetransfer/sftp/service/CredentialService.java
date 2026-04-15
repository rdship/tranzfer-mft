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
    private String instanceId;

    // In-memory cache with TTL: username → (account, expiry). Busted by RabbitMQ events + auto-expiry.
    // H7 fix: 60s TTL prevents stale entries when RabbitMQ event is delayed or lost.
    private static final long CACHE_TTL_MS = 60_000;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(TransferAccount account, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public boolean authenticatePassword(String username, String password, String ipAddress) {
        Optional<TransferAccount> opt = findAccount(username);
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

    public boolean authenticatePublicKey(String username, String providedKey, String ipAddress) {
        Optional<TransferAccount> opt = findAccount(username);
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

    public Optional<TransferAccount> findAccount(String username) {
        // H7 fix: TTL-based cache — stale entries auto-expire after 60s
        CacheEntry entry = cache.get(username);
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.account());
        }
        if (entry != null && entry.isExpired()) {
            cache.remove(username); // Clean up expired entry
        }

        Optional<TransferAccount> dbAccount;
        if (instanceId != null) {
            dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.SFTP, instanceId);
        } else {
            dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);
        }
        dbAccount.ifPresent(a -> cache.put(username,
                new CacheEntry(a, System.currentTimeMillis() + CACHE_TTL_MS)));
        return dbAccount;
    }

    public void evictFromCache(String username) {
        log.info("Evicting credential cache for username={}", username);
        cache.remove(username);
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
