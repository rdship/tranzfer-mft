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

        // R134AG — reject zero-byte password before any credential work so
        // client probes (Mina "none" phase, macOS OpenSSH hostkey-distrust
        // empty-password dispatch, publickey-fallthrough) do NOT burn a
        // lockout attempt and do NOT emit a misleading "invalid_credentials"
        // audit event. R134AB bytes-level decoder proved these arrive as
        // passwordLen=0 / SHA-256 e3b0c442 (SHA-256 of empty string);
        // R134AF run report §2 falsified the old "Java bcrypt rejects
        // partner123" theory — the server never sees partner123 in those
        // trips, it sees zero bytes. Returning false here tells the client
        // "this auth method failed"; the client will then offer the next
        // method (keyboard-interactive / publickey) without racking up
        // counted DENY events.
        if (password == null || password.isEmpty()) {
            log.info("[R134AG][SftpAuth] rejecting zero-byte password probe username={} ip={} listener={} (not counted toward lockout)",
                    username, ip, listenerInstanceId != null ? listenerInstanceId : "<primary>");
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
