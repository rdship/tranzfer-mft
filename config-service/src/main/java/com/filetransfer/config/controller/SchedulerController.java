package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.integration.ScheduledTask;
import com.filetransfer.shared.repository.ScheduledTaskRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/scheduler") @RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class SchedulerController {
    private final ScheduledTaskRepository repo;

    @GetMapping public List<ScheduledTask> list() { return repo.findByEnabledTrueOrderByNextRunAsc(); }
    @GetMapping("/all") public List<ScheduledTask> listAll() { return repo.findAll(); }
    @GetMapping("/{id}") public ScheduledTask get(@PathVariable UUID id) { return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("Not found")); }
    @PostMapping public ResponseEntity<ScheduledTask> create(@Valid @RequestBody ScheduledTask task) { task.setId(null); return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(task)); }
    @PutMapping("/{id}") public ScheduledTask update(@PathVariable UUID id, @Valid @RequestBody ScheduledTask task) { if (!repo.existsById(id)) throw new EntityNotFoundException("Not found"); task.setId(id); return repo.save(task); }
    @PatchMapping("/{id}/toggle") public ScheduledTask toggle(@PathVariable UUID id) { ScheduledTask t = repo.findById(id).orElseThrow(); t.setEnabled(!t.isEnabled()); return repo.save(t); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { repo.deleteById(id); return ResponseEntity.noContent().build(); }
}
