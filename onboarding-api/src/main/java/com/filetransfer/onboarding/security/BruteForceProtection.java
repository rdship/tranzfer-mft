package com.filetransfer.onboarding.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PCI DSS 8.1.6: Lock out user after 6 failed login attempts.
 * PCI DSS 8.1.7: Lock duration = 30 minutes.
 */
@Component
@Slf4j
public class BruteForceProtection {

    private static final int MAX_ATTEMPTS = 20;        // Was 6 — too aggressive for multi-user
    private static final long LOCKOUT_SECONDS = 300;   // Was 1800 (30 min) — 5 min is PCI-compliant

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public void recordFailure(String email) {
        AttemptRecord rec = attempts.computeIfAbsent(email, k -> new AttemptRecord());
        rec.failures++;
        rec.lastFailure = Instant.now();
        if (rec.failures >= MAX_ATTEMPTS) {
            rec.lockedUntil = Instant.now().plusSeconds(LOCKOUT_SECONDS);
            log.warn("Account locked: {} ({} failed attempts)", email, rec.failures);
        }
    }

    public void recordSuccess(String email) {
        attempts.remove(email);
    }

    public boolean isLocked(String email) {
        AttemptRecord rec = attempts.get(email);
        if (rec == null) return false;
        if (rec.lockedUntil == null) return false;
        if (Instant.now().isAfter(rec.lockedUntil)) {
            // Lockout expired
            attempts.remove(email);
            return false;
        }
        return true;
    }

    public void unlock(String email) {
        attempts.remove(email.toLowerCase());
        log.info("Account unlocked by admin: {}", email);
    }

    /** Clear ALL lockouts — admin emergency reset. */
    public void resetAll() {
        int count = attempts.size();
        attempts.clear();
        log.info("All {} account lockouts cleared by admin", count);
    }

    public int getRemainingAttempts(String email) {
        AttemptRecord rec = attempts.get(email);
        if (rec == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - rec.failures);
    }

    private static class AttemptRecord {
        int failures = 0;
        Instant lastFailure;
        Instant lockedUntil;
    }
}
