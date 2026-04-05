package com.filetransfer.ai.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for all AI Engine background agents.
 *
 * <p>Agents are autonomous workers that collect intelligence, analyze threats,
 * and continuously improve the security engine. Each agent is scheduled on its
 * own cadence via {@link AgentManager} and follows a template-method lifecycle:
 * <ol>
 *   <li>Guard against {@link AgentStatus#DISABLED}</li>
 *   <li>Transition to {@link AgentStatus#RUNNING}</li>
 *   <li>Invoke {@link #execute()} (subclass logic)</li>
 *   <li>Call {@link #onSuccess()} or {@link #onFailure(Exception)}</li>
 *   <li>Update execution metrics and return to {@link AgentStatus#IDLE}</li>
 * </ol>
 *
 * <p>Subclasses must implement {@link #execute()} (the main work) and
 * {@link #getSchedule()} (a human-readable description of the run cadence).
 */
public abstract class BackgroundAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ── Agent Status ──────────────────────────────────────────────────

    /** Lifecycle state of a background agent. */
    public enum AgentStatus {
        /** Idle and waiting for the next scheduled invocation. */
        IDLE,
        /** Currently executing. */
        RUNNING,
        /** Last execution ended with an unhandled exception. */
        ERROR,
        /** Administratively disabled — will not run until re-enabled. */
        DISABLED
    }

    /** Execution priority used for ordering and resource allocation. */
    public enum AgentPriority {
        LOW(1),
        MEDIUM(5),
        HIGH(8),
        CRITICAL(10);

        private final int value;

        AgentPriority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    // ── Identity ──────────────────────────────────────────────────────

    protected final String agentId;
    protected final String agentName;
    protected final AgentPriority priority;

    // ── Mutable State ─────────────────────────────────────────────────

    protected volatile AgentStatus status = AgentStatus.IDLE;

    // ── Execution Metrics ─────────────────────────────────────────────

    protected final AtomicLong executionCount = new AtomicLong(0);
    protected final AtomicLong successCount = new AtomicLong(0);
    protected final AtomicLong failureCount = new AtomicLong(0);
    protected final AtomicLong itemsProcessed = new AtomicLong(0);
    protected volatile Instant lastExecutionTime;
    protected volatile Instant lastSuccessTime;
    protected volatile Duration lastExecutionDuration;

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Creates a new background agent.
     *
     * @param agentId   unique identifier (e.g. {@code "osint-collector"})
     * @param agentName human-readable name (e.g. {@code "OSINT Collector Agent"})
     * @param priority  execution priority
     */
    protected BackgroundAgent(String agentId, String agentName, AgentPriority priority) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.priority = priority;
    }

    // ── Abstract Methods ──────────────────────────────────────────────

    /**
     * Main agent logic. Implementations should perform their collection,
     * analysis, or maintenance work here and update {@link #itemsProcessed}
     * as appropriate.
     */
    public abstract void execute();

    /**
     * Returns a human-readable schedule description (e.g. {@code "every 15 minutes"}).
     *
     * @return schedule description
     */
    protected abstract String getSchedule();

    // ── Template-Method Lifecycle ──────────────────────────────────────

    /**
     * Runs the full agent lifecycle. Called by the scheduler — do not override.
     * Handles status transitions, metrics, and exception safety.
     */
    public final void run() {
        if (status == AgentStatus.DISABLED) {
            return;
        }

        status = AgentStatus.RUNNING;
        Instant start = Instant.now();

        try {
            execute();
            successCount.incrementAndGet();
            lastSuccessTime = Instant.now();
            onSuccess();
        } catch (Exception e) {
            failureCount.incrementAndGet();
            onFailure(e);
            status = AgentStatus.ERROR;
            return;
        } finally {
            executionCount.incrementAndGet();
            lastExecutionTime = Instant.now();
            lastExecutionDuration = Duration.between(start, Instant.now());
        }

        status = AgentStatus.IDLE;
    }

    // ── Hooks ─────────────────────────────────────────────────────────

    /** Called after a successful execution. Override to add post-success logic. */
    protected void onSuccess() {
        // default no-op — subclasses may override
    }

    /** Called after a failed execution. Override to add custom error handling. */
    protected void onFailure(Exception e) {
        log.error("Agent {} failed during execution: {}", agentName, e.getMessage(), e);
    }

    // ── Metrics ───────────────────────────────────────────────────────

    /**
     * Returns a comprehensive snapshot of this agent's runtime metrics.
     *
     * @return metric map suitable for JSON serialization
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("agentId", agentId);
        metrics.put("agentName", agentName);
        metrics.put("priority", priority.name());
        metrics.put("status", status.name());
        metrics.put("schedule", getSchedule());
        metrics.put("executionCount", executionCount.get());
        metrics.put("successCount", successCount.get());
        metrics.put("failureCount", failureCount.get());
        metrics.put("itemsProcessed", itemsProcessed.get());
        metrics.put("lastExecutionTime", lastExecutionTime != null ? lastExecutionTime.toString() : null);
        metrics.put("lastSuccessTime", lastSuccessTime != null ? lastSuccessTime.toString() : null);
        metrics.put("lastExecutionDurationMs",
                lastExecutionDuration != null ? lastExecutionDuration.toMillis() : null);

        double successRate = executionCount.get() > 0
                ? (double) successCount.get() / executionCount.get() * 100.0
                : 0.0;
        metrics.put("successRatePercent", Math.round(successRate * 10.0) / 10.0);

        return metrics;
    }

    // ── Control ───────────────────────────────────────────────────────

    /** Enable the agent so it will execute on the next scheduled tick. */
    public void enable() {
        status = AgentStatus.IDLE;
        log.info("Agent {} enabled", agentName);
    }

    /** Disable the agent — it will skip all future scheduled invocations until re-enabled. */
    public void disable() {
        status = AgentStatus.DISABLED;
        log.info("Agent {} disabled", agentName);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public AgentPriority getPriority() {
        return priority;
    }

    public AgentStatus getStatus() {
        return status;
    }
}
