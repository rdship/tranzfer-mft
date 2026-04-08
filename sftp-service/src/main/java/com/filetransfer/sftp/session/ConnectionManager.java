package com.filetransfer.sftp.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active SSH/SFTP sessions and enforces connection limits.
 * Provides global and per-user connection caps, and exposes metrics
 * for the enhanced health endpoint.
 */
@Slf4j
@Component
public class ConnectionManager {

    @Value("${sftp.max-connections:0}")
    private int maxConnections;

    @Value("${sftp.max-connections-per-user:0}")
    private int maxConnectionsPerUser;

    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUserConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    /** Per-user QoS session limits: username → max sessions (from TransferAccount). */
    private final ConcurrentHashMap<String, Integer> perUserQosLimits = new ConcurrentHashMap<>();

    /**
     * Attempts to register a new session. Returns true if the connection is allowed
     * under both global and per-user limits. Returns false if a limit would be exceeded.
     *
     * @param sessionId unique session identifier
     * @param username  the authenticated username
     * @param ipAddress the client IP address
     * @return true if the connection is accepted, false if rejected
     */
    public boolean tryRegisterSession(String sessionId, String username, String ipAddress) {
        // Check global limit
        if (maxConnections > 0 && totalConnections.get() >= maxConnections) {
            log.warn("Connection rejected: global limit reached ({}/{}), user={} ip={}",
                    totalConnections.get(), maxConnections, username, ipAddress);
            return false;
        }

        // Check per-user limit: QoS limit takes priority, then global config
        int effectivePerUserLimit = perUserQosLimits.getOrDefault(username, maxConnectionsPerUser);
        if (effectivePerUserLimit > 0) {
            AtomicInteger userCount = perUserConnections.computeIfAbsent(username, k -> new AtomicInteger(0));
            if (userCount.get() >= effectivePerUserLimit) {
                log.warn("Connection rejected: per-user limit reached ({}/{}), user={} ip={}",
                        userCount.get(), effectivePerUserLimit, username, ipAddress);
                return false;
            }
        }

        // Register the session
        totalConnections.incrementAndGet();
        perUserConnections.computeIfAbsent(username, k -> new AtomicInteger(0)).incrementAndGet();
        activeSessions.put(sessionId, new SessionInfo(sessionId, username, ipAddress, Instant.now()));
        log.debug("Session registered: sessionId={} user={} ip={} total={}", sessionId, username, ipAddress, totalConnections.get());
        return true;
    }

    /**
     * Unregisters a session when it disconnects. Decrements both global
     * and per-user counters.
     *
     * @param sessionId the session identifier to remove
     */
    public void unregisterSession(String sessionId) {
        SessionInfo info = activeSessions.remove(sessionId);
        if (info != null) {
            totalConnections.decrementAndGet();
            AtomicInteger userCount = perUserConnections.get(info.getUsername());
            if (userCount != null) {
                int remaining = userCount.decrementAndGet();
                if (remaining <= 0) {
                    perUserConnections.remove(info.getUsername());
                }
            }
            log.debug("Session unregistered: sessionId={} user={} total={}", sessionId, info.getUsername(), totalConnections.get());
        }
    }

    /**
     * Returns the number of currently active connections.
     */
    public int getActiveConnectionCount() {
        return totalConnections.get();
    }

    /**
     * Returns an unmodifiable collection of all active session details.
     */
    public Collection<SessionInfo> getActiveSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }

    /**
     * Returns the per-user connection counts as an unmodifiable map.
     */
    public Map<String, Integer> getPerUserConnectionCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        perUserConnections.forEach((user, count) -> result.put(user, count.get()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the configured maximum global connections (0 = unlimited).
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * Returns the configured maximum per-user connections (0 = unlimited).
     */
    public int getMaxConnectionsPerUser() {
        return maxConnectionsPerUser;
    }

    /**
     * Register per-user QoS session limit (from TransferAccount).
     * Called after successful authentication.
     *
     * @param username           the authenticated username
     * @param maxConcurrentSessions limit from QoS config (null or 0 = use global default)
     */
    public void registerQosSessionLimit(String username, Integer maxConcurrentSessions) {
        if (maxConcurrentSessions != null && maxConcurrentSessions > 0) {
            perUserQosLimits.put(username, maxConcurrentSessions);
            log.debug("QoS session limit registered: user={} max={}", username, maxConcurrentSessions);
        }
    }

    /**
     * Unregister per-user QoS session limit.
     * Only removes when user has no remaining active sessions.
     *
     * @param username the username to unregister
     */
    public void unregisterQosSessionLimit(String username) {
        AtomicInteger userCount = perUserConnections.get(username);
        if (userCount == null || userCount.get() <= 0) {
            perUserQosLimits.remove(username);
            log.debug("QoS session limit unregistered: user={}", username);
        }
    }

    /**
     * Holds metadata for an active session.
     */
    @lombok.Getter
    @lombok.AllArgsConstructor
    public static class SessionInfo {
        private final String sessionId;
        private final String username;
        private final String ipAddress;
        private final Instant connectedAt;
    }
}
