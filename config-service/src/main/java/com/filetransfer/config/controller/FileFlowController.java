package com.filetransfer.config.controller;

import com.filetransfer.config.dto.QuickFlowRequest;
import com.filetransfer.config.messaging.FlowRuleEventPublisher;
import com.filetransfer.config.service.MatchCriteriaService;
import com.filetransfer.shared.dto.FileFlowDto;
import com.filetransfer.shared.dto.FlowExecutionDto;
import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.flow.FlowFunctionRegistry;
import com.filetransfer.shared.flow.FunctionDescriptor;
import com.filetransfer.shared.flow.FunctionImportExportService;
import com.filetransfer.shared.flow.FunctionPackage;
import com.filetransfer.shared.matching.MatchCriteria;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class FileFlowController {

    private final FileFlowRepository flowRepository;
    private final FlowExecutionRepository executionRepository;
    private final MatchCriteriaService matchCriteriaService;
    private final FlowRuleEventPublisher flowRuleEventPublisher;
    private final FlowFunctionRegistry flowFunctionRegistry;
    private final FunctionImportExportService functionImportExportService;

    // --- Flow CRUD ---

    @GetMapping
    @Cacheable(value = "flows", unless = "#result.isEmpty()")
    @Transactional(readOnly = true)
    public List<FileFlowDto> getAllFlows() {
        List<FileFlow> flows = flowRepository.findByActiveTrueOrderByPriorityAsc();
        return flows.stream().map(FileFlowDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public FileFlowDto getFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        return FileFlowDto.from(flow);
    }

    @PostMapping
    @CacheEvict(value = "flows", allEntries = true)
    @Transactional
    public ResponseEntity<FileFlowDto> createFlow(@Valid @RequestBody FileFlow flow) {
        flow.setName(sanitizeName(flow.getName()));
        if (flowRepository.existsByName(flow.getName())) {
            throw new IllegalArgumentException("Flow name already exists: " + flow.getName());
        }
        flow.setId(null);
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishCreated(saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(FileFlowDto.from(saved));
    }

    @PutMapping("/{id}")
    @CacheEvict(value = "flows", allEntries = true)
    @Transactional
    public FileFlowDto updateFlow(@PathVariable UUID id, @Valid @RequestBody FileFlow flow) {
        if (!flowRepository.existsById(id))
            throw new EntityNotFoundException("Flow not found: " + id);
        flow.setName(sanitizeName(flow.getName()));
        flow.setId(id);
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishUpdated(saved.getId());
        return FileFlowDto.from(saved);
    }

    @PatchMapping("/{id}/toggle")
    @CacheEvict(value = "flows", allEntries = true)
    @Transactional
    public FileFlowDto toggleFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        flow.setActive(!flow.isActive());
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishUpdated(saved.getId());
        return FileFlowDto.from(saved);
    }

    @DeleteMapping("/{id}")
    @CacheEvict(value = "flows", allEntries = true)
    public ResponseEntity<Void> deleteFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        flow.setActive(false);
        flowRepository.save(flow);
        flowRuleEventPublisher.publishDeleted(id);
        return ResponseEntity.noContent().build();
    }

    // --- Quick Flow (simplified creation — 30 seconds, not 30 minutes) ---

    /**
     * Creates a complete flow from a simplified 4-section request (When/Do/Deliver/Error).
     * Auto-generates flow name, steps with sensible defaults, and delivery config.
     * The admin fills in source + pattern + actions + destination. That's it.
     */
    @PostMapping("/quick")
    @CacheEvict(value = "flows", allEntries = true)
    @Transactional
    public ResponseEntity<FileFlowDto> createQuickFlow(@RequestBody QuickFlowRequest req) {
        // Auto-generate name if not provided
        String name = req.getName();
        if (name == null || name.isBlank()) {
            String src = req.getSource() != null ? req.getSource() : "any";
            String pat = req.getFilenamePattern() != null ? req.getFilenamePattern().replaceAll("[^a-zA-Z0-9]", "") : "all";
            name = src + "-" + pat + "-flow";
        }
        name = sanitizeName(name);
        if (flowRepository.existsByName(name)) {
            throw new IllegalArgumentException("Flow name already exists: " + name);
        }

        // Build steps from action list
        List<FileFlow.FlowStep> steps = new java.util.ArrayList<>();
        int order = 0;
        if (req.getActions() != null) {
            for (String action : req.getActions()) {
                java.util.Map<String, String> config = new java.util.LinkedHashMap<>();

                // Auto-fill step config based on type
                switch (action.toUpperCase()) {
                    case "SCREEN" -> config.put("onFailure", "PASS"); // graceful default
                    case "ENCRYPT_PGP", "ENCRYPT_AES" -> {
                        if (req.getEncryptionKeyAlias() != null) config.put("keyAlias", req.getEncryptionKeyAlias());
                    }
                    case "CONVERT_EDI" -> config.put("targetFormat", req.getEdiTargetFormat());
                    case "CHECKSUM_VERIFY" -> config.put("algorithm", "SHA-256");
                    case "MAILBOX" -> {
                        if (req.getDeliverTo() != null) config.put("destinationUsername", req.getDeliverTo());
                        if (req.getDeliveryPath() != null) config.put("destinationPath", req.getDeliveryPath());
                    }
                }
                steps.add(FileFlow.FlowStep.builder().type(action.toUpperCase()).config(config).order(order++).build());
            }
        }

        // If deliverTo is set and no MAILBOX/FILE_DELIVERY step exists, auto-add MAILBOX
        boolean hasDelivery = steps.stream().anyMatch(s ->
                "MAILBOX".equals(s.getType()) || "FILE_DELIVERY".equals(s.getType()));
        if (!hasDelivery && req.getDeliverTo() != null) {
            java.util.Map<String, String> mailboxConfig = new java.util.LinkedHashMap<>();
            mailboxConfig.put("destinationUsername", req.getDeliverTo());
            mailboxConfig.put("destinationPath", req.getDeliveryPath());
            steps.add(FileFlow.FlowStep.builder().type("MAILBOX").config(mailboxConfig).order(order).build());
        }

        // Ensure at least one step
        if (steps.isEmpty()) {
            steps.add(FileFlow.FlowStep.builder().type("ROUTE").config(java.util.Map.of()).order(0).build());
        }

        FileFlow flow = FileFlow.builder()
                .name(name)
                .description("Quick flow: " + (req.getSource() != null ? req.getSource() : "any source")
                        + " → " + (req.getDeliverTo() != null ? req.getDeliverTo() : "default"))
                .filenamePattern(req.getFilenamePattern())
                .direction(req.getDirection())
                .priority(req.getPriority())
                .steps(steps)
                .active(true)
                .build();

        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishCreated(saved.getId());

        log.info("Quick flow created: '{}' (id={}, {} steps, priority={})",
                saved.getName(), saved.getId(), saved.getSteps().size(), saved.getPriority());

        return ResponseEntity.status(HttpStatus.CREATED).body(FileFlowDto.from(saved));
    }

    // --- Flow Executions / Tracking ---

    @GetMapping("/executions")
    @Transactional(readOnly = true)
    public Page<FlowExecutionDto> searchExecutions(
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) FlowExecution.FlowStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return executionRepository.search(trackId, filename, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")))
                .map(FlowExecutionDto::from);
    }

    @GetMapping("/executions/{trackId}")
    @Transactional(readOnly = true)
    public FlowExecutionDto getExecution(@PathVariable String trackId) {
        FlowExecution exec = executionRepository.findByTrackId(trackId)
                .orElseThrow(() -> new EntityNotFoundException("Execution not found: " + trackId));
        return FlowExecutionDto.from(exec);
    }

    @GetMapping("/step-types")
    public Map<String, Object> getStepTypes() {
        Map<String, Object> types = new java.util.LinkedHashMap<>();
        types.put("encryption", List.of("ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES"));
        types.put("compression", List.of("COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP"));
        types.put("transform", List.of("RENAME"));
        types.put("security", List.of("SCREEN"));
        types.put("scripting", List.of("EXECUTE_SCRIPT"));
        types.put("delivery", List.of("MAILBOX", "FILE_DELIVERY"));
        types.put("routing", List.of("ROUTE"));
        types.put("conversion", List.of("CONVERT_EDI"));
        return types;
    }

    @GetMapping("/functions/catalog")
    public List<Map<String, Object>> functionCatalog() {
        return flowFunctionRegistry.getAll().entrySet().stream()
            .map(e -> Map.<String, Object>of(
                "type", e.getKey(),
                "ioMode", e.getValue().ioMode().name(),
                "description", e.getValue().description(),
                "configSchema", e.getValue().configSchema() != null ? e.getValue().configSchema() : ""
            ))
            .sorted(Comparator.comparing(m -> (String) m.get("type")))
            .toList();
    }

    // --- Function Import / Export ---

    /** Import a gRPC or WASM function by providing endpoint and metadata. */
    @PostMapping("/functions/import")
    public Map<String, String> importFunction(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String endpoint = body.get("endpoint");
        String runtime = body.getOrDefault("runtime", "GRPC");
        String description = body.getOrDefault("description", name);
        String category = body.getOrDefault("category", "TRANSFORM");

        FunctionDescriptor desc = new FunctionDescriptor(name, "1.0.0", category, "PARTNER", "external", true, description);
        FunctionPackage pkg = new FunctionPackage(desc, runtime, endpoint, null, body.get("configSchema"));
        functionImportExportService.importFunction(pkg);

        return Map.of("status", "IMPORTED", "type", name.toUpperCase().replace('-', '_'), "runtime", runtime);
    }

    /** Export function metadata. */
    @GetMapping("/functions/{type}/export")
    public FunctionPackage exportFunction(@PathVariable String type) {
        return functionImportExportService.exportFunction(type.toUpperCase());
    }

    // --- Match Criteria ---

    @GetMapping("/match-fields")
    public List<MatchCriteriaService.FieldInfo> getMatchFields() {
        return matchCriteriaService.getMatchFields();
    }

    @PostMapping("/validate-criteria")
    public MatchCriteriaService.ValidationResult validateCriteria(@RequestBody MatchCriteria criteria) {
        return matchCriteriaService.validate(criteria);
    }

    @PostMapping("/test-match")
    public MatchCriteriaService.TestMatchResult testMatch(@RequestBody TestMatchRequest request) {
        return matchCriteriaService.testMatch(request.criteria(), request.fileContext());
    }

    public record TestMatchRequest(MatchCriteria criteria, Map<String, Object> fileContext) {}

    private static String sanitizeName(String name) {
        if (name == null) return null;
        return name.replace("<", "").replace(">", "").trim();
    }
}
