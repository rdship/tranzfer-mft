package com.filetransfer.sftp.server;

import com.filetransfer.sftp.audit.AuditEventLogger;
import com.filetransfer.sftp.security.IpAccessControl;
import com.filetransfer.sftp.security.LoginAttemptTracker;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.session.ConnectionManager;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.security.PublicKey;
import java.util.List;

/**
 * Public key authenticator with failed-login lockout tracking, IP access control,
 * and structured audit logging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpPublicKeyAuthenticator implements PublickeyAuthenticator {

    private final CredentialService credentialService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final IpAccessControl ipAccessControl;
    private final AuditEventLogger auditEventLogger;
    private final BandwidthThrottleManager bandwidthThrottleManager;
    private final ConnectionManager connectionManager;

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        String ip = session.getClientAddress() != null
                ? session.getClientAddress().toString()
                : "unknown";
        String listenerInstanceId = ListenerContext.instanceId(session);

        // Check IP access control
        if (!ipAccessControl.isAllowed(ip)) {
            auditEventLogger.logLoginFailed(username, ip, "ip_denied", session);
            return false;
        }

        // Check account lockout
        if (loginAttemptTracker.isLocked(username)) {
            log.warn("SFTP pubkey auth rejected: account locked username={} ip={}", username, ip);
            auditEventLogger.logLoginFailed(username, ip, "account_locked", session);
            return false;
        }

        return credentialService.findAccount(username, listenerInstanceId).map(account -> {
            if (account.getPublicKey() == null) return false;
            try {
                // Parse the stored authorized_keys line and compare
                List<AuthorizedKeyEntry> entries = AuthorizedKeyEntry.readAuthorizedKeys(
                        new StringReader(account.getPublicKey()), true);
                for (AuthorizedKeyEntry entry : entries) {
                    PublicKey stored = entry.resolvePublicKey(null, null, null);
                    if (stored != null && stored.equals(key)) {
                        log.info("SFTP publickey auth success: username={}", username);
                        loginAttemptTracker.recordSuccess(username);
                        auditEventLogger.logLogin(username, ip, "publickey", session);
                        // Register per-user QoS: bandwidth limits + session limit
                        bandwidthThrottleManager.registerUserLimits(username,
                            account.getQosUploadBytesPerSecond(),
                            account.getQosDownloadBytesPerSecond(),
                            account.getQosBurstAllowancePercent());
                        connectionManager.registerQosSessionLimit(username,
                            account.getQosMaxConcurrentSessions());
                        return true;
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing public key for user {}: {}", username, e.getMessage());
            }
            log.warn("SFTP publickey auth failed: username={} ip={}", username, ip);
            loginAttemptTracker.recordFailure(username);
            auditEventLogger.logLoginFailed(username, ip, "key_mismatch", session);
            return false;
        }).orElse(false);
    }
}
