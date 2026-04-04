package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.CreateFolderMappingRequest;
import com.filetransfer.onboarding.dto.response.FolderMappingResponse;
import com.filetransfer.onboarding.service.FolderMappingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Folder Mapping API
 *
 * POST   /api/folder-mappings                        — create a new mapping
 * GET    /api/folder-mappings/{id}                   — get one mapping
 * GET    /api/folder-mappings?accountId={id}          — list mappings for an account
 * PATCH  /api/folder-mappings/{id}/active?value=true  — enable / disable
 * DELETE /api/folder-mappings/{id}                   — delete
 */
@RestController
@RequestMapping("/api/folder-mappings")
@RequiredArgsConstructor
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
    public List<FolderMappingResponse> listForAccount(@RequestParam UUID accountId) {
        return folderMappingService.listForAccount(accountId);
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
