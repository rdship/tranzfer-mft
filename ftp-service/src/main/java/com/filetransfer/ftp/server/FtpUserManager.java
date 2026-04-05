package com.filetransfer.ftp.server;

import com.filetransfer.ftp.audit.AuditEventLogger;
import com.filetransfer.ftp.security.IpFilterService;
import com.filetransfer.ftp.security.LoginLockoutService;
import com.filetransfer.ftp.service.CredentialService;
import com.filetransfer.ftp.throttle.BandwidthThrottleService;
import com.filetransfer.shared.entity.TransferAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.usermanager.AnonymousAuthentication;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.AbstractUserManager;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom FTP user manager backed by the platform database.
 *
 * <p>Integrates with:
 * <ul>
 *   <li>{@link LoginLockoutService} -- account lockout after repeated failures</li>
 *   <li>{@link IpFilterService} -- IP allowlist/denylist enforcement</li>
 *   <li>{@link AuditEventLogger} -- structured JSON audit events</li>
 *   <li>{@link BandwidthThrottleService} -- per-user upload/download rate limits</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FtpUserManager extends AbstractUserManager {

    private final CredentialService credentialService;
    private final LoginLockoutService lockoutService;
    private final IpFilterService ipFilterService;
    private final AuditEventLogger auditEventLogger;
    private final BandwidthThrottleService bandwidthThrottleService;

    @Value("${ftp.connection.max-per-user:10}")
    private int maxConnectionsPerUser;

    @Value("${ftp.connection.max-per-ip:10}")
    private int maxConnectionsPerIp;

    @Value("${ftp.idle-timeout-seconds:300}")
    private int idleTimeoutSeconds;

    @Value("${ftp.anonymous.enabled:false}")
    private boolean anonymousEnabled;

    @Value("${ftp.anonymous.home-dir:${ftp.home-base:/data/ftp}/anonymous}")
    private String anonymousHomeDir;

    @Override
    public User getUserByName(String username) throws FtpException {
        if ("anonymous".equals(username) && anonymousEnabled) {
            return buildAnonymousUser();
        }
        return credentialService.findAccount(username)
                .map(this::toFtpUser)
                .orElse(null);
    }

    @Override
    public String[] getAllUserNames() throws FtpException {
        return new String[0];
    }

    @Override
    public void delete(String username) throws FtpException {
        throw new FtpException("Deletion must go through the Onboarding API");
    }

    @Override
    public void save(User user) throws FtpException {
        throw new FtpException("User management must go through the Onboarding API");
    }

    @Override
    public boolean doesExist(String username) throws FtpException {
        if ("anonymous".equals(username) && anonymousEnabled) {
            return true;
        }
        return credentialService.findAccount(username).isPresent();
    }

    /**
     * Authenticate an FTP user with lockout and IP filtering enforcement.
     *
     * @param authentication the authentication request
     * @return the authenticated FTP user
     * @throws AuthenticationFailedException if credentials are invalid or the account is locked
     */
    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        // Handle anonymous login
        if (authentication instanceof AnonymousAuthentication anonAuth) {
            if (!anonymousEnabled) {
                throw new AuthenticationFailedException("Anonymous login is disabled");
            }
            String ip = extractIp(anonAuth);
            if (!ipFilterService.isAllowed(ip)) {
                throw new AuthenticationFailedException("Connection denied from IP: " + ip);
            }
            auditEventLogger.logEvent("LOGIN", "anonymous", ip, Map.of("auth_type", "anonymous"));
            try {
                return getUserByName("anonymous");
            } catch (FtpException e) {
                throw new AuthenticationFailedException("Anonymous user setup failed", e);
            }
        }

        if (!(authentication instanceof UsernamePasswordAuthentication upAuth)) {
            throw new AuthenticationFailedException("Only username/password authentication is supported");
        }

        String username = upAuth.getUsername();
        String password = upAuth.getPassword();
        String ip = "unknown";
        if (upAuth.getUserMetadata() != null && upAuth.getUserMetadata().getInetAddress() != null) {
            ip = upAuth.getUserMetadata().getInetAddress().getHostAddress();
        }

        // IP filter check
        if (!ipFilterService.isAllowed(ip)) {
            auditEventLogger.logEvent("LOGIN_FAILED", username, ip,
                    Map.of("reason", "ip_denied"));
            throw new AuthenticationFailedException("Connection denied from IP: " + ip);
        }

        // Lockout check
        if (lockoutService.isLockedOut(username)) {
            auditEventLogger.logEvent("LOGIN_FAILED", username, ip,
                    Map.of("reason", "account_locked"));
            throw new AuthenticationFailedException("Account temporarily locked due to repeated failures: " + username);
        }

        if (!credentialService.authenticate(username, password, ip)) {
            lockoutService.recordFailure(username);
            auditEventLogger.logEvent("LOGIN_FAILED", username, ip,
                    Map.of("reason", "invalid_credentials",
                            "failure_count", lockoutService.getFailureCount(username)));
            throw new AuthenticationFailedException("Invalid credentials for user: " + username);
        }

        // Successful login
        lockoutService.recordSuccess(username);
        auditEventLogger.logEvent("LOGIN", username, ip);

        try {
            return getUserByName(username);
        } catch (FtpException e) {
            throw new AuthenticationFailedException("User lookup failed after successful auth", e);
        }
    }

    private BaseUser toFtpUser(TransferAccount account) {
        BaseUser user = new BaseUser();
        user.setName(account.getUsername());
        user.setHomeDirectory(account.getHomeDir());
        user.setEnabled(account.isActive());
        user.setMaxIdleTime(idleTimeoutSeconds);

        List<Authority> authorities = new ArrayList<>();
        authorities.add(new ConcurrentLoginPermission(maxConnectionsPerUser, maxConnectionsPerIp));

        Map<String, Boolean> perms = account.getPermissions();
        if (perms != null && Boolean.TRUE.equals(perms.get("write"))) {
            authorities.add(new WritePermission());
        }

        // Bandwidth throttle
        Authority ratePermission = bandwidthThrottleService.createRatePermission();
        if (ratePermission != null) {
            authorities.add(ratePermission);
        }

        user.setAuthorities(authorities);
        return user;
    }

    /**
     * Build a read-only anonymous FTP user with restricted permissions.
     */
    private BaseUser buildAnonymousUser() {
        BaseUser user = new BaseUser();
        user.setName("anonymous");
        user.setHomeDirectory(anonymousHomeDir);
        user.setEnabled(true);
        user.setMaxIdleTime(idleTimeoutSeconds);

        List<Authority> authorities = new ArrayList<>();
        // Anonymous gets very restricted concurrent sessions
        authorities.add(new ConcurrentLoginPermission(3, 2));
        // No write permission for anonymous by default
        user.setAuthorities(authorities);
        return user;
    }

    private String extractIp(AnonymousAuthentication anonAuth) {
        try {
            if (anonAuth.getUserMetadata() != null && anonAuth.getUserMetadata().getInetAddress() != null) {
                return anonAuth.getUserMetadata().getInetAddress().getHostAddress();
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
}
