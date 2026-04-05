package com.filetransfer.dmz.security;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Threat Event Reporter — batches and sends security events to the AI engine.
 * Non-blocking: events are queued and flushed periodically or when batch is full.
 *
 * Product-agnostic: reports generic connection events.
 */
@Slf4j
public class ThreatEventReporter {

    private final AiVerdictClient aiClient;
    private final BlockingQueue<Map<String, Object>> eventQueue;
    private final int batchSize;
    private final long flushIntervalMs;
    private final ScheduledExecutorService scheduler;

    public ThreatEventReporter(AiVerdictClient aiClient, int queueCapacity, int batchSize, long flushIntervalMs) {
        this.aiClient = aiClient;
        this.eventQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "threat-event-flush");
            t.setDaemon(true);
            return t;
        });

        // Periodic flush
        scheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ── Event Reporting ────────────────────────────────────────────────

    public void reportConnectionOpened(String ip, int port, String protocol) {
        enqueue(Map.of(
            "eventType", "CONNECTION_OPENED",
            "sourceIp", ip,
            "targetPort", port,
            "detectedProtocol", protocol != null ? protocol : "UNKNOWN",
            "timestamp", Instant.now().toString()
        ));
    }

    public void reportConnectionClosed(String ip, int port, String protocol,
                                        long bytesIn, long bytesOut, long durationMs) {
        enqueue(buildEvent("CONNECTION_CLOSED", ip, port, protocol,
            bytesIn, bytesOut, durationMs, false, null));
    }

    public void reportRateLimitHit(String ip, int port, String protocol) {
        enqueue(buildEvent("RATE_LIMIT_HIT", ip, port, protocol,
            0, 0, 0, true, "rate_limit"));
    }

    public void reportRejected(String ip, int port, String protocol, String reason) {
        enqueue(buildEvent("REJECTED", ip, port, protocol,
            0, 0, 0, true, reason));
    }

    public void reportAuthFailure(String ip, int port, String protocol, String account) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "AUTH_FAILURE");
        event.put("sourceIp", ip);
        event.put("targetPort", port);
        event.put("detectedProtocol", protocol != null ? protocol : "UNKNOWN");
        event.put("blocked", false);
        if (account != null) event.put("account", account);
        event.put("timestamp", Instant.now().toString());
        enqueue(event);
    }

    public void reportBytesTransferred(String ip, int port, long bytesIn, long bytesOut) {
        enqueue(Map.of(
            "eventType", "BYTES_TRANSFERRED",
            "sourceIp", ip,
            "targetPort", port,
            "bytesIn", bytesIn,
            "bytesOut", bytesOut,
            "timestamp", Instant.now().toString()
        ));
    }

    // ── Flush ──────────────────────────────────────────────────────────

    public void flush() {
        List<Map<String, Object>> batch = new ArrayList<>(batchSize);
        eventQueue.drainTo(batch, batchSize);

        if (!batch.isEmpty()) {
            aiClient.reportEventsAsync(batch);
            log.debug("Flushed {} threat events to AI engine", batch.size());
        }
    }

    public int getPendingEventCount() {
        return eventQueue.size();
    }

    public void shutdown() {
        flush(); // final flush
        scheduler.shutdownNow();
    }

    // ── Private ────────────────────────────────────────────────────────

    private void enqueue(Map<String, Object> event) {
        if (!eventQueue.offer(event)) {
            log.debug("Event queue full, dropping event");
        }

        // Auto-flush if batch is full
        if (eventQueue.size() >= batchSize) {
            scheduler.submit(this::flush);
        }
    }

    private Map<String, Object> buildEvent(String type, String ip, int port,
                                            String protocol, long bytesIn, long bytesOut,
                                            long durationMs, boolean blocked, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", type);
        event.put("sourceIp", ip);
        event.put("targetPort", port);
        event.put("detectedProtocol", protocol != null ? protocol : "UNKNOWN");
        event.put("bytesIn", bytesIn);
        event.put("bytesOut", bytesOut);
        event.put("durationMs", durationMs);
        event.put("blocked", blocked);
        if (reason != null) event.put("blockReason", reason);
        event.put("timestamp", Instant.now().toString());
        return event;
    }
}
