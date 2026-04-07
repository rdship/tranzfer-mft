package com.filetransfer.config.service;

import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.FolderTemplate;
import com.filetransfer.shared.repository.FolderTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FolderTemplateService {

    private final FolderTemplateRepository repository;

    public List<FolderTemplate> listActive() {
        return repository.findByActiveTrue();
    }

    public FolderTemplate get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id));
    }

    public FolderTemplate getDefault() {
        return repository.findByName("Standard")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Standard template missing"));
    }

    @Transactional
    public FolderTemplate create(FolderTemplate template) {
        validateFolders(template.getFolders());
        if (repository.existsByName(template.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Template name already exists: " + template.getName());
        }
        template.setId(null);
        template.setBuiltIn(false);
        log.info("Created folder template: {}", template.getName());
        return repository.save(template);
    }

    @Transactional
    public FolderTemplate update(UUID id, FolderTemplate template) {
        FolderTemplate existing = get(id);
        if (existing.isBuiltIn()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot modify built-in template: " + existing.getName());
        }
        validateFolders(template.getFolders());
        // Check name uniqueness if changed
        if (!existing.getName().equals(template.getName()) && repository.existsByName(template.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Template name already exists: " + template.getName());
        }
        existing.setName(template.getName());
        existing.setDescription(template.getDescription());
        existing.setFolders(template.getFolders());
        existing.setActive(template.isActive());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        FolderTemplate existing = get(id);
        if (existing.isBuiltIn()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete built-in template: " + existing.getName());
        }
        repository.delete(existing);
        log.info("Deleted folder template: {}", existing.getName());
    }

    public FolderTemplate exportOne(UUID id) {
        return get(id);
    }

    public List<FolderTemplate> exportAll() {
        return repository.findByActiveTrue();
    }

    @Transactional
    public List<FolderTemplate> importTemplates(List<FolderTemplate> templates) {
        List<FolderTemplate> imported = new ArrayList<>();
        for (FolderTemplate t : templates) {
            validateFolders(t.getFolders());
            if (repository.existsByName(t.getName())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Template name already exists: " + t.getName() + ". Rename before importing.");
            }
            t.setId(null);
            t.setBuiltIn(false);
            imported.add(repository.save(t));
        }
        log.info("Imported {} folder templates", imported.size());
        return imported;
    }

    // --- Validation ---

    private void validateFolders(List<FolderDefinition> folders) {
        if (folders == null || folders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template must have at least one folder");
        }
        Set<String> seen = new HashSet<>();
        for (FolderDefinition f : folders) {
            if (f.getPath() == null || f.getPath().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder path cannot be empty");
            }
            String normalized = f.getPath().replace("\\", "/").replaceAll("/+", "/");
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (normalized.contains("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder path cannot contain '..': " + f.getPath());
            }
            if (!seen.add(normalized.toLowerCase())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate folder path: " + f.getPath());
            }
            f.setPath(normalized);
        }
    }
}
