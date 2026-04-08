package com.filetransfer.shared.connector;

import com.filetransfer.shared.entity.ActivityEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Real-time activity monitor. Tracks live connections, active transfers,
 * and recent events. All in-memory — no DB writes for speed.
 */
@Service @Slf4j
public class ActivityMonitor {

    private final ConcurrentLinkedDeque<ActivityEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private final Map<String, ActivityEvent> activeTransfers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicInteger activeSftpConnections = new AtomicInteger();
    private final AtomicInteger activeFtpConnections = new AtomicInteger();
    private final AtomicInteger activeHttpConnections = new AtomicInteger();
    private static final int MAX_EVENTS = 500;

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

    public void connectionOpened(String protocol) {
        switch (protocol.toUpperCase()) {
            case "SFTP" -> activeSftpConnections.incrementAndGet();
            case "FTP" -> activeFtpConnections.incrementAndGet();
            case "HTTPS", "HTTP" -> activeHttpConnections.incrementAndGet();
        }
    }

    public void connectionClosed(String protocol) {
        switch (protocol.toUpperCase()) {
            case "SFTP" -> activeSftpConnections.decrementAndGet();
            case "FTP" -> activeFtpConnections.decrementAndGet();
            case "HTTPS", "HTTP" -> activeHttpConnections.decrementAndGet();
        }
    }

    public Map<String, Object> getSnapshot() {
        Instant fiveMinAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        long recentCount = recentEvents.stream().filter(e -> e.getTimestamp().isAfter(fiveMinAgo)).count();

        return Map.of(
                "activeSftpConnections", activeSftpConnections.get(),
                "activeFtpConnections", activeFtpConnections.get(),
                "activeHttpConnections", activeHttpConnections.get(),
                "totalActiveConnections", activeSftpConnections.get() + activeFtpConnections.get() + activeHttpConnections.get(),
                "activeTransfers", activeTransfers.size(),
                "transfersLast5Min", recentCount,
                "timestamp", Instant.now().toString()
        );
    }

    public List<ActivityEvent> getActiveTransfers() {
        return new ArrayList<>(activeTransfers.values());
    }

    public List<ActivityEvent> getRecentEvents(int limit) {
        return recentEvents.stream().limit(limit).collect(Collectors.toList());
    }
}
