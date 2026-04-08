package com.filetransfer.shared.matching;

import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.repository.FileFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            log.info("Flow rule registry initialized with {} active flows", registry.size());
        } catch (Exception e) {
            log.warn("Flow rule registry initialization skipped: {}", e.getMessage());
        }
    }
}
