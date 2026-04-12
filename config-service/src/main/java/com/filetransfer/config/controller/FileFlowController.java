package com.filetransfer.config.controller;

import com.filetransfer.config.messaging.FlowRuleEventPublisher;
import com.filetransfer.config.service.MatchCriteriaService;
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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
    public List<FileFlow> getAllFlows() {
        return flowRepository.findByActiveTrueOrderByPriorityAsc();
    }

    @GetMapping("/{id}")
    public FileFlow getFlow(@PathVariable UUID id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
    }

    @PostMapping
    @CacheEvict(value = "flows", allEntries = true)
    public ResponseEntity<FileFlow> createFlow(@Valid @RequestBody FileFlow flow) {
        flow.setName(sanitizeName(flow.getName()));
        if (flowRepository.existsByName(flow.getName())) {
            throw new IllegalArgumentException("Flow name already exists: " + flow.getName());
        }
        flow.setId(null);
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishCreated(saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    @CacheEvict(value = "flows", allEntries = true)
    public FileFlow updateFlow(@PathVariable UUID id, @Valid @RequestBody FileFlow flow) {
        if (!flowRepository.existsById(id))
            throw new EntityNotFoundException("Flow not found: " + id);
        flow.setName(sanitizeName(flow.getName()));
        flow.setId(id);
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishUpdated(saved.getId());
        return saved;
    }

    @PatchMapping("/{id}/toggle")
    @CacheEvict(value = "flows", allEntries = true)
    public FileFlow toggleFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        flow.setActive(!flow.isActive());
        FileFlow saved = flowRepository.save(flow);
        flowRuleEventPublisher.publishUpdated(saved.getId());
        return saved;
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

    // --- Flow Executions / Tracking ---

    @GetMapping("/executions")
    public Page<FlowExecution> searchExecutions(
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) FlowExecution.FlowStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return executionRepository.search(trackId, filename, status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")));
    }

    @GetMapping("/executions/{trackId}")
    public FlowExecution getExecution(@PathVariable String trackId) {
        return executionRepository.findByTrackId(trackId)
                .orElseThrow(() -> new EntityNotFoundException("Execution not found: " + trackId));
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
