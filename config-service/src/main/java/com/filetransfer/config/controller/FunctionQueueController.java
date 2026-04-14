package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.transfer.FunctionQueue;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FunctionQueueRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Function Queue management API — configure per-step-type queues.
 *
 * <p>Admins can view, edit, add custom, and delete queues. Each queue controls
 * how a specific step type (SCREEN, ENCRYPT, CONVERT, etc.) behaves in the
 * flow pipeline: retry count, timeout, concurrency, backoff.
 *
 * <p>Works identically on distributed (Kafka topics) and on-premise (SEDA stages).
 * The configuration is read by the step pipeline workers at execution time.
 *
 * <p>Delete protection: built-in queues cannot be deleted. Custom queues can only
 * be deleted if no active flow references that function type.
 */
@Slf4j
@RestController
@RequestMapping("/api/function-queues")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
public class FunctionQueueController {

    private final FunctionQueueRepository queueRepo;
    private final FileFlowRepository flowRepo;

    /** List all function queues grouped by category */
    @GetMapping
    public List<FunctionQueue> list() {
        List<FunctionQueue> queues = queueRepo.findAll();
        // Enrich with active flow count (how many flows use this function)
        for (FunctionQueue q : queues) {
            long count = countFlowsUsingFunction(q.getFunctionType());
            q.setActiveFlowCount(count);
        }
        queues.sort(Comparator.comparing(FunctionQueue::getCategory)
                .thenComparing(FunctionQueue::getFunctionType));
        return queues;
    }

    /** Get single queue by ID */
    @GetMapping("/{id}")
    public FunctionQueue get(@PathVariable UUID id) {
        FunctionQueue q = queueRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Queue not found: " + id));
        q.setActiveFlowCount(countFlowsUsingFunction(q.getFunctionType()));
        return q;
    }

    /** Get default queue for a function type (used by pipeline workers) */
    @GetMapping("/by-type/{functionType}")
    public FunctionQueue getByType(@PathVariable String functionType) {
        return queueRepo.findByFunctionTypeAndDefaultQueueTrue(functionType.toUpperCase())
                .orElseThrow(() -> new EntityNotFoundException("No default queue for type: " + functionType));
    }

    /** Get all queues for a specific function type (multiple profiles) */
    @GetMapping("/for-type/{functionType}")
    public List<FunctionQueue> listByType(@PathVariable String functionType) {
        return queueRepo.findByFunctionType(functionType.toUpperCase());
    }

    /** Create a queue profile — can be a new function type or an additional profile for an existing type */
    @PostMapping
    public ResponseEntity<FunctionQueue> create(@Valid @RequestBody FunctionQueue queue) {
        if (queueRepo.existsByDisplayName(queue.getDisplayName())) {
            throw new IllegalArgumentException("Queue name already exists: " + queue.getDisplayName());
        }
        queue.setId(null);
        queue.setFunctionType(queue.getFunctionType().toUpperCase());
        // Custom topic name: flow.step.{TYPE}.{sanitized-name} for non-default profiles
        String baseTopic = "flow.step." + queue.getFunctionType();
        if (queueRepo.existsByFunctionType(queue.getFunctionType().toUpperCase())) {
            // Additional profile — give it a unique topic
            String suffix = queue.getDisplayName().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
            queue.setTopicName(baseTopic + "." + suffix);
            queue.setDefaultQueue(false); // existing type already has a default
        } else {
            queue.setTopicName(baseTopic);
            queue.setDefaultQueue(true); // first queue for this type = default
        }
        queue.setBuiltIn(false);
        FunctionQueue saved = queueRepo.save(queue);
        log.info("Function queue created: '{}' (type={}, topic={}, default={})",
                saved.getDisplayName(), saved.getFunctionType(), saved.getTopicName(), saved.isDefaultQueue());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Update queue configuration (retry, timeout, concurrency, etc.) */
    @PutMapping("/{id}")
    public FunctionQueue update(@PathVariable UUID id, @RequestBody FunctionQueue updates) {
        FunctionQueue existing = queueRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Queue not found: " + id));

        // Allow updating operational params, not identity
        if (updates.getRetryCount() >= 0) existing.setRetryCount(updates.getRetryCount());
        if (updates.getRetryBackoffMs() > 0) existing.setRetryBackoffMs(updates.getRetryBackoffMs());
        if (updates.getTimeoutSeconds() > 0) existing.setTimeoutSeconds(updates.getTimeoutSeconds());
        if (updates.getMinConcurrency() > 0) existing.setMinConcurrency(updates.getMinConcurrency());
        if (updates.getMaxConcurrency() > 0) existing.setMaxConcurrency(updates.getMaxConcurrency());
        if (updates.getMessageTtlMs() > 0) existing.setMessageTtlMs(updates.getMessageTtlMs());
        if (updates.getDisplayName() != null) existing.setDisplayName(updates.getDisplayName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getCustomConfig() != null) existing.setCustomConfig(updates.getCustomConfig());

        FunctionQueue saved = queueRepo.save(existing);
        log.info("Function queue updated: {} (retry={}, timeout={}s, concurrency={}-{})",
                saved.getFunctionType(), saved.getRetryCount(), saved.getTimeoutSeconds(),
                saved.getMinConcurrency(), saved.getMaxConcurrency());
        return saved;
    }

    /** Enable/disable a queue */
    @PatchMapping("/{id}/toggle")
    public FunctionQueue toggle(@PathVariable UUID id) {
        FunctionQueue q = queueRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Queue not found: " + id));
        q.setEnabled(!q.isEnabled());
        FunctionQueue saved = queueRepo.save(q);
        log.info("Function queue {}: {}", saved.isEnabled() ? "enabled" : "disabled", saved.getFunctionType());
        return saved;
    }

    /**
     * Delete a custom queue — only if:
     * 1. It's not a built-in queue
     * 2. No active flow references this function type
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) {
        FunctionQueue q = queueRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Queue not found: " + id));

        if (q.isBuiltIn()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Cannot delete built-in queue: " + q.getFunctionType()));
        }

        long flowCount = countFlowsUsingFunction(q.getFunctionType());
        if (flowCount > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", q.getFunctionType() + " is used by " + flowCount
                            + " active flow(s). Remove it from all flows before deleting."));
        }

        queueRepo.delete(q);
        log.info("Custom function queue deleted: {}", q.getFunctionType());
        return ResponseEntity.ok(Map.of("status", "deleted", "functionType", q.getFunctionType()));
    }

    /** Count active flows that use a specific function type in their steps */
    private long countFlowsUsingFunction(String functionType) {
        // Query all active flows and check step types
        // This is a read-through count — not cached, always fresh
        return flowRepo.findByActiveTrueOrderByPriorityAsc().stream()
                .filter(f -> f.getSteps() != null && f.getSteps().stream()
                        .anyMatch(s -> functionType.equalsIgnoreCase(s.getType())))
                .count();
    }
}
