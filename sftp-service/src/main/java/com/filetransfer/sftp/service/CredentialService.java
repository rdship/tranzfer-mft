package com.filetransfer.sftp.service;

import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.AuditLogRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
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

    // Simple in-memory cache: username → account. Busted by RabbitMQ events.
    private final ConcurrentHashMap<String, TransferAccount> cache = new ConcurrentHashMap<>();

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
        TransferAccount cached = cache.get(username);
        if (cached != null) return Optional.of(cached);

        Optional<TransferAccount> dbAccount;
        if (instanceId != null) {
            // Instance-aware: only accept users assigned to this instance or unassigned
            dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.SFTP, instanceId);
        } else {
            dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);
        }
        dbAccount.ifPresent(a -> cache.put(username, a));
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
