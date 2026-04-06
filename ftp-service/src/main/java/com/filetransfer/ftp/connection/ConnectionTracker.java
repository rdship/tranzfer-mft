package com.filetransfer.ftp.connection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active FTP connections globally, per-user, and per-IP.
 * Enforces configurable limits at each level.
 *
 * <p>All operations are thread-safe. Limits are automatically divided by the
 * replica count so that N replicas collectively enforce the intended global limit.</p>
 */
@Slf4j
@Service
public class ConnectionTracker {

    @Value("${ftp.connection.max-total:200}")
    private int maxTotal;

    @Value("${ftp.connection.max-per-user:10}")
    private int maxPerUser;

    @Value("${ftp.connection.max-per-ip:10}")
    private int maxPerIp;

    @Value("${platform.replica-count:1}")
    private int replicaCount;

    @PostConstruct
    void adjustForReplicas() {
        if (replicaCount > 1) {
            maxTotal = Math.max(1, maxTotal / replicaCount);
            maxPerUser = Math.max(1, maxPerUser / replicaCount);
            maxPerIp = Math.max(1, maxPerIp / replicaCount);
            log.info("Connection limits adjusted for {} replicas: total={}, perUser={}, perIp={}",
                    replicaCount, maxTotal, maxPerUser, maxPerIp);
        }
    }

    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> perIp = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire a connection slot.
     *
     * @param username the authenticated username (may be null for pre-auth)
     * @param ip       the client IP address
     * @return {@code true} if the connection is accepted, {@code false} if a limit would be exceeded
     */
    public boolean tryAcquire(String username, String ip) {
        int total = totalConnections.get();
        if (total >= maxTotal) {
            log.warn("Global connection limit reached: {}/{}", total, maxTotal);
            return false;
        }

        if (ip != null) {
            AtomicInteger ipCount = perIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
            if (ipCount.get() >= maxPerIp) {
                log.warn("Per-IP connection limit reached: ip={} count={}/{}", ip, ipCount.get(), maxPerIp);
                return false;
            }
        }

        if (username != null) {
            AtomicInteger userCount = perUser.computeIfAbsent(username, k -> new AtomicInteger(0));
            if (userCount.get() >= maxPerUser) {
                log.warn("Per-user connection limit reached: user={} count={}/{}", username, userCount.get(), maxPerUser);
                return false;
            }
        }

        // Commit the acquisition
        totalConnections.incrementAndGet();
        if (ip != null) {
            perIp.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        }
        if (username != null) {
            perUser.computeIfAbsent(username, k -> new AtomicInteger(0)).incrementAndGet();
        }
        return true;
    }

    /**
     * Release a connection slot.
     *
     * @param username the authenticated username (may be null)
     * @param ip       the client IP address
     */
    public void release(String username, String ip) {
        totalConnections.updateAndGet(v -> Math.max(0, v - 1));
        if (ip != null) {
            AtomicInteger ipCount = perIp.get(ip);
            if (ipCount != null) {
                int val = ipCount.decrementAndGet();
                if (val <= 0) {
                    perIp.remove(ip);
                }
            }
        }
        if (username != null) {
            AtomicInteger userCount = perUser.get(username);
            if (userCount != null) {
                int val = userCount.decrementAndGet();
                if (val <= 0) {
                    perUser.remove(username);
                }
            }
        }
    }

    /** Return the total number of currently active connections. */
    public int getActiveCount() {
        return totalConnections.get();
    }

    /** Return a snapshot of per-user connection counts (for diagnostics). */
    public Map<String, Integer> getPerUserSnapshot() {
        ConcurrentHashMap<String, Integer> snapshot = new ConcurrentHashMap<>();
        perUser.forEach((k, v) -> {
            int val = v.get();
            if (val > 0) snapshot.put(k, val);
        });
        return snapshot;
    }

    /** Return a snapshot of per-IP connection counts (for diagnostics). */
    public Map<String, Integer> getPerIpSnapshot() {
        ConcurrentHashMap<String, Integer> snapshot = new ConcurrentHashMap<>();
        perIp.forEach((k, v) -> {
            int val = v.get();
            if (val > 0) snapshot.put(k, val);
        });
        return snapshot;
    }

    public int getMaxTotal() {
        return maxTotal;
    }

    public int getMaxPerUser() {
        return maxPerUser;
    }

    public int getMaxPerIp() {
        return maxPerIp;
    }
}
