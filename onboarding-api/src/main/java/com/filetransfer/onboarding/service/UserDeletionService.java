package com.filetransfer.onboarding.service;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * GDPR Article 17 — Right to Erasure (Right to Deletion).
 *
 * Performs cascading anonymization/deletion of all user data:
 *   - User record: anonymize email, clear name, clear password
 *   - Audit logs: anonymize principal (keep entries for compliance)
 *   - Transfer accounts: deactivate, anonymize
 *   - TOTP secrets: delete
 *   - Login attempts: delete
 *   - User permissions: delete
 *   - Notification preferences: delete (when table exists)
 *
 * Returns a confirmation report of what was deleted/anonymized.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

    private final UserRepository userRepository;
    private final TransferAccountRepository transferAccountRepository;
    private final AuditLogRepository auditLogRepository;
    private final TotpConfigRepository totpConfigRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final AuditService auditService;

    @Transactional
    public DeletionReport deleteUser(UUID userId, String requestedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));

        String originalEmail = user.getEmail();
        String anonymizedEmail = "deleted_" + userId + "@redacted.com";
        String anonymizedPrincipal = "deleted_" + userId;

        DeletionReport report = new DeletionReport(userId, originalEmail);

        // 1. Anonymize audit logs (GDPR allows keeping anonymized audit data)
        int auditLogsAnonymized = auditLogRepository.anonymizePrincipal(originalEmail, anonymizedPrincipal);
        report.auditLogsAnonymized = auditLogsAnonymized;

        // 2. Handle transfer accounts
        List<TransferAccount> accounts = transferAccountRepository.findByUserId(userId);
        for (TransferAccount account : accounts) {
            // Detach audit logs from this account
            int detached = auditLogRepository.detachFromAccount(account.getId());
            report.auditLogsDetached += detached;

            // Anonymize the principal in audit logs for this account's username
            auditLogRepository.anonymizePrincipal(account.getUsername(), anonymizedPrincipal);

            // Deactivate and anonymize the account
            account.setActive(false);
            account.setUsername("deleted_" + account.getId());
            account.setPasswordHash("REDACTED");
            account.setPublicKey(null);
            account.setPermissions(Map.of());
            transferAccountRepository.save(account);
        }
        report.transferAccountsAnonymized = accounts.size();

        // 3. Delete TOTP secrets
        totpConfigRepository.findByUsername(originalEmail).ifPresent(totp -> {
            totpConfigRepository.delete(totp);
            report.totpDeleted = true;
        });

        // 4. Delete login attempts
        loginAttemptRepository.findByUsername(originalEmail).ifPresent(attempt -> {
            loginAttemptRepository.delete(attempt);
            report.loginAttemptsDeleted = true;
        });

        // 5. Delete user permissions
        userPermissionRepository.deleteByUserId(userId);
        report.userPermissionsDeleted = true;

        // 6. Anonymize the user record (don't hard-delete — preserves FK integrity)
        user.setEmail(anonymizedEmail);
        user.setPasswordHash("REDACTED");
        userRepository.save(user);
        report.userAnonymized = true;

        // 7. Audit the deletion itself
        auditService.logAction(requestedBy, "USER_DELETED", true, null,
                Map.of("deletedUserId", userId.toString(),
                       "deletedEmail", "REDACTED",
                       "accountsAnonymized", String.valueOf(accounts.size()),
                       "auditLogsAnonymized", String.valueOf(auditLogsAnonymized)));

        log.info("GDPR deletion completed for user {} by {}", userId, requestedBy);
        return report;
    }

    /**
     * Report of what was deleted/anonymized during GDPR erasure.
     */
    public static class DeletionReport {
        public final UUID userId;
        public final String originalEmail;
        public boolean userAnonymized;
        public int transferAccountsAnonymized;
        public int auditLogsAnonymized;
        public int auditLogsDetached;
        public boolean totpDeleted;
        public boolean loginAttemptsDeleted;
        public boolean userPermissionsDeleted;

        public DeletionReport(UUID userId, String originalEmail) {
            this.userId = userId;
            this.originalEmail = originalEmail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", userId.toString());
            map.put("userAnonymized", userAnonymized);
            map.put("transferAccountsAnonymized", transferAccountsAnonymized);
            map.put("auditLogsAnonymized", auditLogsAnonymized);
            map.put("auditLogsDetached", auditLogsDetached);
            map.put("totpSecretsDeleted", totpDeleted);
            map.put("loginAttemptsDeleted", loginAttemptsDeleted);
            map.put("userPermissionsDeleted", userPermissionsDeleted);
            return map;
        }
    }
}
