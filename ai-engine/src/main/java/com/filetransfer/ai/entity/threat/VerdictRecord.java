package com.filetransfer.ai.entity.threat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent verdict record for the SecurityAI proxy intelligence subsystem.
 *
 * <p>Replaces the in-memory ring buffer in
 * {@code com.filetransfer.ai.service.proxy.ProxyIntelligenceService} with a
 * durable, queryable audit trail. Every verdict — allow, throttle, challenge,
 * block, or blackhole — is persisted here for compliance reporting, trend
 * analysis, and SOC dashboards.</p>
 *
 * <h3>Integration with existing ProxyIntelligenceService</h3>
 * <p>The service's {@code recordVerdict()} method should be updated to persist a
 * {@code VerdictRecord} in addition to (or instead of) the in-memory deque.
 * The {@code cached} flag distinguishes verdicts served from the proxy's local
 * cache vs. freshly computed by the intelligence pipeline.</p>
 *
 * @see com.filetransfer.ai.service.proxy.ProxyIntelligenceService
 * @see SecurityEvent
 */
@Entity
@Table(name = "verdict_records", indexes = {
    @Index(name = "idx_verdict_record_timestamp", columnList = "timestamp"),
    @Index(name = "idx_verdict_record_source_ip", columnList = "sourceIp"),
    @Index(name = "idx_verdict_record_action", columnList = "action"),
    @Index(name = "idx_verdict_record_risk_score", columnList = "riskScore"),
    @Index(name = "idx_verdict_record_protocol", columnList = "protocol")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerdictRecord {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID verdictId;

    /** When the verdict was computed. */
    private Instant timestamp;

    /** Source IP address of the evaluated connection. */
    @Column(length = 45, nullable = false)
    private String sourceIp;

    /** Target port the connection was destined for. */
    private int targetPort;

    /** Detected or declared protocol (e.g. "SSH", "FTP", "TLS"). */
    @Column(length = 20)
    private String protocol;

    /**
     * Verdict action: {@code ALLOW}, {@code THROTTLE}, {@code CHALLENGE},
     * {@code BLOCK}, {@code BLACKHOLE}.
     */
    @Column(length = 20, nullable = false)
    private String action;

    /** Composite risk score (0–100) that produced this verdict. */
    private int riskScore;

    /** Human-readable reason for the verdict. */
    @Column(length = 1024)
    private String reason;

    /** JSON array of contributing threat signals. */
    @Column(columnDefinition = "TEXT")
    private String signals;

    /** Time-to-live in seconds — how long the proxy should cache this verdict. */
    private int ttlSeconds;

    /** Whether this verdict was served from cache rather than freshly computed. */
    private boolean cached;

    // ── Lifecycle Callbacks ───────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (verdictId == null) {
            verdictId = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    // ── Convenience Methods ───────────────────────────────────────────

    /**
     * Returns {@code true} if this verdict resulted in the connection being denied.
     */
    public boolean isDenied() {
        return "BLOCK".equals(action) || "BLACKHOLE".equals(action);
    }

    /**
     * Returns {@code true} if this verdict applied rate-limiting.
     */
    public boolean isThrottled() {
        return "THROTTLE".equals(action);
    }

    /**
     * Returns {@code true} if the risk score exceeds the given threshold.
     */
    public boolean isHighRisk(int threshold) {
        return riskScore >= threshold;
    }
}
