package com.filetransfer.dmz.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI Verdict Client — queries the AI engine for connection verdicts.
 * Includes a local TTL cache for sub-millisecond hot-path decisions.
 *
 * Design:
 * - Sync verdict query with timeout (for first connection from an IP)
 * - Local cache with TTL (subsequent connections use cache)
 * - Graceful degradation: if AI engine is down, returns ALLOW with local heuristics
 * - Async cache refresh in background
 *
 * Product-agnostic: any TCP proxy can use this as its security backend client.
 */
@Slf4j
public class AiVerdictClient {

    public enum Action {
        ALLOW, THROTTLE, CHALLENGE, BLOCK, BLACKHOLE
    }

    public record CachedVerdict(
        Action action,
        int riskScore,
        String reason,
        Integer maxConnectionsPerMinute,  // null if no rate limit
        Integer maxConcurrentConnections,
        Long maxBytesPerMinute,
        List<String> signals,
        Instant cachedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    // ── State ──────────────────────────────────────────────────────────

    private final String aiEngineUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedVerdict> verdictCache = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);

    // Config
    private final long verdictTimeoutMs;
    private volatile boolean aiEngineAvailable = true;
    private volatile Instant lastHealthCheck = Instant.EPOCH;
    private static final int MAX_CACHE_SIZE = 100_000;

    public AiVerdictClient(String aiEngineUrl, long verdictTimeoutMs) {
        this.aiEngineUrl = aiEngineUrl;
        this.verdictTimeoutMs = verdictTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(verdictTimeoutMs))
            .build();
    }

    // ── Core Verdict Query ─────────────────────────────────────────────

    /**
     * Get a verdict for an incoming connection.
     * Returns cached verdict if available, otherwise queries AI engine.
     * Falls back to local heuristics if AI engine is unavailable.
     */
    public CachedVerdict getVerdict(String sourceIp, int targetPort, String protocol) {
        // 1. Check cache first (sub-millisecond)
        CachedVerdict cached = verdictCache.get(cacheKey(sourceIp, targetPort));
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        // 2. If AI engine known to be down, skip network call
        if (!aiEngineAvailable) {
            checkHealthAsync();
            return localFallback(sourceIp, targetPort, protocol, "ai_engine_unavailable");
        }

        // 3. Query AI engine synchronously (with timeout)
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "sourceIp", sourceIp,
                "targetPort", targetPort,
                "detectedProtocol", protocol != null ? protocol : "TCP"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiEngineUrl + "/api/v1/proxy/verdict"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(verdictTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                CachedVerdict verdict = parseVerdict(response.body());
                cacheVerdict(sourceIp, targetPort, verdict);
                aiEngineAvailable = true;
                return verdict;
            } else {
                log.warn("AI engine returned {}: {}", response.statusCode(), response.body());
                return localFallback(sourceIp, targetPort, protocol, "http_" + response.statusCode());
            }
        } catch (Exception e) {
            log.warn("AI engine unreachable: {}", e.getMessage());
            aiEngineAvailable = false;
            lastHealthCheck = Instant.now();
            return localFallback(sourceIp, targetPort, protocol, "unreachable");
        }
    }

    /**
     * Async verdict query — for background cache refresh.
     */
    public CompletableFuture<CachedVerdict> getVerdictAsync(String sourceIp, int targetPort, String protocol) {
        return CompletableFuture.supplyAsync(
            () -> getVerdict(sourceIp, targetPort, protocol), asyncExecutor);
    }

    // ── Event Reporting (fire-and-forget) ──────────────────────────────

    /**
     * Report a threat event to the AI engine asynchronously.
     */
    public void reportEventAsync(Map<String, Object> event) {
        asyncExecutor.submit(() -> {
            try {
                String body = objectMapper.writeValueAsString(event);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiEngineUrl + "/api/v1/proxy/event"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Fire-and-forget: swallow errors
                log.trace("Event report failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Report a batch of events.
     */
    public void reportEventsAsync(List<Map<String, Object>> events) {
        if (events.isEmpty()) return;
        asyncExecutor.submit(() -> {
            try {
                String body = objectMapper.writeValueAsString(events);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiEngineUrl + "/api/v1/proxy/events"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.trace("Batch event report failed: {}", e.getMessage());
            }
        });
    }

    // ── Cache Management ───────────────────────────────────────────────

    public void invalidate(String ip) {
        verdictCache.entrySet().removeIf(e -> e.getKey().startsWith(ip + ":"));
    }

    public void invalidateAll() {
        verdictCache.clear();
    }

    public int getCacheSize() {
        return verdictCache.size();
    }

    public boolean isAiEngineAvailable() {
        return aiEngineAvailable;
    }

    // ── Private ────────────────────────────────────────────────────────

    private CachedVerdict parseVerdict(String responseBody) throws Exception {
        JsonNode json = objectMapper.readTree(responseBody);

        Action action = Action.valueOf(json.path("action").asText("ALLOW"));
        int riskScore = json.path("riskScore").asInt(0);
        String reason = json.path("reason").asText("");
        int ttl = json.path("ttlSeconds").asInt(60);

        Integer maxConn = null, maxConcurrent = null;
        Long maxBytes = null;
        JsonNode rl = json.path("rateLimit");
        if (!rl.isMissingNode()) {
            maxConn = rl.path("maxConnectionsPerMinute").asInt(60);
            maxConcurrent = rl.path("maxConcurrentConnections").asInt(20);
            maxBytes = rl.path("maxBytesPerMinute").asLong(500_000_000L);
        }

        List<String> signals = new ArrayList<>();
        JsonNode sigNode = json.path("signals");
        if (sigNode.isArray()) {
            for (JsonNode s : sigNode) signals.add(s.asText());
        }

        Instant now = Instant.now();
        return new CachedVerdict(action, riskScore, reason,
            maxConn, maxConcurrent, maxBytes,
            signals, now, now.plusSeconds(ttl));
    }

    /**
     * Local fallback verdict when AI engine is unavailable.
     * Conservative: allows connections but with stricter rate limits.
     */
    private CachedVerdict localFallback(String sourceIp, int targetPort, String protocol, String reason) {
        Instant now = Instant.now();
        return new CachedVerdict(
            Action.ALLOW,
            10,  // low risk (we don't know better)
            "Local fallback: " + reason,
            30,   // conservative rate limit
            10,   // conservative concurrent limit
            100_000_000L,
            List.of("FALLBACK"),
            now,
            now.plusSeconds(30)  // short TTL so we re-check soon
        );
    }

    private void cacheVerdict(String ip, int port, CachedVerdict verdict) {
        if (verdictCache.size() >= MAX_CACHE_SIZE) {
            evictExpired();
        }
        verdictCache.put(cacheKey(ip, port), verdict);
    }

    private void evictExpired() {
        verdictCache.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private String cacheKey(String ip, int port) {
        return ip + ":" + port;
    }

    private void checkHealthAsync() {
        // Only check every 30 seconds
        if (Instant.now().minusSeconds(30).isBefore(lastHealthCheck)) return;
        lastHealthCheck = Instant.now();

        asyncExecutor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiEngineUrl + "/api/v1/proxy/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                aiEngineAvailable = resp.statusCode() == 200;
                if (aiEngineAvailable) {
                    log.info("AI engine is back online");
                }
            } catch (Exception e) {
                aiEngineAvailable = false;
            }
        });
    }

    public void shutdown() {
        asyncExecutor.shutdownNow();
    }
}
