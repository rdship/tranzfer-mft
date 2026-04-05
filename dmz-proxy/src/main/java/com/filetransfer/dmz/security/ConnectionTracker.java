package com.filetransfer.dmz.security;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection Tracker — maintains per-IP connection state in-memory.
 * Lightweight, bounded, and thread-safe.
 *
 * Tracks:
 * - Active connections per IP
 * - Connection timestamps (for rate calculation)
 * - Bytes transferred per IP
 * - Port distribution (for scan detection)
 * - Connection durations
 *
 * Product-agnostic: pure connection metadata, no protocol logic.
 */
@Slf4j
public class ConnectionTracker {

    private static final int MAX_TRACKED_IPS = 50_000;

    /** Per-IP tracking data */
    public static class IpState {
        private final String ip;
        private final Set<Channel> activeChannels = ConcurrentHashMap.newKeySet();
        private final Deque<Instant> connectionTimes = new ArrayDeque<>();
        private final Set<Integer> portsUsed = ConcurrentHashMap.newKeySet();
        private final AtomicLong totalBytesIn = new AtomicLong(0);
        private final AtomicLong totalBytesOut = new AtomicLong(0);
        private final AtomicLong totalConnections = new AtomicLong(0);
        private final AtomicLong rejectedConnections = new AtomicLong(0);
        private volatile Instant lastSeen;
        private volatile String detectedProtocol;
        private volatile String country;

        public IpState(String ip) {
            this.ip = ip;
            this.lastSeen = Instant.now();
        }

        public String getIp() { return ip; }
        public int getActiveConnectionCount() { return activeChannels.size(); }
        public long getTotalConnections() { return totalConnections.get(); }
        public long getRejectedConnections() { return rejectedConnections.get(); }
        public long getTotalBytesIn() { return totalBytesIn.get(); }
        public long getTotalBytesOut() { return totalBytesOut.get(); }
        public Instant getLastSeen() { return lastSeen; }
        public String getDetectedProtocol() { return detectedProtocol; }
        public String getCountry() { return country; }
        public Set<Integer> getPortsUsed() { return Collections.unmodifiableSet(portsUsed); }

        void addChannel(Channel ch, int port) {
            activeChannels.add(ch);
            portsUsed.add(port);
            totalConnections.incrementAndGet();
            lastSeen = Instant.now();
            synchronized (connectionTimes) {
                connectionTimes.addLast(Instant.now());
                while (connectionTimes.size() > 500) connectionTimes.pollFirst();
            }
        }

        void removeChannel(Channel ch) {
            activeChannels.remove(ch);
        }

        void addBytesIn(long bytes) { totalBytesIn.addAndGet(bytes); }
        void addBytesOut(long bytes) { totalBytesOut.addAndGet(bytes); }
        void recordRejection() { rejectedConnections.incrementAndGet(); }
        void setProtocol(String proto) { this.detectedProtocol = proto; }
        void setCountry(String country) { this.country = country; }

        /**
         * Get connections per minute in the specified window.
         */
        public synchronized int getConnectionsPerMinute(int windowMinutes) {
            Instant cutoff = Instant.now().minusSeconds(windowMinutes * 60L);
            int count = (int) connectionTimes.stream().filter(t -> t.isAfter(cutoff)).count();
            return windowMinutes > 0 ? count / windowMinutes : count;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", ip);
            m.put("activeConnections", activeChannels.size());
            m.put("totalConnections", totalConnections.get());
            m.put("rejectedConnections", rejectedConnections.get());
            m.put("bytesIn", totalBytesIn.get());
            m.put("bytesOut", totalBytesOut.get());
            m.put("portsUsed", new ArrayList<>(portsUsed));
            m.put("detectedProtocol", detectedProtocol);
            m.put("country", country);
            m.put("lastSeen", lastSeen.toString());
            m.put("connectionsPerMinute", getConnectionsPerMinute(1));
            return m;
        }
    }

    private final ConcurrentHashMap<String, IpState> ipStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, ConnectionInfo> channelMap = new ConcurrentHashMap<>();
    private final AtomicLong globalActiveConnections = new AtomicLong(0);
    private final AtomicLong globalTotalConnections = new AtomicLong(0);
    private final AtomicLong globalRejectedConnections = new AtomicLong(0);

    /** Connection metadata stored per channel */
    public record ConnectionInfo(
        String ip,
        int port,
        Instant openedAt,
        AtomicLong bytesIn,
        AtomicLong bytesOut
    ) {
        public ConnectionInfo(String ip, int port) {
            this(ip, port, Instant.now(), new AtomicLong(0), new AtomicLong(0));
        }
    }

    // ── Connection Lifecycle ───────────────────────────────────────────

    /**
     * Register a new connection.
     */
    public IpState connectionOpened(Channel channel, String ip, int port) {
        IpState state = ipStates.computeIfAbsent(ip, IpState::new);
        state.addChannel(channel, port);
        channelMap.put(channel, new ConnectionInfo(ip, port));
        globalActiveConnections.incrementAndGet();
        globalTotalConnections.incrementAndGet();

        // Bounded: evict if too many IPs
        if (ipStates.size() > MAX_TRACKED_IPS) {
            evictOldest();
        }

        return state;
    }

    /**
     * Unregister a closed connection.
     * Returns duration in ms and bytes transferred.
     */
    public ConnectionInfo connectionClosed(Channel channel) {
        ConnectionInfo info = channelMap.remove(channel);
        if (info != null) {
            IpState state = ipStates.get(info.ip());
            if (state != null) {
                state.removeChannel(channel);
            }
            globalActiveConnections.decrementAndGet();
        }
        return info;
    }

    /**
     * Record a rejected connection (didn't pass security check).
     */
    public void connectionRejected(String ip) {
        getOrCreate(ip).recordRejection();
        globalRejectedConnections.incrementAndGet();
    }

    // ── Byte Tracking ──────────────────────────────────────────────────

    public void recordBytesIn(Channel channel, long bytes) {
        ConnectionInfo info = channelMap.get(channel);
        if (info != null) {
            info.bytesIn().addAndGet(bytes);
            IpState state = ipStates.get(info.ip());
            if (state != null) state.addBytesIn(bytes);
        }
    }

    public void recordBytesOut(Channel channel, long bytes) {
        ConnectionInfo info = channelMap.get(channel);
        if (info != null) {
            info.bytesOut().addAndGet(bytes);
            IpState state = ipStates.get(info.ip());
            if (state != null) state.addBytesOut(bytes);
        }
    }

    // ── Query ──────────────────────────────────────────────────────────

    public IpState getOrCreate(String ip) {
        return ipStates.computeIfAbsent(ip, IpState::new);
    }

    public Optional<IpState> get(String ip) {
        return Optional.ofNullable(ipStates.get(ip));
    }

    public ConnectionInfo getConnectionInfo(Channel channel) {
        return channelMap.get(channel);
    }

    public int getActiveConnectionCount() {
        return (int) globalActiveConnections.get();
    }

    public long getTotalConnections() {
        return globalTotalConnections.get();
    }

    public int getTrackedIpCount() {
        return ipStates.size();
    }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedIps", ipStates.size());
        stats.put("activeConnections", globalActiveConnections.get());
        stats.put("totalConnections", globalTotalConnections.get());
        stats.put("rejectedConnections", globalRejectedConnections.get());

        // Top 10 by active connections
        stats.put("topByActiveConnections", ipStates.values().stream()
            .filter(s -> s.getActiveConnectionCount() > 0)
            .sorted(Comparator.comparingInt(s -> -s.getActiveConnectionCount()))
            .limit(10)
            .map(IpState::toMap)
            .toList());

        return stats;
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    private void evictOldest() {
        ipStates.entrySet().stream()
            .filter(e -> e.getValue().getActiveConnectionCount() == 0)
            .min(Comparator.comparing(e -> e.getValue().getLastSeen()))
            .ifPresent(e -> ipStates.remove(e.getKey()));
    }

    /**
     * Remove IPs with no active connections and not seen in given hours.
     */
    public void cleanup(int hoursOld) {
        Instant cutoff = Instant.now().minusSeconds(hoursOld * 3600L);
        ipStates.entrySet().removeIf(e ->
            e.getValue().getActiveConnectionCount() == 0
            && e.getValue().getLastSeen().isBefore(cutoff));
    }
}
