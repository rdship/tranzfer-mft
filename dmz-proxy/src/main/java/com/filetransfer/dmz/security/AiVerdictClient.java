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
 * - Graceful degradation: if AI engine is down, returns THROTTLE with strict rate limits
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
    private final String internalApiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedVerdict> verdictCache = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);

    // Config
    private final long verdictTimeoutMs;
    private volatile boolean aiEngineAvailable = true;
    private volatile Instant lastHealthCheck = Instant.EPOCH;
    private static final int MAX_CACHE_SIZE = 100_000;

    // Security: cap local cache TTL regardless of what AI engine returns
    // Prevents stale ALLOW verdicts from persisting too long in DMZ
    private static final long MAX_LOCAL_CACHE_TTL_SECONDS = 60;       // hard cap
    private static final long MAX_ALLOW_CACHE_TTL_SECONDS = 15;       // stricter for ALLOWs
    private static final long MAX_BLOCK_CACHE_TTL_SECONDS = 300;      // blocks can be cached longer

    public AiVerdictClient(String aiEngineUrl, long verdictTimeoutMs, String internalApiKey) {
        this.aiEngineUrl = aiEngineUrl;
        this.verdictTimeoutMs = verdictTimeoutMs;
        this.internalApiKey = internalApiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(verdictTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** Backwards-compatible constructor (uses default key) */
    public AiVerdictClient(String aiEngineUrl, long verdictTimeoutMs) {
        this(aiEngineUrl, verdictTimeoutMs, "internal_control_secret");
    }

    // ── Core Verdict Query ─────────────────────────────────────────────

    /**
     * Get a verdict for an incoming connection.
     * Returns cached verdict if available, otherwise queries AI engine.
     * Falls back to local heuristics if AI engine is unavailable.
     */
    public CachedVerdict getVerdict(String sourceIp, int targetPort, String protocol) {
        return getVerdict(sourceIp, targetPort, protocol, "AI");
    }

    /**
     * Get a verdict with security tier awareness.
     * The securityTier is forwarded to the AI engine so it can decide
     * whether to invoke LLM escalation (AI_LLM tier only).
     */
    public CachedVerdict getVerdict(String sourceIp, int targetPort, String protocol, String securityTier) {
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
            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("sourceIp", sourceIp);
            requestMap.put("targetPort", targetPort);
            requestMap.put("detectedProtocol", protocol != null ? protocol : "TCP");
            if (securityTier != null) {
                requestMap.put("securityTier", securityTier);
            }
            String body = objectMapper.writeValueAsString(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiEngineUrl + "/api/v1/proxy/verdict"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Key", internalApiKey)
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
                    .header("X-Internal-Key", internalApiKey)
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
                    .header("X-Internal-Key", internalApiKey)
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

        // Security: cap local cache TTL based on action — prevents stale ALLOW in DMZ
        long cappedTtl = switch (action) {
            case BLOCK, BLACKHOLE -> Math.min(ttl, MAX_BLOCK_CACHE_TTL_SECONDS);
            case ALLOW -> Math.min(ttl, MAX_ALLOW_CACHE_TTL_SECONDS);
            default -> Math.min(ttl, MAX_LOCAL_CACHE_TTL_SECONDS);
        };

        Instant now = Instant.now();
        return new CachedVerdict(action, riskScore, reason,
            maxConn, maxConcurrent, maxBytes,
            signals, now, now.plusSeconds(cappedTtl));
    }

    /**
     * Local fallback verdict when AI engine is unavailable.
     * Fail-closed: THROTTLE with strict rate limits to prevent abuse
     * while AI-based threat detection is offline.
     */
    private CachedVerdict localFallback(String sourceIp, int targetPort, String protocol, String reason) {
        log.warn("AI engine unavailable — falling back to THROTTLE mode (5/min per IP). Reason: {}", reason);
        Instant now = Instant.now();
        return new CachedVerdict(
            Action.THROTTLE,
            50,  // elevated risk — we cannot assess threats without AI
            "Local fallback (THROTTLE): " + reason,
            5,    // max 5 connections per minute per IP
            2,    // max 2 concurrent connections
            10_000_000L,  // ~10 MB/min — tight limit during degraded mode
            List.of("FALLBACK", "AI_UNAVAILABLE"),
            now,
            now.plusSeconds(30)  // short TTL so we re-check soon
        );
    }

    private void cacheVerdict(String ip, int port, CachedVerdict verdict) {
        if (verdictCache.size() >= MAX_CACHE_SIZE) {
            evictExpired();
            // If still at capacity after evicting expired, forcibly evict oldest 10%
            if (verdictCache.size() >= MAX_CACHE_SIZE) {
                int toEvict = MAX_CACHE_SIZE / 10;
                verdictCache.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getValue().cachedAt()))
                    .limit(toEvict)
                    .map(java.util.Map.Entry::getKey)
                    .toList()
                    .forEach(verdictCache::remove);
            }
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
                    .header("X-Internal-Key", internalApiKey)
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
