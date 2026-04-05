package com.filetransfer.ftp.service;

import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.AuditLogRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
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
    private String instanceId;

    private final ConcurrentHashMap<String, TransferAccount> cache = new ConcurrentHashMap<>();

    public boolean authenticate(String username, String password, String ipAddress) {
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
        TransferAccount cached = cache.get(username);
        if (cached != null) return Optional.of(cached);

        Optional<TransferAccount> dbAccount;
        if (instanceId != null) {
            // Instance-aware: only accept users assigned to this instance or unassigned
            dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
                    username, Protocol.FTP, instanceId);
        } else {
            dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.FTP);
        }
        dbAccount.ifPresent(a -> cache.put(username, a));
        return dbAccount;
    }

    public void evictFromCache(String username) {
        log.info("Evicting FTP credential cache for username={}", username);
        cache.remove(username);
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
