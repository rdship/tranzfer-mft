package com.filetransfer.sftp.server;

import com.filetransfer.sftp.audit.AuditEventLogger;
import com.filetransfer.sftp.security.IpAccessControl;
import com.filetransfer.sftp.security.LoginAttemptTracker;
import com.filetransfer.sftp.service.CredentialService;
import com.filetransfer.sftp.throttle.BandwidthThrottleManager;
import com.filetransfer.shared.entity.TransferAccount;
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

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        String ip = session.getClientAddress() != null
                ? session.getClientAddress().toString()
                : "unknown";
        log.info("SFTP password auth attempt: username={} ip={}", username, ip);

        // Check IP access control
        if (!ipAccessControl.isAllowed(ip)) {
            auditEventLogger.logLoginFailed(username, ip, "ip_denied");
            return false;
        }

        // Check account lockout
        if (loginAttemptTracker.isLocked(username)) {
            log.warn("SFTP auth rejected: account locked username={} ip={}", username, ip);
            auditEventLogger.logLoginFailed(username, ip, "account_locked");
            return false;
        }

        boolean authenticated = credentialService.authenticatePassword(username, password, ip);

        if (authenticated) {
            loginAttemptTracker.recordSuccess(username);
            auditEventLogger.logLogin(username, ip, "password");
            // Register per-user QoS bandwidth limits
            credentialService.findAccount(username).ifPresent(account ->
                bandwidthThrottleManager.registerUserLimits(username,
                    account.getQosUploadBytesPerSecond(),
                    account.getQosDownloadBytesPerSecond()));
        } else {
            loginAttemptTracker.recordFailure(username);
            auditEventLogger.logLoginFailed(username, ip, "invalid_credentials");
        }

        return authenticated;
    }
}
