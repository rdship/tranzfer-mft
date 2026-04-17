package com.filetransfer.ftp.service;

import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.core.AuditLogRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${ftp.instance-id:#{null}}")
    private String defaultInstanceId;

    // TTL cache keyed by username|instanceId so overlapping accounts on
    // different listeners don't collide.
    private static final long CACHE_TTL_MS = 60_000;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(TransferAccount account, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public boolean authenticate(String username, String password, String ipAddress) {
        // Resolve the arriving listener via FtpListenerContext ThreadLocal
        // (set by per-listener Ftplet). Null → fall through to env-var primary.
        Optional<TransferAccount> opt = findAccount(username);
        if (opt.isEmpty()) {
            log.warn("FTP login failed - user not found: {}", username);
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

    public Optional<TransferAccount> findAccount(String username) {
        String listenerInstanceId = com.filetransfer.ftp.server.FtpListenerContext.instanceId();
        String resolved = listenerInstanceId != null ? listenerInstanceId : defaultInstanceId;
        String cacheKey = username + "|" + (resolved != null ? resolved : "*");

        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.account());
        }
        if (entry != null) cache.remove(cacheKey);

        Optional<TransferAccount> dbAccount;
        if (resolved != null) {
            dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.FTP, resolved);
        } else {
            dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        }
        dbAccount.ifPresent(a -> cache.put(cacheKey,
                new CacheEntry(a, System.currentTimeMillis() + CACHE_TTL_MS)));
        return dbAccount;
    }

    public void evictFromCache(String username) {
        log.info("Evicting FTP credential cache for username={}", username);
        cache.keySet().removeIf(k -> k.startsWith(username + "|"));
    }

    private void logAudit(TransferAccount account, String action, String path,
                           String ipAddress, Map<String, Object> metadata) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .account(account)
                    .action(action)
                    .path(path)
                    .ipAddress(ipAddress)
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to write FTP audit log: {}", e.getMessage());
        }
    }
}
