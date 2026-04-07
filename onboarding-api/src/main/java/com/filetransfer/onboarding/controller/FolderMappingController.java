package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateFolderMappingRequest;
import com.filetransfer.onboarding.dto.response.FolderMappingResponse;
import com.filetransfer.onboarding.service.FolderMappingService;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Folder Mapping API
 *
 * POST   /api/folder-mappings                        — create a new mapping
 * GET    /api/folder-mappings                        — list all (or by accountId)
 * GET    /api/folder-mappings/{id}                   — get one mapping
 * PUT    /api/folder-mappings/{id}                   — update a mapping
 * PATCH  /api/folder-mappings/{id}/active?value=true  — enable / disable
 * DELETE /api/folder-mappings/{id}                   — delete
 */
@RestController
@RequestMapping("/api/folder-mappings")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class FolderMappingController {

    private final FolderMappingService folderMappingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderMappingResponse create(@Valid @RequestBody CreateFolderMappingRequest request) {
        return folderMappingService.create(request);
    }

    @GetMapping("/{id}")
    public FolderMappingResponse get(@PathVariable UUID id) {
        return folderMappingService.get(id);
    }

    @GetMapping
    public List<FolderMappingResponse> list(@RequestParam(required = false) UUID accountId) {
        if (accountId != null) {
            return folderMappingService.listForAccount(accountId);
        }
        return folderMappingService.listAll();
    }

    @PutMapping("/{id}")
    public FolderMappingResponse update(@PathVariable UUID id,
                                         @Valid @RequestBody CreateFolderMappingRequest request) {
        return folderMappingService.update(id, request);
    }

    @PatchMapping("/{id}/active")
    public FolderMappingResponse setActive(@PathVariable UUID id, @RequestParam boolean value) {
        return folderMappingService.setActive(id, value);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        folderMappingService.delete(id);
    }
}
