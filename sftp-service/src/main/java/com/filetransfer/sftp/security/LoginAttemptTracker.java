package com.filetransfer.sftp.security;

import com.filetransfer.shared.entity.security.LoginAttempt;
import com.filetransfer.shared.repository.security.LoginAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Tracks failed login attempts and enforces account lockout after a configurable
 * number of consecutive failures. Lockout duration is also configurable.
 *
 * <p>Uses the database for storage so lockout state is consistent across all
 * service replicas. A user locked on replica-1 is also locked on replica-2.</p>
 */
@Slf4j
@Component
public class LoginAttemptTracker {

    @Value("${sftp.auth.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${sftp.auth.lockout-duration-seconds:900}")
    private int lockoutDurationSeconds;

    private final LoginAttemptRepository repository;

    public LoginAttemptTracker(LoginAttemptRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks whether the given username is currently locked out.
     */
    @Transactional(readOnly = true)
    public boolean isLocked(String username) {
        if (maxFailedAttempts <= 0) return false;

        return repository.findByUsername(username)
                .map(attempt -> {
                    if (attempt.getLockedUntil() == null) return false;
                    if (Instant.now().isBefore(attempt.getLockedUntil())) return true;
                    // Lockout expired — will be cleaned on next failure or success
                    return false;
                })
                .orElse(false);
    }

    /**
     * Records a failed login attempt. If failures exceed the configured maximum,
     * the account is locked for the configured duration across all replicas.
     */
    @Transactional
    public void recordFailure(String username) {
        if (maxFailedAttempts <= 0) return;

        LoginAttempt attempt = repository.findByUsername(username)
                .orElseGet(() -> {
                    LoginAttempt a = new LoginAttempt();
                    a.setUsername(username);
                    return a;
                });

        // If lockout expired, reset count
        if (attempt.getLockedUntil() != null && !Instant.now().isBefore(attempt.getLockedUntil())) {
            attempt.setFailureCount(0);
            attempt.setLockedUntil(null);
        }

        attempt.setFailureCount(attempt.getFailureCount() + 1);
        attempt.setLastFailureAt(Instant.now());

        if (attempt.getFailureCount() >= maxFailedAttempts) {
            attempt.setLockedUntil(Instant.now().plusSeconds(lockoutDurationSeconds));
            log.warn("Account locked: username={} failures={} lockedUntil={}",
                    username, attempt.getFailureCount(), attempt.getLockedUntil());
        }

        repository.save(attempt);
    }

    /**
     * Clears the failure record on successful login.
     */
    @Transactional
    public void recordSuccess(String username) {
        repository.deleteByUsername(username);
    }

    /**
     * Returns a snapshot of currently locked accounts with their unlock times.
     */
    @Transactional(readOnly = true)
    public Map<String, Instant> getLockedAccounts() {
        Map<String, Instant> locked = new LinkedHashMap<>();
        repository.findByLockedUntilAfter(Instant.now())
                .forEach(a -> locked.put(a.getUsername(), a.getLockedUntil()));
        return Collections.unmodifiableMap(locked);
    }

    /**
     * Returns total number of tracked usernames (including non-locked).
     */
    @Transactional(readOnly = true)
    public int getTrackedCount() {
        return (int) repository.count();
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public int getLockoutDurationSeconds() {
        return lockoutDurationSeconds;
    }
}
