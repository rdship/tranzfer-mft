package com.filetransfer.ai.service.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IP Reputation Service — tracks connection history, failure rates, and behavioral
 * patterns per source IP. Uses a decay model so old good/bad behavior fades over time.
 *
 * Product-agnostic: works for any TCP proxy regardless of upstream service.
 *
 * Reputation score: 0 (blacklisted) to 100 (fully trusted).
 * New IPs start at 50 (neutral). Good behavior raises score, bad behavior lowers it.
 */
@Slf4j
@Service
public class IpReputationService {

    /** IP reputation entry with atomic counters for thread-safety */
    public static class IpReputation {
        private final String ip;
        private volatile double score;  // 0-100
        private final Instant firstSeen;
        private volatile Instant lastSeen;
        private final AtomicLong totalConnections = new AtomicLong(0);
        private final AtomicLong successfulConnections = new AtomicLong(0);
        private final AtomicLong failedConnections = new AtomicLong(0);
        private final AtomicLong rejectedConnections = new AtomicLong(0);
        private final AtomicLong bytesTransferred = new AtomicLong(0);
        private final Set<String> protocols = ConcurrentHashMap.newKeySet();
        private final Set<String> countries = ConcurrentHashMap.newKeySet();
        private final Set<String> tags = ConcurrentHashMap.newKeySet();

        // Sliding window: connection timestamps for burst detection
        private final Deque<Instant> recentConnections = new ArrayDeque<>();
        private final Deque<Instant> recentFailures = new ArrayDeque<>();

        public IpReputation(String ip) {
            this.ip = ip;
            this.score = 50.0;  // neutral start
            this.firstSeen = Instant.now();
            this.lastSeen = Instant.now();
        }

        public String getIp() { return ip; }
        public double getScore() { return score; }
        public Instant getFirstSeen() { return firstSeen; }
        public Instant getLastSeen() { return lastSeen; }
        public long getTotalConnections() { return totalConnections.get(); }
        public long getSuccessfulConnections() { return successfulConnections.get(); }
        public long getFailedConnections() { return failedConnections.get(); }
        public long getRejectedConnections() { return rejectedConnections.get(); }
        public long getBytesTransferred() { return bytesTransferred.get(); }
        public Set<String> getProtocols() { return Collections.unmodifiableSet(protocols); }
        public Set<String> getCountries() { return Collections.unmodifiableSet(countries); }
        public Set<String> getTags() { return Collections.unmodifiableSet(tags); }

        public synchronized int getConnectionsInWindow(int minutes) {
            Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
            return (int) recentConnections.stream().filter(t -> t.isAfter(cutoff)).count();
        }

        public synchronized int getFailuresInWindow(int minutes) {
            Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
            return (int) recentFailures.stream().filter(t -> t.isAfter(cutoff)).count();
        }

        public synchronized void recordConnection() {
            lastSeen = Instant.now();
            totalConnections.incrementAndGet();
            recentConnections.addLast(Instant.now());
            // Keep last 1000 entries
            while (recentConnections.size() > 1000) recentConnections.pollFirst();
        }

        public synchronized void recordFailure() {
            failedConnections.incrementAndGet();
            recentFailures.addLast(Instant.now());
            while (recentFailures.size() > 500) recentFailures.pollFirst();
        }

        public void recordSuccess() { successfulConnections.incrementAndGet(); }
        public void recordRejection() { rejectedConnections.incrementAndGet(); }
        public void addBytes(long bytes) { bytesTransferred.addAndGet(bytes); }
        public void addProtocol(String proto) { if (proto != null) protocols.add(proto); }
        public void addCountry(String country) { if (country != null) countries.add(country); }
        public void addTag(String tag) { if (tag != null) tags.add(tag); }
        public void removeTag(String tag) { tags.remove(tag); }

        public synchronized void adjustScore(double delta) {
            this.score = Math.max(0.0, Math.min(100.0, this.score + delta));
        }

        public synchronized void setScore(double score) {
            this.score = Math.max(0.0, Math.min(100.0, score));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", ip);
            m.put("reputationScore", Math.round(score * 10.0) / 10.0);
            m.put("firstSeen", firstSeen.toString());
            m.put("lastSeen", lastSeen.toString());
            m.put("totalConnections", totalConnections.get());
            m.put("successfulConnections", successfulConnections.get());
            m.put("failedConnections", failedConnections.get());
            m.put("rejectedConnections", rejectedConnections.get());
            m.put("bytesTransferred", bytesTransferred.get());
            m.put("protocols", new ArrayList<>(protocols));
            m.put("countries", new ArrayList<>(countries));
            m.put("tags", new ArrayList<>(tags));
            m.put("connectionsLast5Min", getConnectionsInWindow(5));
            m.put("failuresLast5Min", getFailuresInWindow(5));
            return m;
        }
    }

    // Bounded map — evicts oldest entries when exceeding max
    private static final int MAX_TRACKED_IPS = 100_000;
    private final ConcurrentHashMap<String, IpReputation> reputations = new ConcurrentHashMap<>();

    // Permanent blocklist (manual or AI-triggered)
    private final Set<String> blocklist = ConcurrentHashMap.newKeySet();
    // Permanent allowlist (trusted IPs)
    private final Set<String> allowlist = ConcurrentHashMap.newKeySet();

    // ── Core Operations ────────────────────────────────────────────────

    public IpReputation getOrCreate(String ip) {
        return reputations.computeIfAbsent(ip, k -> {
            if (reputations.size() >= MAX_TRACKED_IPS) {
                evictOldest();
            }
            return new IpReputation(k);
        });
    }

    public Optional<IpReputation> get(String ip) {
        return Optional.ofNullable(reputations.get(ip));
    }

    public void recordConnection(String ip, String protocol) {
        IpReputation rep = getOrCreate(ip);
        rep.recordConnection();
        rep.addProtocol(protocol);
    }

    public void recordSuccess(String ip) {
        IpReputation rep = getOrCreate(ip);
        rep.recordSuccess();
        rep.adjustScore(+0.5);  // small reward for good behavior
    }

    public void recordFailure(String ip, String reason) {
        IpReputation rep = getOrCreate(ip);
        rep.recordFailure();

        // Penalty depends on failure type
        double penalty = -2.0;
        if (reason != null) {
            String r = reason.toLowerCase();
            if (r.contains("brute") || r.contains("auth")) penalty = -5.0;
            if (r.contains("scan") || r.contains("probe")) penalty = -8.0;
            if (r.contains("exploit") || r.contains("attack")) penalty = -15.0;
            if (r.contains("rate_limit")) penalty = -3.0;
        }
        rep.adjustScore(penalty);

        // Auto-blocklist if score drops to 0
        if (rep.getScore() <= 5.0) {
            blockIp(ip, "auto:reputation_zero");
        }
    }

    public void recordRejection(String ip) {
        getOrCreate(ip).recordRejection();
    }

    public void recordBytes(String ip, long bytes) {
        getOrCreate(ip).addBytes(bytes);
    }

    public void recordCountry(String ip, String country) {
        getOrCreate(ip).addCountry(country);
    }

    // ── Blocklist / Allowlist ──────────────────────────────────────────

    public void blockIp(String ip, String reason) {
        blocklist.add(ip);
        IpReputation rep = getOrCreate(ip);
        rep.setScore(0.0);
        rep.addTag("blocked:" + reason);
        log.warn("IP blocked: {} reason={}", ip, reason);
    }

    public void unblockIp(String ip) {
        blocklist.remove(ip);
        IpReputation rep = getOrCreate(ip);
        rep.setScore(25.0);  // restore to low-trust
        rep.removeTag("blocked");
        log.info("IP unblocked: {}", ip);
    }

    public boolean isBlocked(String ip) {
        return blocklist.contains(ip);
    }

    public void allowIp(String ip) {
        allowlist.add(ip);
        getOrCreate(ip).setScore(100.0);
    }

    public void removeAllowIp(String ip) {
        allowlist.remove(ip);
        // Don't reset the reputation score — operators may want to keep the
        // history. Removing from allowlist just means the IP is no longer
        // pre-trusted; its reputation still governs future verdicts.
        log.info("IP removed from allowlist: {}", ip);
    }

    public boolean isAllowed(String ip) {
        return allowlist.contains(ip);
    }

    public Set<String> getBlocklist() {
        return Collections.unmodifiableSet(blocklist);
    }

    public Set<String> getAllowlist() {
        return Collections.unmodifiableSet(allowlist);
    }

    // ── Reputation Queries ─────────────────────────────────────────────

    public double getScore(String ip) {
        return get(ip).map(IpReputation::getScore).orElse(50.0);
    }

    public boolean isNewIp(String ip) {
        IpReputation rep = reputations.get(ip);
        return rep == null || rep.getTotalConnections() <= 1;
    }

    public int getActiveIpCount() {
        return reputations.size();
    }

    public List<Map<String, Object>> getTopThreats(int limit) {
        return reputations.values().stream()
            .filter(r -> r.getScore() < 30.0)
            .sorted(Comparator.comparingDouble(IpReputation::getScore))
            .limit(limit)
            .map(IpReputation::toMap)
            .toList();
    }

    public List<Map<String, Object>> getTopTrusted(int limit) {
        return reputations.values().stream()
            .filter(r -> r.getScore() > 80.0)
            .sorted(Comparator.comparingDouble(r -> -r.getScore()))
            .limit(limit)
            .map(IpReputation::toMap)
            .toList();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedIps", reputations.size());
        stats.put("blockedIps", blocklist.size());
        stats.put("allowedIps", allowlist.size());
        stats.put("averageScore", reputations.values().stream()
            .mapToDouble(IpReputation::getScore).average().orElse(50.0));
        stats.put("highRiskCount", reputations.values().stream()
            .filter(r -> r.getScore() < 20.0).count());
        stats.put("trustedCount", reputations.values().stream()
            .filter(r -> r.getScore() > 80.0).count());
        return stats;
    }

    // ── Score Decay (runs every 5 minutes) ─────────────────────────────

    @Scheduled(fixedRate = 300_000)
    public void decayScores() {
        Instant now = Instant.now();
        reputations.values().forEach(rep -> {
            long minutesSinceLastSeen = ChronoUnit.MINUTES.between(rep.getLastSeen(), now);

            // Scores decay toward 50 (neutral) over time
            // Bad IPs slowly recover, good IPs slowly lose trust if inactive
            if (minutesSinceLastSeen > 60) {
                double current = rep.getScore();
                double decayRate = 0.5;  // points per decay cycle
                if (current < 50.0) {
                    rep.adjustScore(decayRate);  // bad IPs recover slowly
                } else if (current > 50.0 && minutesSinceLastSeen > 1440) {
                    rep.adjustScore(-decayRate * 0.2);  // good IPs lose trust very slowly
                }
            }
        });

        // Evict IPs not seen in 7 days with neutral score
        Instant evictionCutoff = now.minus(7, ChronoUnit.DAYS);
        reputations.entrySet().removeIf(e -> {
            IpReputation rep = e.getValue();
            return rep.getLastSeen().isBefore(evictionCutoff)
                && rep.getScore() > 40.0 && rep.getScore() < 60.0
                && !blocklist.contains(e.getKey())
                && !allowlist.contains(e.getKey());
        });
    }

    private void evictOldest() {
        reputations.entrySet().stream()
            .filter(e -> !blocklist.contains(e.getKey()) && !allowlist.contains(e.getKey()))
            .min(Comparator.comparing(e -> e.getValue().getLastSeen()))
            .ifPresent(e -> reputations.remove(e.getKey()));
    }
}
