package com.filetransfer.ftp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks failed login attempts and enforces account lockout after a
 * configurable threshold. Lockout state is held in memory; a restart
 * clears all lockouts (by design -- persistent lockout is handled at the
 * DB/account level if needed).
 *
 * <p>Thread-safe: all mutable state lives in {@link ConcurrentHashMap}s.
 */
@Slf4j
@Service
public class LoginLockoutService {

    @Value("${ftp.security.max-login-failures:5}")
    private int maxFailures;

    @Value("${ftp.security.lockout-duration-seconds:900}")
    private int lockoutDurationSeconds;

    /** username -> running count of consecutive failures */
    private final ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();

    /** username -> instant when the lockout expires */
    private final ConcurrentHashMap<String, Instant> lockoutExpiry = new ConcurrentHashMap<>();

    /**
     * Check whether a user account is currently locked out.
     *
     * @param username the FTP username
     * @return {@code true} if the account is locked and the lockout has not yet expired
     */
    public boolean isLockedOut(String username) {
        Instant expiry = lockoutExpiry.get(username);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            // lockout expired -- clear state
            lockoutExpiry.remove(username);
            failureCounts.remove(username);
            return false;
        }
        return true;
    }

    /**
     * Record a failed login attempt. If the failure count reaches
     * {@code ftp.security.max-login-failures}, the account is locked for
     * {@code ftp.security.lockout-duration-seconds}.
     *
     * @param username the FTP username
     */
    public void recordFailure(String username) {
        int count = failureCounts.merge(username, 1, Integer::sum);
        if (count >= maxFailures) {
            Instant expiry = Instant.now().plusSeconds(lockoutDurationSeconds);
            lockoutExpiry.put(username, expiry);
            log.warn("Account locked: username={} failures={} lockout_until={}", username, count, expiry);
        }
    }

    /**
     * Clear failure tracking after a successful login.
     *
     * @param username the FTP username
     */
    public void recordSuccess(String username) {
        failureCounts.remove(username);
        lockoutExpiry.remove(username);
    }

    /**
     * Return the set of currently locked-out usernames (for the health endpoint).
     *
     * @return unmodifiable set of locked usernames
     */
    public Set<String> getLockedAccounts() {
        Instant now = Instant.now();
        return lockoutExpiry.entrySet().stream()
                .filter(e -> now.isBefore(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Return the failure count for a given user (0 if none tracked).
     *
     * @param username the FTP username
     * @return current consecutive failure count
     */
    public int getFailureCount(String username) {
        return failureCounts.getOrDefault(username, 0);
    }
}
