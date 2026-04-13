package com.filetransfer.shared.matching;

import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.repository.FileFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads all active flow rules into the in-memory registry at application startup.
 * Only activates in services that set {@code flow.rules.enabled=true}
 * (SFTP, FTP, FTP-Web, Gateway, AS2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class FlowRuleRegistryInitializer {

    private final FileFlowRepository flowRepository;
    private final FlowRuleRegistry registry;
    private final FlowRuleCompiler compiler;

    /**
     * Phase 1: In-memory flow cache — populated alongside rule registry every 30s.
     * RoutingEngine calls {@link #getFlow(UUID)} instead of flowRepository.findById().
     * Eliminates one DB round-trip per matched file.
     */
    private final ConcurrentHashMap<UUID, FileFlow> flowCache = new ConcurrentHashMap<>();

    /** Get a cached FileFlow by ID. Returns null if not in cache (caller should fall back to DB). */
    public FileFlow getFlow(UUID flowId) {
        return flowId != null ? flowCache.get(flowId) : null;
    }

    /** Hot-reload: add/update a single flow in the cache (called by FlowRuleEventListener). */
    public void cacheFlow(FileFlow flow) {
        if (flow != null && flow.getId() != null) {
            flowCache.put(flow.getId(), flow);
        }
    }

    /** Hot-reload: remove a flow from the cache (called by FlowRuleEventListener on delete/deactivate). */
    public void uncacheFlow(UUID flowId) {
        if (flowId != null) {
            flowCache.remove(flowId);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            List<FileFlow> activeFlows = flowRepository.findByActiveTrueOrderByPriorityAsc();
            Map<UUID, CompiledFlowRule> compiled = new LinkedHashMap<>();
            for (FileFlow flow : activeFlows) {
                try {
                    compiled.put(flow.getId(), compiler.compile(flow));
                } catch (Exception e) {
                    log.error("Failed to compile flow: {} (id={})", flow.getName(), flow.getId(), e);
                }
            }
            registry.loadAll(compiled);
            // Phase 1: populate flow cache alongside rule registry
            flowCache.clear();
            activeFlows.forEach(f -> flowCache.put(f.getId(), f));
            log.info("Flow rule registry initialized with {} active flows", registry.size());
        } catch (Exception e) {
            log.warn("Flow rule registry initialization skipped: {}", e.getMessage());
        }
    }

    /** Phase 3.4: track last refresh count to skip no-op reloads. */
    private volatile int lastRefreshCount = -1;

    /**
     * Phase 3.4: Periodic refresh — reloads active flows every 5s (was 30s).
     * Skips reload if flow count hasn't changed (cheap COUNT query).
     * Full reload only when count differs or on first call.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    public void refresh() {
        try {
            // Quick count check — skip full reload if count unchanged
            long currentCount = flowRepository.countByActiveTrue();
            if (currentCount == lastRefreshCount && lastRefreshCount >= 0) {
                return; // No change — skip the expensive findAll + compile cycle
            }

            List<FileFlow> activeFlows = flowRepository.findByActiveTrueOrderByPriorityAsc();
            Map<UUID, CompiledFlowRule> compiled = new LinkedHashMap<>();
            for (FileFlow flow : activeFlows) {
                try {
                    compiled.put(flow.getId(), compiler.compile(flow));
                } catch (Exception e) {
                    log.debug("Skipping flow {} during refresh: {}", flow.getName(), e.getMessage());
                }
            }
            int before = registry.size();
            registry.loadAll(compiled);
            flowCache.clear();
            activeFlows.forEach(f -> flowCache.put(f.getId(), f));
            lastRefreshCount = compiled.size();
            if (compiled.size() != before) {
                log.info("Flow rule registry refreshed: {} → {} active flows", before, compiled.size());
            }
        } catch (Exception e) {
            log.debug("Flow rule registry refresh failed: {}", e.getMessage());
        }
    }
}
