package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.integration.ScheduledTask;
import com.filetransfer.shared.repository.integration.ScheduledTaskRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController @RequestMapping("/api/scheduler") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class SchedulerController {
    private final ScheduledTaskRepository repo;

    @GetMapping public List<ScheduledTask> list() { return repo.findByEnabledTrueOrderByNextRunAsc(); }
    @GetMapping("/all") public List<ScheduledTask> listAll() { return repo.findAll(); }
    @GetMapping("/{id}") public ScheduledTask get(@PathVariable UUID id) { return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Not found")); }

    @PostMapping public ResponseEntity<ScheduledTask> create(@Valid @RequestBody ScheduledTask task) {
        validateConfig(task);
        task.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(task));
    }

    @PutMapping("/{id}") public ScheduledTask update(@PathVariable UUID id, @Valid @RequestBody ScheduledTask task) {
        if (!repo.existsById(id)) throw new EntityNotFoundException("Not found");
        validateConfig(task);
        task.setId(id);
        return repo.save(task);
    }

    @PatchMapping("/{id}/toggle") public ScheduledTask toggle(@PathVariable UUID id) { ScheduledTask t = repo.findById(id).orElseThrow(); t.setEnabled(!t.isEnabled()); return repo.save(t); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { repo.deleteById(id); return ResponseEntity.noContent().build(); }

    /**
     * Catches the "scheduler bean misconfigured" class of failures at save time
     * instead of at run time (where they used to spam logs every cron tick).
     * Each taskType has required config keys.
     */
    private void validateConfig(ScheduledTask task) {
        Map<String, String> cfg = task.getConfig() != null ? task.getConfig() : Map.of();
        String type = task.getTaskType();
        if (type == null) return;
        switch (type) {
            case "EXECUTE_SCRIPT" -> {
                String cmd = cfg.get("command");
                if (cmd == null || cmd.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "EXECUTE_SCRIPT task '" + task.getName() + "' requires config.command " +
                                    "(e.g. 'check-pgp-expiry'). Without it the scheduler will fail at run time.");
                }
            }
            case "RUN_FLOW" -> {
                if (task.getReferenceId() == null || task.getReferenceId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "RUN_FLOW task requires referenceId (the flow UUID)");
                }
            }
            case "PUSH_FILES", "PULL_FILES" -> {
                if (task.getReferenceId() == null || task.getReferenceId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            type + " task requires referenceId (account UUID)");
                }
            }
            case "CLEANUP" -> {
                if (cfg.get("path") == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CLEANUP task requires config.path");
                }
            }
            default -> {}
        }
    }
}
