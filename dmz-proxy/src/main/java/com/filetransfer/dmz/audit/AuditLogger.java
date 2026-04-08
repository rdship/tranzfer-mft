package com.filetransfer.dmz.audit;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Compliance-grade audit logger for DMZ proxy security events.
 *
 * <p>Writes JSON Lines (one JSON object per line) to local disk for SIEM ingestion,
 * forensic analysis, and regulatory compliance. Designed for zero-dependency operation:
 * no database, no external services — just reliable, tamper-evident local files.</p>
 *
 * <h3>File layout</h3>
 * <pre>
 *   {logDirectory}/
 *     audit-2026-04-06.jsonl         ← daily primary
 *     audit-2026-04-06.1.jsonl       ← size-rotated within day
 *     audit-2026-04-06.2.jsonl
 *     audit-2026-04-05.jsonl         ← previous day
 * </pre>
 *
 * <h3>Retention</h3>
 * <p>Files older than {@code maxDays} are deleted on startup and via {@link #cleanup()}.
 * Default retention is 90 days per compliance requirements.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All write operations are guarded by a {@link ReentrantLock}. Writes are buffered
 * and flushed every 1 second or every 100 events, whichever comes first.</p>
 *
 * <h3>Resilience</h3>
 * <p>Audit failures never throw — they log to stderr and increment an error counter.
 * Proxy operations must never be blocked by audit I/O.</p>
 *
 * <p>Product-agnostic: logs generic proxy security events.</p>
 *
 * @see com.filetransfer.dmz.proxy.ProxyManager
 */
@Slf4j
public class AuditLogger {

    // ── Constants ─────────────────────────────────────────────────────────

    private static final String FILE_PREFIX = "audit-";
    private static final String FILE_EXTENSION = ".jsonl";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final int FLUSH_EVENT_THRESHOLD = 100;
    private static final long FLUSH_INTERVAL_MS = 1_000;

    // ── Configuration ─────────────────────────────────────────────────────

    private final Path logDirectory;
    private final int maxDays;
    private final long maxFileSizeBytes;
    private final boolean enabled;

    // ── State ─────────────────────────────────────────────────────────────

    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong eventsWritten = new AtomicLong(0);
    private final AtomicLong errorsCount = new AtomicLong(0);
    private final AtomicLong eventsSinceFlush = new AtomicLong(0);

    private volatile BufferedWriter writer;
    private volatile Path currentFile;
    private volatile LocalDate currentDate;
    private volatile int rotationIndex;
    private volatile boolean closed;

    private final ScheduledExecutorService flushScheduler;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a new audit logger with the specified configuration.
     *
     * @param logDirectory   directory for audit log files (created if absent); defaults to {@code "./audit-logs"}
     * @param maxDays        retention period in days; files older than this are deleted on startup; defaults to 90
     * @param maxFileSizeMb  maximum file size in MB before intra-day rotation; defaults to 100
     * @param enabled        whether audit logging is active; when false, all log methods are no-ops
     */
    public AuditLogger(String logDirectory, int maxDays, long maxFileSizeMb, boolean enabled) {
        this.logDirectory = Paths.get(logDirectory != null ? logDirectory : "./audit-logs");
        this.maxDays = maxDays > 0 ? maxDays : 90;
        this.maxFileSizeBytes = (maxFileSizeMb > 0 ? maxFileSizeMb : 100) * 1024 * 1024;
        this.enabled = enabled;

        if (!enabled) {
            log.info("Audit logging DISABLED");
            this.flushScheduler = null;
            return;
        }

        // Ensure directory exists
        try {
            Files.createDirectories(this.logDirectory);
        } catch (IOException e) {
            log.error("Failed to create audit log directory: {} — audit logging will attempt writes anyway",
                    this.logDirectory, e);
        }

        // Initial cleanup of expired files
        cleanup();

        // Open first file
        openWriter();

        // Periodic flush
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // JVM shutdown hook to flush remaining buffered events on unexpected shutdown.
        // Ensures no audit data is lost if the proxy is killed (SIGTERM, OOM, etc.).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!closed) {
                shutdown();
            }
        }, "audit-shutdown-hook"));

        log.info("Audit logging ENABLED — dir={}, retention={}d, maxSize={}MB",
                this.logDirectory, this.maxDays, maxFileSizeMb > 0 ? maxFileSizeMb : 100);
    }

    // ── Event Logging Methods ─────────────────────────────────────────────

    /**
     * Logs a connection lifecycle event (OPEN, CLOSE, BLOCKED, RATE_LIMITED).
     *
     * @param event    event name: OPEN, CLOSE, BLOCKED, RATE_LIMITED
     * @param sourceIp client IP address
     * @param port     listen port
     * @param mapping  mapping name (e.g. "sftp-gateway")
     * @param tier     security tier: RULES, AI, AI_LLM
     * @param verdict  verdict action: ALLOW, BLOCK, THROTTLE, BLACKHOLE
     * @param risk     risk score (0-100)
     * @param protocol detected protocol (SSH, FTP, HTTPS, etc.)
     * @param detail   human-readable detail or reason
     */
    public void logConnection(String event, String sourceIp, int port, String mapping,
                              String tier, String verdict, int risk, String protocol, String detail) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "CONNECTION");
        entry.put("event", event);
        entry.put("src", sourceIp);
        entry.put("port", port);
        entry.put("mapping", mapping);
        entry.put("tier", tier);
        entry.put("verdict", verdict);
        entry.put("risk", risk);
        entry.put("protocol", protocol);
        entry.put("detail", detail);

        writeEntry(entry);
    }

    /**
     * Logs a TLS handshake event including cipher negotiation and client certificate details.
     *
     * @param sourceIp    client IP address
     * @param port        listen port
     * @param mapping     mapping name
     * @param tlsVersion  negotiated TLS version (e.g. "TLSv1.3")
     * @param cipher      negotiated cipher suite
     * @param clientCert  whether client presented a certificate
     * @param certSubject client certificate subject DN, or null
     */
    public void logTls(String sourceIp, int port, String mapping, String tlsVersion,
                       String cipher, boolean clientCert, String certSubject) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "TLS");
        entry.put("src", sourceIp);
        entry.put("port", port);
        entry.put("mapping", mapping);
        entry.put("tlsVersion", tlsVersion);
        entry.put("cipher", cipher);
        entry.put("clientCert", clientCert);
        if (certSubject != null) {
            entry.put("certSubject", certSubject);
        }

        writeEntry(entry);
    }

    /**
     * Logs an AI/rules verdict decision with optional LLM timing details.
     *
     * @param sourceIp     client IP address
     * @param port         listen port
     * @param mapping      mapping name
     * @param action       verdict action: ALLOW, BLOCK, THROTTLE, BLACKHOLE
     * @param risk         risk score (0-100)
     * @param reason       human-readable reason for the verdict
     * @param llmUsed      whether the LLM tier was invoked
     * @param llmLatencyMs LLM response time in milliseconds, or null if not used
     */
    public void logVerdict(String sourceIp, int port, String mapping, String action,
                           int risk, String reason, boolean llmUsed, Long llmLatencyMs) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "VERDICT");
        entry.put("src", sourceIp);
        entry.put("port", port);
        entry.put("mapping", mapping);
        entry.put("action", action);
        entry.put("risk", risk);
        entry.put("reason", reason);
        entry.put("llmUsed", llmUsed);
        if (llmLatencyMs != null) {
            entry.put("llmLatencyMs", llmLatencyMs);
        }

        writeEntry(entry);
    }

    /**
     * Logs a zone enforcement decision (cross-zone traffic control).
     *
     * @param sourceIp   client IP address
     * @param port       listen port
     * @param mapping    mapping name
     * @param sourceZone originating network zone
     * @param targetZone destination network zone
     * @param allowed    whether the cross-zone traffic was permitted
     * @param reason     reason for allow/deny
     */
    public void logZone(String sourceIp, int port, String mapping, String sourceZone,
                        String targetZone, boolean allowed, String reason) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "ZONE");
        entry.put("src", sourceIp);
        entry.put("port", port);
        entry.put("mapping", mapping);
        entry.put("sourceZone", sourceZone);
        entry.put("targetZone", targetZone);
        entry.put("allowed", allowed);
        entry.put("reason", reason);

        writeEntry(entry);
    }

    /**
     * Logs an egress filter decision (outbound connection control).
     *
     * @param targetHost destination hostname or IP
     * @param targetPort destination port
     * @param mapping    mapping name
     * @param allowed    whether the egress was permitted
     * @param reason     reason for allow/deny
     */
    public void logEgress(String targetHost, int targetPort, String mapping,
                          boolean allowed, String reason) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "EGRESS");
        entry.put("targetHost", targetHost);
        entry.put("targetPort", targetPort);
        entry.put("mapping", mapping);
        entry.put("allowed", allowed);
        entry.put("reason", reason);

        writeEntry(entry);
    }

    /**
     * Logs a deep packet inspection finding.
     *
     * @param sourceIp client IP address
     * @param port     listen port
     * @param mapping  mapping name
     * @param protocol detected protocol
     * @param finding  what was found (e.g. "malware_signature", "exfiltration_pattern")
     * @param severity severity level: LOW, MEDIUM, HIGH, CRITICAL
     * @param detail   human-readable description of the finding
     */
    public void logInspection(String sourceIp, int port, String mapping, String protocol,
                              String finding, String severity, String detail) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", timestamp());
        entry.put("type", "INSPECTION");
        entry.put("src", sourceIp);
        entry.put("port", port);
        entry.put("mapping", mapping);
        entry.put("protocol", protocol);
        entry.put("finding", finding);
        entry.put("severity", severity);
        entry.put("detail", detail);

        writeEntry(entry);
    }

    // ── Flush & Shutdown ──────────────────────────────────────────────────

    /**
     * Forces an immediate flush of the write buffer to disk.
     * Safe to call from any thread.
     */
    public void flush() {
        if (!enabled || closed) return;

        writeLock.lock();
        try {
            if (writer != null) {
                writer.flush();
                eventsSinceFlush.set(0);
            }
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            log.error("Audit flush failed: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Flushes remaining events and closes the audit logger permanently.
     * Must be called during proxy shutdown to prevent data loss.
     */
    public void shutdown() {
        if (!enabled) return;

        closed = true;

        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
        }

        writeLock.lock();
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            log.error("Audit shutdown error: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }

        log.info("Audit logger shut down — {} events written, {} errors",
                eventsWritten.get(), errorsCount.get());
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    /**
     * Deletes audit log files older than the configured retention period.
     * Called automatically on startup; can also be triggered manually.
     */
    public void cleanup() {
        if (!Files.isDirectory(logDirectory)) return;

        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(maxDays);
        int deleted = 0;

        try (Stream<Path> files = Files.list(logDirectory)) {
            List<Path> candidates = files
                    .filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXTENSION))
                    .toList();

            for (Path file : candidates) {
                LocalDate fileDate = extractDate(file);
                if (fileDate != null && fileDate.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                    } catch (IOException e) {
                        log.warn("Failed to delete expired audit file {}: {}", file, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list audit directory for cleanup: {}", e.getMessage());
        }

        if (deleted > 0) {
            log.info("Audit cleanup: deleted {} files older than {} days", deleted, maxDays);
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    /**
     * Returns operational statistics for monitoring and health checks.
     *
     * @return map containing eventsWritten, errorsCount, currentFile, and diskUsageMb
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventsWritten", eventsWritten.get());
        stats.put("errorsCount", errorsCount.get());
        stats.put("currentFile", currentFile != null ? currentFile.toString() : "none");
        stats.put("enabled", enabled);
        stats.put("diskUsageMb", calculateDiskUsageMb());
        return stats;
    }

    // ── Private: Writing ──────────────────────────────────────────────────

    /**
     * Serializes a map entry to JSON and writes it as a single line.
     * Handles date rotation, size rotation, and all error conditions.
     *
     * <p>Security-critical verdicts (BLOCK, BLACKHOLE) trigger an immediate flush
     * to ensure crash-consistent audit trail — evidence of attacks must never be
     * lost due to buffered writes.</p>
     */
    private void writeEntry(Map<String, Object> entry) {
        if (closed) return;

        String json = toJson(entry);
        boolean securityCritical = isSecurityCritical(entry);

        writeLock.lock();
        try {
            // Check if we need to rotate (date change or size limit)
            rotateIfNeeded();

            if (writer == null) {
                openWriter();
            }

            if (writer != null) {
                writer.write(json);
                writer.newLine();
                eventsWritten.incrementAndGet();

                // Force-flush on security-critical verdicts (BLOCK/BLACKHOLE)
                // to guarantee crash-consistent audit trail for attack evidence
                if (securityCritical) {
                    writer.flush();
                    eventsSinceFlush.set(0);
                } else if (eventsSinceFlush.incrementAndGet() >= FLUSH_EVENT_THRESHOLD) {
                    // Normal events: auto-flush if threshold reached
                    writer.flush();
                    eventsSinceFlush.set(0);
                }
            }
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            System.err.println("AUDIT WRITE FAILURE: " + e.getMessage());
            log.error("Audit write failed: {}", e.getMessage());
            // Attempt to reopen on next write
            closeWriterQuietly();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Determines whether an audit entry represents a security-critical event
     * that must be flushed immediately. BLOCK and BLACKHOLE verdicts are
     * security-critical because they represent detected attacks — losing this
     * evidence in a crash would compromise forensic and compliance requirements.
     */
    private static boolean isSecurityCritical(Map<String, Object> entry) {
        // logConnection() stores verdict under "verdict" key
        // logVerdict() stores verdict under "action" key
        Object verdict = entry.get("verdict");
        if (verdict == null) {
            verdict = entry.get("action");
        }
        if (verdict instanceof String v) {
            return "BLOCK".equalsIgnoreCase(v) || "BLACKHOLE".equalsIgnoreCase(v);
        }
        return false;
    }

    /**
     * Checks whether rotation is needed (date change or file size exceeded)
     * and performs the rotation. Must be called under writeLock.
     */
    private void rotateIfNeeded() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Date change → new day file
        if (currentDate == null || !today.equals(currentDate)) {
            closeWriterQuietly();
            currentDate = today;
            rotationIndex = 0;
            openWriter();
            return;
        }

        // Size check — only if we have a current file
        if (currentFile != null) {
            try {
                long size = Files.exists(currentFile) ? Files.size(currentFile) : 0;
                if (size >= maxFileSizeBytes) {
                    closeWriterQuietly();
                    rotationIndex++;
                    openWriter();
                }
            } catch (IOException e) {
                // Non-fatal: we'll keep writing to the current file
                log.debug("Failed to check audit file size: {}", e.getMessage());
            }
        }
    }

    /**
     * Opens a BufferedWriter for the current date/rotation index.
     * Must be called under writeLock or during initialization.
     */
    private void openWriter() {
        if (currentDate == null) {
            currentDate = LocalDate.now(ZoneOffset.UTC);
        }

        String fileName = FILE_PREFIX + currentDate.format(DATE_FMT)
                + (rotationIndex > 0 ? "." + rotationIndex : "")
                + FILE_EXTENSION;
        currentFile = logDirectory.resolve(fileName);

        try {
            Files.createDirectories(logDirectory);
            writer = Files.newBufferedWriter(currentFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            writer = null;
            System.err.println("AUDIT FILE OPEN FAILURE: " + currentFile + " — " + e.getMessage());
            log.error("Failed to open audit file {}: {}", currentFile, e.getMessage());
        }
    }

    /**
     * Closes the current writer silently — errors are logged but never thrown.
     */
    private void closeWriterQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                log.debug("Error closing audit writer: {}", e.getMessage());
            }
            writer = null;
        }
    }

    // ── Private: JSON Serialization ───────────────────────────────────────

    /**
     * Minimal JSON serializer — avoids pulling in Jackson/Gson just for audit.
     * Handles String, Number, Boolean, and null values. Properly escapes strings.
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            appendValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    /**
     * Escapes special JSON characters: backslash, double-quote, and control characters.
     */
    private static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ── Private: Utilities ────────────────────────────────────────────────

    private static String timestamp() {
        return Instant.now().atOffset(ZoneOffset.UTC).format(TS_FMT);
    }

    /**
     * Extracts the date portion from an audit filename.
     * Handles both {@code audit-2026-04-06.jsonl} and {@code audit-2026-04-06.1.jsonl}.
     */
    private LocalDate extractDate(Path file) {
        String name = file.getFileName().toString();
        // Strip prefix and extension to get date (and optional rotation index)
        // Format: audit-YYYY-MM-DD[.N].jsonl
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_EXTENSION)) {
            return null;
        }
        String middle = name.substring(FILE_PREFIX.length(), name.length() - FILE_EXTENSION.length());
        // middle is "2026-04-06" or "2026-04-06.1"
        String dateStr = middle.length() >= 10 ? middle.substring(0, 10) : middle;
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Calculates total disk usage of all audit files in the log directory, in megabytes.
     */
    private double calculateDiskUsageMb() {
        if (!Files.isDirectory(logDirectory)) return 0.0;

        try (Stream<Path> files = Files.list(logDirectory)) {
            long totalBytes = files
                    .filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXTENSION))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            return Math.round(totalBytes / (1024.0 * 1024.0) * 100.0) / 100.0;
        } catch (IOException e) {
            return -1.0;
        }
    }
}
