package com.filetransfer.shared.connector;

import com.filetransfer.shared.entity.ActivityEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Real-time activity monitor.
 *
 * <p>Per-replica counts live in {@link AtomicInteger}s for zero-latency local reads.
 * When Redis is available, the same delta is applied to {@code platform:activity}
 * (a Redis HASH) so every replica sees the <b>cluster-wide total</b>.
 *
 * <p>Redis unavailability is silent — local counters remain correct for this replica.
 */
@Service
@Slf4j
public class ActivityMonitor {

    private static final String ACTIVITY_KEY = "platform:activity";

    private final ConcurrentLinkedDeque<ActivityEvent> recentEvents   = new ConcurrentLinkedDeque<>();
    private final Map<String, ActivityEvent>           activeTransfers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicInteger activeSftpConnections  = new AtomicInteger();
    private final AtomicInteger activeFtpConnections   = new AtomicInteger();
    private final AtomicInteger activeHttpConnections  = new AtomicInteger();
    private static final int MAX_EVENTS = 500;

    /** Injected only when Redis is on the classpath and configured. */
    @Autowired(required = false)
    @Nullable
    private StringRedisTemplate redis;

    // ── Events ────────────────────────────────────────────────────────────────

    public void recordEvent(ActivityEvent event) {
        event.setTimestamp(Instant.now());
        recentEvents.addFirst(event);
        while (recentEvents.size() > MAX_EVENTS) recentEvents.removeLast();

        if ("IN_PROGRESS".equals(event.getStatus())) {
            activeTransfers.put(event.getTrackId() != null ? event.getTrackId() : UUID.randomUUID().toString(), event);
        } else {
            if (event.getTrackId() != null) activeTransfers.remove(event.getTrackId());
        }
    }

    // ── Connection tracking ───────────────────────────────────────────────────

    public void connectionOpened(String protocol) {
        String field = protocolField(protocol);
        switch (protocol.toUpperCase()) {
            case "SFTP"        -> activeSftpConnections.incrementAndGet();
            case "FTP"         -> activeFtpConnections.incrementAndGet();
            case "HTTPS","HTTP"-> activeHttpConnections.incrementAndGet();
        }
        redisIncrement(field, 1);
    }

    public void connectionClosed(String protocol) {
        String field = protocolField(protocol);
        switch (protocol.toUpperCase()) {
            case "SFTP"        -> activeSftpConnections.decrementAndGet();
            case "FTP"         -> activeFtpConnections.decrementAndGet();
            case "HTTPS","HTTP"-> activeHttpConnections.decrementAndGet();
        }
        redisIncrement(field, -1);
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    /**
     * Returns activity metrics. If Redis is available, the connection counts are
     * cluster-wide totals (all replicas summed). If not, they are this-replica only.
     */
    public Map<String, Object> getSnapshot() {
        Instant fiveMinAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        long recentCount   = recentEvents.stream().filter(e -> e.getTimestamp().isAfter(fiveMinAgo)).count();

        long sftp = clusterCount("sftp",  activeSftpConnections.get());
        long ftp  = clusterCount("ftp",   activeFtpConnections.get());
        long http = clusterCount("http",  activeHttpConnections.get());

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("activeSftpConnections",  sftp);
        snap.put("activeFtpConnections",   ftp);
        snap.put("activeHttpConnections",  http);
        snap.put("totalActiveConnections", sftp + ftp + http);
        snap.put("activeTransfers",        activeTransfers.size());
        snap.put("transfersLast5Min",      recentCount);
        snap.put("clusterWide",            redis != null);
        snap.put("timestamp",              Instant.now().toString());
        return snap;
    }

    public List<ActivityEvent> getActiveTransfers() { return new ArrayList<>(activeTransfers.values()); }
    public List<ActivityEvent> getRecentEvents(int limit) {
        return recentEvents.stream().limit(limit).collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void redisIncrement(String field, long delta) {
        if (redis == null || field == null) return;
        try {
            redis.opsForHash().increment(ACTIVITY_KEY, field, delta);
        } catch (Exception e) {
            log.debug("[ActivityMonitor] Redis HINCRBY failed (local count still correct): {}", e.getMessage());
        }
    }

    private long clusterCount(String field, long localFallback) {
        if (redis == null) return localFallback;
        try {
            Object val = redis.opsForHash().get(ACTIVITY_KEY, field);
            return val != null ? Long.parseLong(val.toString()) : localFallback;
        } catch (Exception e) {
            return localFallback;
        }
    }

    private static String protocolField(String protocol) {
        return switch (protocol.toUpperCase()) {
            case "SFTP"         -> "sftp";
            case "FTP"          -> "ftp";
            case "HTTPS","HTTP" -> "http";
            default             -> null;
        };
    }
}
