package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Configurable function queue — each step type in the flow pipeline has its own
 * queue with independently tunable retry, timeout, concurrency, and priority.
 *
 * <p>Admins can:
 * <ul>
 *   <li>View all queues and their real-time stats (depth, throughput, error rate)
 *   <li>Edit retry count, timeout, concurrency per queue
 *   <li>Add custom function queues (for EXECUTE_SCRIPT or plugin functions)
 *   <li>Enable/disable a queue (disabled = step is skipped in flows)
 *   <li>Delete only if no active flow references this function type
 * </ul>
 *
 * <p>The 15 built-in queues are seeded on first boot. Custom queues can be added
 * for plugin step types (gRPC/WASM functions registered in FlowFunctionRegistry).
 */
@Entity
@Table(name = "function_queues")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FunctionQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Step type this queue handles — e.g., SCREEN, ENCRYPT_PGP, CONVERT_EDI */
    @NotBlank
    @Column(unique = true, nullable = false, length = 50)
    private String functionType;

    /** Human-readable name */
    @NotBlank
    @Column(nullable = false)
    private String displayName;

    /** Description of what this function does */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Category for UI grouping: SECURITY, TRANSFORM, DELIVERY, CUSTOM */
    @Column(length = 30)
    @Builder.Default
    private String category = "CUSTOM";

    /** Kafka topic name — auto-derived as flow.step.{functionType} */
    @Column(nullable = false)
    private String topicName;

    /** Max retry attempts before sending to DLQ (0 = no retry) */
    @Builder.Default
    private int retryCount = 0;

    /** Backoff between retries in milliseconds (exponential: backoff * 2^attempt) */
    @Builder.Default
    private long retryBackoffMs = 5000;

    /** Max time in seconds a single step execution can take before timeout */
    @Builder.Default
    private int timeoutSeconds = 60;

    /** Min concurrent consumers (Kafka consumer threads) */
    @Builder.Default
    private int minConcurrency = 2;

    /** Max concurrent consumers (auto-scales up to this) */
    @Builder.Default
    private int maxConcurrency = 8;

    /** Message TTL in the queue before expiry (milliseconds) */
    @Builder.Default
    private long messageTtlMs = 600_000; // 10 minutes

    /** Whether this queue is active — disabled queues skip the step in flows */
    @Builder.Default
    private boolean enabled = true;

    /** Whether this is a built-in queue (cannot be deleted) */
    @Builder.Default
    private boolean builtIn = true;

    /** Optional: extra config for custom functions (gRPC endpoint, WASM module, etc.) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> customConfig;

    /** Number of active flows that reference this function type */
    @Transient
    private long activeFlowCount;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
