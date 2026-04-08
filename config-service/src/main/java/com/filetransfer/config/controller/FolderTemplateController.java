package com.filetransfer.config.controller;

import com.filetransfer.config.service.FolderTemplateService;
import com.filetransfer.shared.entity.FolderTemplate;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/folder-templates")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class FolderTemplateController {

    private final FolderTemplateService service;

    @GetMapping
    public List<FolderTemplate> list() {
        return service.listActive();
    }

    @GetMapping("/{id}")
    public FolderTemplate get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderTemplate create(@Valid @RequestBody FolderTemplate template) {
        return service.create(template);
    }

    @PutMapping("/{id}")
    public FolderTemplate update(@PathVariable UUID id, @Valid @RequestBody FolderTemplate template) {
        return service.update(id, template);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/{id}/export")
    public FolderTemplate exportOne(@PathVariable UUID id) {
        return service.exportOne(id);
    }

    @GetMapping("/export")
    public List<FolderTemplate> exportAll() {
        return service.exportAll();
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public List<FolderTemplate> importTemplates(@RequestBody List<FolderTemplate> templates) {
        return service.importTemplates(templates);
    }
}
