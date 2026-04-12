package com.filetransfer.ai.service.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Spring configuration that wires all {@link BackgroundAgent} implementations
 * into the {@link AgentManager} with their respective execution intervals.
 *
 * <p>Agent registration is conditional on the {@code ai.agents.enabled}
 * property (defaults to {@code true}). When disabled, agents are still created
 * as Spring beans but are not scheduled for execution.
 *
 * <h3>Registered Agents and Intervals</h3>
 * <table>
 *   <tr><th>Agent</th><th>Interval</th><th>Priority</th></tr>
 *   <tr><td>OSINT Collector</td><td>15 minutes</td><td>HIGH</td></tr>
 *   <tr><td>CVE Monitor</td><td>1 hour</td><td>MEDIUM</td></tr>
 *   <tr><td>Threat Correlation</td><td>2 minutes</td><td>CRITICAL</td></tr>
 *   <tr><td>Reputation Decay</td><td>5 minutes</td><td>LOW</td></tr>
 * </table>
 */
@Configuration
@ConditionalOnProperty(name = "ai.agents.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class AgentRegistrar {

    private static final long MINUTES_MS = 60L * 1000L;

    @Autowired
    private AgentManager agentManager;

    @Autowired
    private OsintCollectorAgent osintAgent;

    @Autowired
    private CveMonitorAgent cveAgent;

    @Autowired
    private ThreatCorrelationAgent correlationAgent;

    @Autowired
    private ReputationDecayAgent decayAgent;

    /**
     * Registers all background agents with the agent manager on application startup.
     * Agents are staggered by priority to avoid thundering-herd effects.
     */
    @org.springframework.scheduling.annotation.Async
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void registerAllAgents() {
        log.info("Initializing AI security agent framework...");

        // CRITICAL priority — runs most frequently for real-time correlation
        agentManager.registerAgent(correlationAgent, 2 * MINUTES_MS);

        // HIGH priority — threat intel collection every 15 minutes
        agentManager.registerAgent(osintAgent, 15 * MINUTES_MS);

        // MEDIUM priority — CVE monitoring every hour
        agentManager.registerAgent(cveAgent, 60 * MINUTES_MS);

        // LOW priority — housekeeping every 5 minutes
        agentManager.registerAgent(decayAgent, 5 * MINUTES_MS);

        log.info("Registered {} AI security agents: [{}]", agentManager.getAgentCount(),
                String.join(", ",
                        correlationAgent.getAgentName(),
                        osintAgent.getAgentName(),
                        cveAgent.getAgentName(),
                        decayAgent.getAgentName()));
    }
}
