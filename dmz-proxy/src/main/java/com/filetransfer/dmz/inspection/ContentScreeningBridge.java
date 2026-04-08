package com.filetransfer.dmz.inspection;

import com.filetransfer.dmz.security.SpiffeProxyAuth;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content Screening Bridge — async bridge to the platform's screening-service (port 8092)
 * for AML/sanctions scanning of file transfers.
 *
 * <p>Accumulates transfer data per connection and triggers screening on transfer completion.
 * Supports both blocking and async (background) screening modes.</p>
 *
 * <p>Design principles:</p>
 * <ul>
 *   <li>Never throw — screening failures must not block transfers unless configured</li>
 *   <li>Bounded buffers — prevents OOM from large transfers</li>
 *   <li>Thread-safe — ConcurrentHashMap for buffers, atomic counters for stats</li>
 *   <li>Uses {@link java.net.http.HttpClient} (no Spring RestTemplate)</li>
 * </ul>
 *
 * @see com.filetransfer.dmz.security.ProtocolDetector
 */
@Slf4j
public class ContentScreeningBridge {

    // ── Nested types ──────────────────────────────────────────────────

    /**
     * Screening configuration.
     */
    public record ContentScreeningConfig(
        boolean enabled,
        long maxScreeningSizeBytes,
        int screeningTimeoutSeconds,
        boolean blockOnHit,
        boolean blockOnTimeout,
        boolean asyncMode,
        List<String> screenedProtocols
    ) {
        public ContentScreeningConfig {
            if (maxScreeningSizeBytes <= 0) maxScreeningSizeBytes = 50_000_000L;
            if (screeningTimeoutSeconds <= 0) screeningTimeoutSeconds = 30;
            if (screenedProtocols == null) screenedProtocols = List.of("FTP", "SFTP", "HTTP");
        }
    }

    /**
     * Result of a screening operation.
     */
    public record ScreeningResult(
        Outcome outcome,
        String detail,
        int hitsFound,
        long durationMs
    ) {}

    /**
     * Screening outcome.
     */
    public enum Outcome {
        CLEAR,
        HIT,
        POSSIBLE_HIT,
        ERROR,
        TIMEOUT,
        SKIPPED
    }

    // ── Per-connection buffer metadata ────────────────────────────────

    private static class TransferBuffer {
        final ByteArrayOutputStream data = new ByteArrayOutputStream(8192);
        volatile boolean limitReached = false;
    }

    // ── State ─────────────────────────────────────────────────────────

    private final String screeningServiceUrl;
    private final SpiffeProxyAuth spiffeAuth;
    private final ContentScreeningConfig config;
    private final HttpClient httpClient;
    private final ExecutorService screeningExecutor;

    // Per-connection buffers
    private final ConcurrentHashMap<String, TransferBuffer> buffers = new ConcurrentHashMap<>();

    // Pending screening futures (for shutdown cleanup)
    private final ConcurrentHashMap<String, CompletableFuture<ScreeningResult>> pendingScreenings =
        new ConcurrentHashMap<>();

    // ── Stats ─────────────────────────────────────────────────────────

    private final AtomicLong totalScreened = new AtomicLong();
    private final AtomicLong totalClear = new AtomicLong();
    private final AtomicLong totalHit = new AtomicLong();
    private final AtomicLong totalPossibleHit = new AtomicLong();
    private final AtomicLong totalError = new AtomicLong();
    private final AtomicLong totalTimeout = new AtomicLong();
    private final AtomicLong totalSkipped = new AtomicLong();

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Create a new content screening bridge.
     *
     * @param screeningServiceUrl base URL of the screening service (e.g., "http://screening-service:8092")
     * @param spiffeAuth          SPIFFE auth component for outbound SVID tokens
     * @param config              screening configuration
     */
    public ContentScreeningBridge(String screeningServiceUrl,
                                   com.filetransfer.dmz.security.SpiffeProxyAuth spiffeAuth,
                                   ContentScreeningConfig config) {
        this.screeningServiceUrl = screeningServiceUrl;
        this.spiffeAuth = spiffeAuth;
        this.config = config;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.screeningTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        this.screeningExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "screening-bridge");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Buffer Management ─────────────────────────────────────────────

    /**
     * Feed transfer data for a connection. Accumulates data up to the configured maximum.
     * If the buffer exceeds {@code maxScreeningSizeBytes}, further data is silently dropped.
     * Uses {@code getBytes()} for zero-copy reads from the ByteBuf.
     *
     * @param connectionId unique connection identifier
     * @param data         the ByteBuf containing transfer data (not consumed)
     */
    public void feedData(String connectionId, ByteBuf data) {
        if (!config.enabled()) return;

        try {
            int readable = data.readableBytes();
            if (readable == 0) return;

            TransferBuffer buffer = buffers.computeIfAbsent(connectionId, k -> new TransferBuffer());

            if (buffer.limitReached) {
                return; // already at size limit — stop buffering
            }

            synchronized (buffer) {
                long currentSize = buffer.data.size();
                if (currentSize >= config.maxScreeningSizeBytes()) {
                    buffer.limitReached = true;
                    log.debug("Screening buffer limit reached for connection {}: {} bytes",
                        connectionId, currentSize);
                    return;
                }

                long remaining = config.maxScreeningSizeBytes() - currentSize;
                int toRead = (int) Math.min(readable, remaining);

                byte[] bytes = new byte[toRead];
                data.getBytes(data.readerIndex(), bytes);
                buffer.data.write(bytes, 0, toRead);

                if (buffer.data.size() >= config.maxScreeningSizeBytes()) {
                    buffer.limitReached = true;
                }
            }
        } catch (Exception e) {
            log.debug("Error buffering data for screening (connection {}): {}", connectionId, e.getMessage());
        }
    }

    /**
     * Notify that a transfer is complete — triggers screening of the accumulated buffer.
     *
     * @param connectionId unique connection identifier
     * @param sourceIp     source IP address
     * @param port         listen port
     * @param protocol     detected protocol (e.g., "FTP", "SFTP", "HTTP")
     * @param filename     filename if known, or null
     */
    public void transferComplete(String connectionId, String sourceIp, int port,
                                  String protocol, String filename) {
        if (!config.enabled()) return;

        TransferBuffer buffer = buffers.remove(connectionId);
        if (buffer == null) {
            log.debug("No buffered data for screening (connection {})", connectionId);
            return;
        }

        byte[] content;
        synchronized (buffer) {
            content = buffer.data.toByteArray();
        }

        if (content.length == 0) {
            totalSkipped.incrementAndGet();
            return;
        }

        // Check if protocol should be screened
        if (config.screenedProtocols() != null && !config.screenedProtocols().isEmpty()) {
            String proto = protocol != null ? protocol.toUpperCase() : "";
            boolean shouldScreen = config.screenedProtocols().stream()
                .anyMatch(p -> p.equalsIgnoreCase(proto));
            if (!shouldScreen) {
                totalSkipped.incrementAndGet();
                log.debug("Protocol {} not configured for screening — skipping", protocol);
                return;
            }
        }

        CompletableFuture<ScreeningResult> future = screenTransfer(
            sourceIp, port, protocol, filename, content);

        pendingScreenings.put(connectionId, future);

        future.whenComplete((result, throwable) -> {
            pendingScreenings.remove(connectionId);
            if (result != null) {
                updateStats(result.outcome());
                if (result.outcome() == Outcome.HIT || result.outcome() == Outcome.POSSIBLE_HIT) {
                    log.warn("Screening {} for connection {} from {} — {} hit(s): {}",
                        result.outcome(), connectionId, sourceIp, result.hitsFound(), result.detail());
                } else {
                    log.debug("Screening result for connection {}: {}", connectionId, result.outcome());
                }
            }
        });
    }

    /**
     * Notify that a transfer was cancelled — cleanup the buffer and cancel pending screening.
     *
     * @param connectionId unique connection identifier
     */
    public void transferCancelled(String connectionId) {
        buffers.remove(connectionId);
        CompletableFuture<ScreeningResult> pending = pendingScreenings.remove(connectionId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    // ── Async Screening ───────────────────────────────────────────────

    /**
     * Screen a transfer's content against the screening-service (AML/sanctions).
     *
     * @param sourceIp source IP address
     * @param port     listen port
     * @param protocol detected protocol
     * @param filename filename if known
     * @param content  file content bytes
     * @return a future with the screening result
     */
    public CompletableFuture<ScreeningResult> screenTransfer(String sourceIp, int port,
                                                              String protocol, String filename,
                                                              byte[] content) {
        if (!config.enabled()) {
            return CompletableFuture.completedFuture(
                new ScreeningResult(Outcome.SKIPPED, "screening_disabled", 0, 0));
        }

        if (content == null || content.length == 0) {
            totalSkipped.incrementAndGet();
            return CompletableFuture.completedFuture(
                new ScreeningResult(Outcome.SKIPPED, "empty_content", 0, 0));
        }

        if (content.length > config.maxScreeningSizeBytes()) {
            totalSkipped.incrementAndGet();
            return CompletableFuture.completedFuture(
                new ScreeningResult(Outcome.SKIPPED, "content_exceeds_max_size", 0, 0));
        }

        return CompletableFuture.supplyAsync(() -> doScreen(sourceIp, port, protocol, filename, content),
            screeningExecutor);
    }

    // ── Stats ─────────────────────────────────────────────────────────

    /**
     * Get screening statistics.
     *
     * @return map of stat name to value
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", config.enabled());
        stats.put("totalScreened", totalScreened.get());
        stats.put("totalClear", totalClear.get());
        stats.put("totalHit", totalHit.get());
        stats.put("totalPossibleHit", totalPossibleHit.get());
        stats.put("totalError", totalError.get());
        stats.put("totalTimeout", totalTimeout.get());
        stats.put("totalSkipped", totalSkipped.get());
        stats.put("activeBuffers", buffers.size());
        stats.put("pendingScreenings", pendingScreenings.size());
        return stats;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Shutdown the screening bridge. Cancels pending screenings and cleans up buffers.
     */
    public void shutdown() {
        log.info("Shutting down content screening bridge — {} pending screenings",
            pendingScreenings.size());

        // Cancel all pending screenings
        pendingScreenings.values().forEach(f -> f.cancel(true));
        pendingScreenings.clear();

        // Clear buffers
        buffers.clear();

        // Shutdown executor
        screeningExecutor.shutdownNow();
        try {
            if (!screeningExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Screening executor did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Perform the actual HTTP screening call to the screening-service.
     */
    private ScreeningResult doScreen(String sourceIp, int port, String protocol,
                                      String filename, byte[] content) {
        long startTime = System.currentTimeMillis();
        totalScreened.incrementAndGet();

        try {
            HttpRequest.Builder screenBuilder = HttpRequest.newBuilder()
                .uri(URI.create(screeningServiceUrl + "/api/v1/screening/scan/text"))
                .header("Content-Type", "text/plain")
                .header("X-Source-Ip", sourceIp != null ? sourceIp : "unknown")
                .header("X-Source-Port", String.valueOf(port))
                .header("X-Protocol", protocol != null ? protocol : "UNKNOWN")
                .header("X-Filename", filename != null ? filename : "unknown")
                .timeout(Duration.ofSeconds(config.screeningTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content));
            if (spiffeAuth != null && spiffeAuth.isAvailable()) {
                String token = spiffeAuth.getJwtSvidFor("screening-service");
                if (token != null) screenBuilder.header("Authorization", "Bearer " + token);
            }
            HttpRequest request = screenBuilder.build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            long durationMs = System.currentTimeMillis() - startTime;

            if (response.statusCode() == 200) {
                return parseScreeningResponse(response.body(), durationMs);
            } else {
                log.warn("Screening service returned HTTP {}: {}", response.statusCode(), response.body());
                return new ScreeningResult(Outcome.ERROR,
                    "http_" + response.statusCode(), 0, durationMs);
            }
        } catch (java.net.http.HttpTimeoutException e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Screening timed out after {}ms for {} from {}", durationMs, protocol, sourceIp);
            return new ScreeningResult(Outcome.TIMEOUT, "timeout_after_" + durationMs + "ms", 0, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startTime;
            return new ScreeningResult(Outcome.ERROR, "interrupted", 0, durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Screening error for {} from {}: {}", protocol, sourceIp, e.getMessage());
            return new ScreeningResult(Outcome.ERROR, e.getMessage(), 0, durationMs);
        }
    }

    /**
     * Parse the screening-service JSON response.
     * Expected structure: { "outcome": "CLEAR|HIT|POSSIBLE_HIT", "hits": N, "detail": "..." }
     *
     * Uses lightweight manual JSON parsing to avoid external library dependency
     * (DMZ proxy is intentionally isolated).
     */
    private ScreeningResult parseScreeningResponse(String responseBody, long durationMs) {
        try {
            String outcomeStr = extractJsonString(responseBody, "outcome");
            String detail = extractJsonString(responseBody, "detail");
            int hits = extractJsonInt(responseBody, "hits");

            // Fall back to hitsFound key for compatibility
            if (hits == 0) {
                hits = extractJsonInt(responseBody, "hitsFound");
            }

            Outcome outcome;
            try {
                outcome = Outcome.valueOf(outcomeStr != null ? outcomeStr.toUpperCase() : "ERROR");
            } catch (IllegalArgumentException e) {
                outcome = Outcome.ERROR;
                detail = "unknown_outcome: " + outcomeStr;
            }

            return new ScreeningResult(outcome, detail != null ? detail : "", hits, durationMs);
        } catch (Exception e) {
            log.warn("Failed to parse screening response: {}", e.getMessage());
            return new ScreeningResult(Outcome.ERROR, "parse_error: " + e.getMessage(), 0, durationMs);
        }
    }

    /**
     * Extract a string value from a simple JSON object. Avoids external JSON library dependency.
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIndex = json.indexOf(search);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + search.length());
        if (colonIndex < 0) return null;

        int start = colonIndex + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end > start) {
                return json.substring(start + 1, end);
            }
        }

        // Unquoted value (number, boolean, null)
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    /**
     * Extract an integer value from a simple JSON object.
     */
    private static int extractJsonInt(String json, String key) {
        String value = extractJsonString(json, key);
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Update stats counters based on screening outcome.
     */
    private void updateStats(Outcome outcome) {
        switch (outcome) {
            case CLEAR -> totalClear.incrementAndGet();
            case HIT -> totalHit.incrementAndGet();
            case POSSIBLE_HIT -> totalPossibleHit.incrementAndGet();
            case ERROR -> totalError.incrementAndGet();
            case TIMEOUT -> totalTimeout.incrementAndGet();
            case SKIPPED -> totalSkipped.incrementAndGet();
        }
    }
}
