package com.filetransfer.sftp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed login attempts and enforces account lockout after a configurable
 * number of consecutive failures. Lockout duration is also configurable.
 *
 * <p>Uses an in-memory store keyed by username. Lockout state is not persisted
 * across restarts; this is intentional to avoid permanent lockouts after crashes.</p>
 */
@Slf4j
@Component
public class LoginAttemptTracker {

    @Value("${sftp.auth.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${sftp.auth.lockout-duration-seconds:900}")
    private int lockoutDurationSeconds;

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Checks whether the given username is currently locked out.
     *
     * @param username the username to check
     * @return true if the account is locked
     */
    public boolean isLocked(String username) {
        if (maxFailedAttempts <= 0) return false;

        AttemptRecord record = attempts.get(username);
        if (record == null) return false;

        if (record.lockedUntil != null && Instant.now().isBefore(record.lockedUntil)) {
            return true;
        }

        // Lockout expired: reset
        if (record.lockedUntil != null && !Instant.now().isBefore(record.lockedUntil)) {
            attempts.remove(username);
            return false;
        }

        return false;
    }

    /**
     * Records a failed login attempt for the given username. If the failure
     * count exceeds the configured maximum, the account is locked for
     * the configured duration.
     *
     * @param username the username that failed authentication
     */
    public void recordFailure(String username) {
        if (maxFailedAttempts <= 0) return;

        AttemptRecord record = attempts.computeIfAbsent(username, k -> new AttemptRecord());
        record.failureCount++;
        record.lastFailure = Instant.now();

        if (record.failureCount >= maxFailedAttempts) {
            record.lockedUntil = Instant.now().plusSeconds(lockoutDurationSeconds);
            log.warn("Account locked: username={} failures={} lockedUntil={}",
                    username, record.failureCount, record.lockedUntil);
        }
    }

    /**
     * Clears the failure record for the given username. Called on successful login.
     *
     * @param username the username to reset
     */
    public void recordSuccess(String username) {
        attempts.remove(username);
    }

    /**
     * Returns a snapshot of currently locked accounts with their unlock times.
     */
    public Map<String, Instant> getLockedAccounts() {
        Map<String, Instant> locked = new LinkedHashMap<>();
        Instant now = Instant.now();
        attempts.forEach((username, record) -> {
            if (record.lockedUntil != null && now.isBefore(record.lockedUntil)) {
                locked.put(username, record.lockedUntil);
            }
        });
        return Collections.unmodifiableMap(locked);
    }

    /**
     * Returns total number of tracked usernames (including non-locked).
     */
    public int getTrackedCount() {
        return attempts.size();
    }

    /**
     * Returns the configured maximum failed attempts before lockout.
     */
    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    /**
     * Returns the configured lockout duration in seconds.
     */
    public int getLockoutDurationSeconds() {
        return lockoutDurationSeconds;
    }

    private static class AttemptRecord {
        int failureCount;
        Instant lastFailure;
        Instant lockedUntil;
    }
}
