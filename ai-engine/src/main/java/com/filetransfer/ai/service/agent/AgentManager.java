package com.filetransfer.ai.service.agent;

import com.filetransfer.ai.service.agent.BackgroundAgent.AgentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Spring-managed lifecycle manager for all {@link BackgroundAgent} instances.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register agents with their desired execution interval</li>
 *   <li>Schedule periodic execution on a shared thread pool</li>
 *   <li>Expose agent statuses and metrics for dashboards</li>
 *   <li>Health-check agents and automatically restart those stuck in ERROR</li>
 *   <li>Support on-demand triggering, pausing, and resuming of individual agents</li>
 *   <li>Graceful shutdown on application context close</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentManager {

    private final Map<String, BackgroundAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> agentIntervals = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final Instant startupTime = Instant.now();

    @Value("${ai.agents.enabled:true}")
    private boolean agentsEnabled;

    @Value("${ai.agents.health-check-interval-seconds:60}")
    private int healthCheckInterval;

    public AgentManager() {
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("ai-agent-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    // ── Registration ──────────────────────────────────────────────────

    /**
     * Registers an agent and schedules it for periodic execution.
     *
     * <p>Start times are staggered based on agent priority to avoid
     * thundering-herd effects at boot.
     *
     * @param agent      the agent to register
     * @param intervalMs interval between executions in milliseconds
     */
    public void registerAgent(BackgroundAgent agent, long intervalMs) {
        agents.put(agent.getAgentId(), agent);
        agentIntervals.put(agent.getAgentId(), intervalMs);

        if (agentsEnabled) {
            long delay = initialDelay(agent);
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    agent::run,
                    delay,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
            scheduledTasks.put(agent.getAgentId(), future);

            log.info("Registered agent: {} (id={}, interval={}ms, priority={}, initialDelay={}ms)",
                    agent.getAgentName(), agent.getAgentId(), intervalMs,
                    agent.getPriority(), delay);
        } else {
            log.info("Agent registered but scheduling disabled: {} (id={})",
                    agent.getAgentName(), agent.getAgentId());
        }
    }

    /**
     * Computes a staggered initial delay so agents don't all fire at the same instant.
     * Higher-priority agents start sooner.
     */
    private long initialDelay(BackgroundAgent agent) {
        int priorityValue = agent.getPriority().getValue();
        // CRITICAL (10) -> 2s, HIGH (8) -> 4s, MEDIUM (5) -> 7s, LOW (1) -> 11s
        return (12L - priorityValue) * 1000L;
    }

    // ── On-Demand Execution ───────────────────────────────────────────

    /**
     * Triggers an immediate, one-shot execution of the specified agent.
     * Does not affect the regular schedule.
     *
     * @param agentId the agent to trigger
     * @throws IllegalArgumentException if no agent with that ID is registered
     */
    public void triggerAgent(String agentId) {
        BackgroundAgent agent = requireAgent(agentId);
        log.info("Manually triggering agent: {}", agent.getAgentName());
        scheduler.submit(agent::run);
    }

    // ── Pause / Resume ────────────────────────────────────────────────

    /**
     * Pauses a running agent by cancelling its scheduled task and marking it disabled.
     *
     * @param agentId the agent to pause
     */
    public void pauseAgent(String agentId) {
        BackgroundAgent agent = requireAgent(agentId);
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
        }
        agent.disable();
        log.info("Paused agent: {}", agent.getAgentName());
    }

    /**
     * Resumes a previously paused agent by re-enabling it and rescheduling.
     *
     * @param agentId the agent to resume
     */
    public void resumeAgent(String agentId) {
        BackgroundAgent agent = requireAgent(agentId);
        Long intervalMs = agentIntervals.get(agentId);
        if (intervalMs == null) {
            log.warn("Cannot resume agent {} — no interval recorded", agentId);
            return;
        }

        agent.enable();

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                agent::run,
                1000L,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        scheduledTasks.put(agentId, future);
        log.info("Resumed agent: {} (interval={}ms)", agent.getAgentName(), intervalMs);
    }

    // ── Status & Metrics ──────────────────────────────────────────────

    /**
     * Returns the status overview of every registered agent.
     *
     * @return list of agent status maps
     */
    public List<Map<String, Object>> getAgentStatuses() {
        return agents.values().stream()
                .sorted(Comparator.comparingInt(a -> -a.getPriority().getValue()))
                .map(agent -> {
                    Map<String, Object> status = new LinkedHashMap<>();
                    status.put("agentId", agent.getAgentId());
                    status.put("agentName", agent.getAgentName());
                    status.put("status", agent.getStatus().name());
                    status.put("priority", agent.getPriority().name());
                    status.put("schedule", agent.getSchedule());
                    status.put("executionCount", agent.executionCount.get());
                    status.put("successCount", agent.successCount.get());
                    status.put("failureCount", agent.failureCount.get());
                    status.put("itemsProcessed", agent.itemsProcessed.get());
                    status.put("lastExecutionTime",
                            agent.lastExecutionTime != null ? agent.lastExecutionTime.toString() : null);
                    return status;
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns detailed metrics for a specific agent.
     *
     * @param agentId the agent ID
     * @return metric map
     * @throws IllegalArgumentException if the agent is not found
     */
    public Map<String, Object> getAgentMetrics(String agentId) {
        return requireAgent(agentId).getMetrics();
    }

    // ── Health Check ──────────────────────────────────────────────────

    /**
     * Periodic health check that detects agents in ERROR state and attempts
     * recovery, as well as agents that appear stuck (running far longer than
     * their configured interval).
     */
    @Scheduled(fixedDelayString = "${ai.agents.health-check-interval-seconds:60}000")
    public void healthCheck() {
        for (Map.Entry<String, BackgroundAgent> entry : agents.entrySet()) {
            String agentId = entry.getKey();
            BackgroundAgent agent = entry.getValue();

            // Skip disabled agents
            if (agent.getStatus() == AgentStatus.DISABLED) {
                continue;
            }

            // Recover agents in ERROR state
            if (agent.getStatus() == AgentStatus.ERROR) {
                log.warn("Agent {} ({}) in ERROR state — attempting recovery",
                        agent.getAgentName(), agentId);
                recoverAgent(agentId, agent);
            }

            // Detect stuck agents: running longer than 10x their scheduled interval
            if (agent.getStatus() == AgentStatus.RUNNING && agent.lastExecutionDuration != null) {
                Long intervalMs = agentIntervals.get(agentId);
                if (intervalMs != null) {
                    long maxAllowedMs = intervalMs * 10;
                    Duration runningFor = agent.lastExecutionDuration;
                    if (runningFor.toMillis() > maxAllowedMs) {
                        log.error("Agent {} appears stuck — last execution took {}ms " +
                                        "(limit: {}ms). Cancelling and rescheduling.",
                                agent.getAgentName(), runningFor.toMillis(), maxAllowedMs);
                        recoverAgent(agentId, agent);
                    }
                }
            }
        }
    }

    /**
     * Cancels the current scheduled task for the agent and reschedules it.
     */
    private void recoverAgent(String agentId, BackgroundAgent agent) {
        // Cancel existing task
        ScheduledFuture<?> existing = scheduledTasks.remove(agentId);
        if (existing != null) {
            existing.cancel(false);
        }

        // Re-enable and reschedule
        agent.enable();
        Long intervalMs = agentIntervals.get(agentId);
        if (intervalMs != null) {
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    agent::run,
                    5000L, // short delay before retry
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
            scheduledTasks.put(agentId, future);
            log.info("Recovered agent: {} — rescheduled with 5s initial delay", agent.getAgentName());
        }
    }

    // ── Dashboard ─────────────────────────────────────────────────────

    /**
     * Returns a summary dashboard suitable for API exposure.
     *
     * @return dashboard map with aggregate statistics
     */
    public Map<String, Object> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        long totalExecutions = agents.values().stream()
                .mapToLong(a -> a.executionCount.get()).sum();
        long totalItems = agents.values().stream()
                .mapToLong(a -> a.itemsProcessed.get()).sum();
        long totalErrors = agents.values().stream()
                .mapToLong(a -> a.failureCount.get()).sum();
        long runningCount = agents.values().stream()
                .filter(a -> a.getStatus() == AgentStatus.RUNNING).count();
        long errorCount = agents.values().stream()
                .filter(a -> a.getStatus() == AgentStatus.ERROR).count();
        long disabledCount = agents.values().stream()
                .filter(a -> a.getStatus() == AgentStatus.DISABLED).count();

        dashboard.put("totalAgents", agents.size());
        dashboard.put("agentsRunning", runningCount);
        dashboard.put("agentsInError", errorCount);
        dashboard.put("agentsDisabled", disabledCount);
        dashboard.put("agentsIdle", agents.size() - runningCount - errorCount - disabledCount);
        dashboard.put("totalExecutions", totalExecutions);
        dashboard.put("totalItemsProcessed", totalItems);
        dashboard.put("totalErrors", totalErrors);
        dashboard.put("agentsEnabled", agentsEnabled);
        dashboard.put("uptimeSeconds", Duration.between(startupTime, Instant.now()).getSeconds());
        dashboard.put("agents", getAgentStatuses());

        return dashboard;
    }

    // ── Shutdown ──────────────────────────────────────────────────────

    /**
     * Gracefully shuts down all agents and the scheduler thread pool.
     * Called automatically by Spring on context close.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AgentManager — disabling {} agents", agents.size());

        // Cancel all scheduled tasks
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();

        // Disable all agents
        agents.values().forEach(BackgroundAgent::disable);

        // Shutdown executor
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate gracefully — forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("AgentManager shutdown complete");
    }

    // ── Internal Helpers ──────────────────────────────────────────────

    private BackgroundAgent requireAgent(String agentId) {
        BackgroundAgent agent = agents.get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("No agent registered with id: " + agentId);
        }
        return agent;
    }

    /**
     * Returns the count of registered agents. Primarily for testing.
     *
     * @return number of registered agents
     */
    public int getAgentCount() {
        return agents.size();
    }
}
