package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class FileFlowController {

    private final FileFlowRepository flowRepository;
    private final FlowExecutionRepository executionRepository;

    // --- Flow CRUD ---

    @GetMapping
    public List<FileFlow> getAllFlows() {
        return flowRepository.findByActiveTrueOrderByPriorityAsc();
    }

    @GetMapping("/{id}")
    public FileFlow getFlow(@PathVariable UUID id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
    }

    @PostMapping
    public ResponseEntity<FileFlow> createFlow(@Valid @RequestBody FileFlow flow) {
        if (flowRepository.existsByName(flow.getName())) {
            throw new IllegalArgumentException("Flow name already exists: " + flow.getName());
        }
        flow.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(flowRepository.save(flow));
    }

    @PutMapping("/{id}")
    public FileFlow updateFlow(@PathVariable UUID id, @RequestBody FileFlow flow) {
        if (!flowRepository.existsById(id))
            throw new EntityNotFoundException("Flow not found: " + id);
        flow.setId(id);
        return flowRepository.save(flow);
    }

    @PatchMapping("/{id}/toggle")
    public FileFlow toggleFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        flow.setActive(!flow.isActive());
        return flowRepository.save(flow);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlow(@PathVariable UUID id) {
        FileFlow flow = flowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Flow not found: " + id));
        flow.setActive(false);
        flowRepository.save(flow);
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
        return types;
    }
}
