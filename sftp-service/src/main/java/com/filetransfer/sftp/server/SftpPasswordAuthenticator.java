package com.filetransfer.sftp.server;

import com.filetransfer.sftp.audit.AuditEventLogger;
import com.filetransfer.sftp.security.IpAccessControl;
import com.filetransfer.sftp.security.LoginAttemptTracker;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.session.ConnectionManager;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import com.filetransfer.shared.entity.core.TransferAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

/**
 * Password authenticator with failed-login lockout tracking, IP access control,
 * and structured audit logging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SftpPasswordAuthenticator implements PasswordAuthenticator {

    private final CredentialService credentialService;
    private final LoginAttemptTracker loginAttemptTracker;
    private final IpAccessControl ipAccessControl;
    private final AuditEventLogger auditEventLogger;
    private final BandwidthThrottleManager bandwidthThrottleManager;
    private final ConnectionManager connectionManager;

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        String ip = session.getClientAddress() != null
                ? session.getClientAddress().toString()
                : "unknown";
        // Listener this session arrived on — null for legacy/untagged sessions;
        // CredentialService falls back to the env-var primary in that case.
        String listenerInstanceId = ListenerContext.instanceId(session);
        log.info("SFTP password auth attempt: username={} ip={} listener={}",
                username, ip, listenerInstanceId != null ? listenerInstanceId : "<primary>");

        // Check IP access control
        if (!ipAccessControl.isAllowed(ip)) {
            auditEventLogger.logLoginFailed(username, ip, "ip_denied", session);
            return false;
        }

        // Check account lockout
        if (loginAttemptTracker.isLocked(username)) {
            log.warn("SFTP auth rejected: account locked username={} ip={}", username, ip);
            auditEventLogger.logLoginFailed(username, ip, "account_locked", session);
            return false;
        }

        boolean authenticated = credentialService.authenticatePassword(username, password, ip, listenerInstanceId);

        if (authenticated) {
            loginAttemptTracker.recordSuccess(username);
            auditEventLogger.logLogin(username, ip, "password", session);
            // Register per-user QoS: bandwidth limits + session limit
            credentialService.findAccount(username, listenerInstanceId).ifPresent(account -> {
                bandwidthThrottleManager.registerUserLimits(username,
                    account.getQosUploadBytesPerSecond(),
                    account.getQosDownloadBytesPerSecond(),
                    account.getQosBurstAllowancePercent());
                connectionManager.registerQosSessionLimit(username,
                    account.getQosMaxConcurrentSessions());
            });
        } else {
            loginAttemptTracker.recordFailure(username);
            auditEventLogger.logLoginFailed(username, ip, "invalid_credentials", session);
        }

        return authenticated;
    }
}
