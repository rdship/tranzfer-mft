package com.filetransfer.shared.connector;

import com.filetransfer.shared.entity.transfer.ActivityEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Real-time activity monitor (per-replica).
 *
 * <p>Per-replica counters live in {@link AtomicInteger}s for zero-latency local reads.
 *
 * <p><b>R134AH — Redis aggregation retired.</b> Earlier versions synced local
 * deltas into a Redis {@code platform:activity} hash so each replica could
 * expose a cluster-wide total. R134Y replaced the activity event feed with
 * a PG table poll on {@code file_transfer_records}; the Redis HINCRBY/HGET
 * dance was already best-effort silent-fail and never load-bearing. The
 * admin UI now shows per-replica connection counts (prefixed accordingly in
 * the snapshot); cluster-wide aggregation can be restored later by summing
 * across replicas discovered through {@code platform_pod_heartbeat}.
 */
@Service
@Slf4j
public class ActivityMonitor {

    private final ConcurrentLinkedDeque<ActivityEvent> recentEvents   = new ConcurrentLinkedDeque<>();
    private final Map<String, ActivityEvent>           activeTransfers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicInteger activeSftpConnections  = new AtomicInteger();
    private final AtomicInteger activeFtpConnections   = new AtomicInteger();
    private final AtomicInteger activeHttpConnections  = new AtomicInteger();
    private static final int MAX_EVENTS = 500;

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
        switch (protocol.toUpperCase()) {
            case "SFTP"        -> activeSftpConnections.incrementAndGet();
            case "FTP"         -> activeFtpConnections.incrementAndGet();
            case "HTTPS","HTTP"-> activeHttpConnections.incrementAndGet();
            default            -> { /* no-op */ }
        }
    }

    public void connectionClosed(String protocol) {
        switch (protocol.toUpperCase()) {
            case "SFTP"        -> activeSftpConnections.decrementAndGet();
            case "FTP"         -> activeFtpConnections.decrementAndGet();
            case "HTTPS","HTTP"-> activeHttpConnections.decrementAndGet();
            default            -> { /* no-op */ }
        }
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    /**
     * Returns per-replica activity metrics. For cluster-wide totals, sum the
     * snapshot across replicas discovered via {@code platform_pod_heartbeat}.
     */
    public Map<String, Object> getSnapshot() {
        Instant fiveMinAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        long recentCount   = recentEvents.stream().filter(e -> e.getTimestamp().isAfter(fiveMinAgo)).count();

        long sftp = activeSftpConnections.get();
        long ftp  = activeFtpConnections.get();
        long http = activeHttpConnections.get();

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("activeSftpConnections",  sftp);
        snap.put("activeFtpConnections",   ftp);
        snap.put("activeHttpConnections",  http);
        snap.put("totalActiveConnections", sftp + ftp + http);
        snap.put("activeTransfers",        activeTransfers.size());
        snap.put("transfersLast5Min",      recentCount);
        snap.put("clusterWide",            false);
        snap.put("timestamp",              Instant.now().toString());
        return snap;
    }

    public List<ActivityEvent> getActiveTransfers() { return new ArrayList<>(activeTransfers.values()); }
    public List<ActivityEvent> getRecentEvents(int limit) {
        return recentEvents.stream().limit(limit).collect(Collectors.toList());
    }
}
