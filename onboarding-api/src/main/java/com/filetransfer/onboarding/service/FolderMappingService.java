package com.filetransfer.onboarding.service;

import com.filetransfer.onboarding.dto.request.CreateFolderMappingRequest;
import com.filetransfer.onboarding.dto.response.FolderMappingResponse;
import com.filetransfer.shared.entity.FolderMapping;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.FolderMappingRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FolderMappingService {

    private final FolderMappingRepository mappingRepository;
    private final TransferAccountRepository accountRepository;

    @Transactional
    public FolderMappingResponse create(CreateFolderMappingRequest request) {
        TransferAccount source = findAccount(request.getSourceAccountId());
        TransferAccount dest = findAccount(request.getDestinationAccountId());

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(source)
                .sourcePath(normalizePath(request.getSourcePath()))
                .destinationAccount(dest)
                .destinationPath(normalizePath(request.getDestinationPath()))
                .filenamePattern(request.getFilenamePattern())
                .active(true)
                .build();

        return toResponse(mappingRepository.save(mapping));
    }

    @Transactional(readOnly = true)
    public FolderMappingResponse get(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public List<FolderMappingResponse> listAll() {
        return mappingRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FolderMappingResponse> listForAccount(UUID accountId) {
        return mappingRepository
                .findBySourceAccountIdOrDestinationAccountId(accountId, accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public FolderMappingResponse update(UUID id, CreateFolderMappingRequest request) {
        FolderMapping mapping = findById(id);
        mapping.setSourceAccount(findAccount(request.getSourceAccountId()));
        mapping.setSourcePath(normalizePath(request.getSourcePath()));
        mapping.setDestinationAccount(findAccount(request.getDestinationAccountId()));
        mapping.setDestinationPath(normalizePath(request.getDestinationPath()));
        mapping.setFilenamePattern(request.getFilenamePattern());
        return toResponse(mappingRepository.save(mapping));
    }

    @Transactional
    public FolderMappingResponse setActive(UUID id, boolean active) {
        FolderMapping mapping = findById(id);
        mapping.setActive(active);
        return toResponse(mappingRepository.save(mapping));
    }

    @Transactional
    public void delete(UUID id) {
        mappingRepository.deleteById(id);
    }

    private FolderMapping findById(UUID id) {
        return mappingRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Folder mapping not found: " + id));
    }

    private TransferAccount findAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transfer account not found: " + id));
    }

    private String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private FolderMappingResponse toResponse(FolderMapping m) {
        return FolderMappingResponse.builder()
                .id(m.getId())
                .sourceAccountId(m.getSourceAccount().getId())
                .sourceUsername(m.getSourceAccount().getUsername())
                .sourcePath(m.getSourcePath())
                .destinationAccountId(m.getDestinationAccount().getId())
                .destinationUsername(m.getDestinationAccount().getUsername())
                .destinationPath(m.getDestinationPath())
                .filenamePattern(m.getFilenamePattern())
                .active(m.isActive())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
