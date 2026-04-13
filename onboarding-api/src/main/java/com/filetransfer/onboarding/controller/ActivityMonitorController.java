package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.response.ActivityMonitorEntry;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.criteria.Join;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/activity-monitor")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
@Tag(name = "Activity Monitor", description = "Paginated view of all file transfers")
public class ActivityMonitorController {

    private static final Set<String> VALID_SORT_COLUMNS = Set.of(
            "uploadedAt", "routedAt", "downloadedAt", "completedAt",
            "originalFilename", "status", "fileSizeBytes", "retryCount", "trackId"
    );

    private final FileTransferRecordRepository transferRepo;
    private final FlowExecutionRepository flowExecRepo;
    private final PartnerRepository partnerRepo;
    private final com.filetransfer.shared.repository.TransferAccountRepository accountRepository;

    /** Optional — null when shared-fabric not configured. Used for activity enrichment. */
    @Autowired(required = false)
    @Nullable
    private FabricCheckpointRepository fabricCheckpointRepo;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Search and list file transfers with optional filters")
    public Page<ActivityMonitorEntry> search(
            @RequestParam(required = false) String trackId,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) FileTransferStatus status,
            @RequestParam(required = false) String sourceUsername,
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "uploadedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {

        // Cap page size at 100
        if (size > 100) size = 100;

        // Validate sort column
        if (!VALID_SORT_COLUMNS.contains(sortBy)) {
            sortBy = "uploadedAt";
        }

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortBy));

        // Load partner map once: partnerId -> displayName (or companyName)
        Map<UUID, String> partnerMap = partnerRepo.findAll().stream()
                .collect(Collectors.toMap(
                        Partner::getId,
                        p -> p.getDisplayName() != null ? p.getDisplayName() : p.getCompanyName(),
                        (a, b) -> a
                ));

        // Build a dynamic Specification. Every filter is optional — we only
        // add a predicate when the caller supplied a non-null value, which
        // means a default page load (all filters null) produces an unfiltered
        // findAll() with zero parameter bindings. This side-steps BUG-1 where
        // Hibernate 6's null-param binding as Types.NULL broke PostgreSQL's
        // type inference on `:trackId IS NULL OR r.trackId = :trackId`.
        final String trackIdF = trackId;
        final String filenameF = filename;
        final FileTransferStatus statusF = status;
        final String sourceUsernameF = sourceUsername;
        final Protocol protocolF = protocol;
        Specification<FileTransferRecord> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (trackIdF != null && !trackIdF.isBlank()) {
                predicates.add(cb.equal(root.get("trackId"), trackIdF));
            }
            if (filenameF != null && !filenameF.isBlank()) {
                predicates.add(cb.like(
                        cb.lower(root.get("originalFilename")),
                        "%" + filenameF.toLowerCase() + "%"));
            }
            if (statusF != null) {
                predicates.add(cb.equal(root.get("status"), statusF));
            }
            if (sourceUsernameF != null && !sourceUsernameF.isBlank()) {
                Join<FileTransferRecord, FolderMapping> fmJoin = root.join("folderMapping", jakarta.persistence.criteria.JoinType.LEFT);
                Join<FolderMapping, TransferAccount> saJoin = fmJoin.join("sourceAccount", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.equal(saJoin.get("username"), sourceUsernameF));
            }
            if (protocolF != null) {
                Join<FileTransferRecord, FolderMapping> fmJoin = root.join("folderMapping", jakarta.persistence.criteria.JoinType.LEFT);
                Join<FolderMapping, TransferAccount> saJoin = fmJoin.join("sourceAccount", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.equal(saJoin.get("protocol"), protocolF));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        // Execute paginated query via Specification (no null-param bindings)
        Page<FileTransferRecord> records = transferRepo.findAll(spec, pageRequest);

        // Batch-fetch flow executions for trackIds in this page
        List<String> trackIds = records.getContent().stream()
                .map(FileTransferRecord::getTrackId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<String, FlowExecution> flowMap = trackIds.isEmpty()
                ? Collections.emptyMap()
                : flowExecRepo.findByTrackIdIn(trackIds).stream()
                        .collect(Collectors.toMap(FlowExecution::getTrackId, Function.identity(), (a, b) -> a));

        // Batch-fetch fabric checkpoints (latest per trackId). Only when fabric is wired.
        final Map<String, FabricCheckpoint> latestCpByTrackId = new HashMap<>();
        if (fabricCheckpointRepo != null && !trackIds.isEmpty()) {
            try {
                List<FabricCheckpoint> all = fabricCheckpointRepo.findLatestByTrackIds(trackIds);
                for (FabricCheckpoint cp : all) {
                    latestCpByTrackId.merge(cp.getTrackId(), cp,
                            (a, b) -> a.getStepIndex() >= b.getStepIndex() ? a : b);
                }
            } catch (Exception ignore) {
                // Fabric enrichment is best-effort — never break the activity monitor
            }
        }

        // Map to DTOs
        return records.map(r -> toEntry(r, partnerMap, flowMap, latestCpByTrackId));
    }

    private ActivityMonitorEntry toEntry(FileTransferRecord r,
                                         Map<UUID, String> partnerMap,
                                         Map<String, FlowExecution> flowMap,
                                         Map<String, FabricCheckpoint> latestCpByTrackId) {
        FolderMapping fm = r.getFolderMapping();
        TransferAccount src = fm != null ? fm.getSourceAccount() : null;
        TransferAccount dest = fm != null ? fm.getDestinationAccount() : null;
        ExternalDestination extDest = fm != null ? fm.getExternalDestination() : null;

        // VIRTUAL-mode records: resolve source account directly (no FolderMapping)
        if (src == null && r.getSourceAccountId() != null) {
            src = accountRepository.findById(r.getSourceAccountId()).orElse(null);
        }

        // Integrity check
        String integrityStatus = "PENDING";
        if (r.getSourceChecksum() != null && r.getDestinationChecksum() != null) {
            integrityStatus = r.getSourceChecksum().equals(r.getDestinationChecksum()) ? "VERIFIED" : "MISMATCH";
        }

        // Flow enrichment
        FlowExecution flowExec = flowMap.get(r.getTrackId());

        // Fabric enrichment (optional — null when fabric off or no checkpoint yet)
        FabricCheckpoint latestCp = latestCpByTrackId != null ? latestCpByTrackId.get(r.getTrackId()) : null;
        Integer fabricCurrentStep = null;
        String fabricCurrentStepType = null;
        String fabricProcessingInstance = null;
        Long fabricLeaseRemainingMs = null;
        Boolean fabricIsStuck = null;
        String fabricStatus = null;
        if (latestCp != null) {
            fabricCurrentStep = latestCp.getStepIndex();
            fabricCurrentStepType = latestCp.getStepType();
            fabricProcessingInstance = latestCp.getProcessingInstance();
            fabricStatus = latestCp.getStatus();
            if (latestCp.getLeaseExpiresAt() != null && "IN_PROGRESS".equals(latestCp.getStatus())) {
                long remain = Duration.between(Instant.now(), latestCp.getLeaseExpiresAt()).toMillis();
                fabricLeaseRemainingMs = remain;
                fabricIsStuck = remain < 0;
            }
        }

        return ActivityMonitorEntry.builder()
                .trackId(r.getTrackId())
                .filename(r.getOriginalFilename())
                .status(r.getStatus().name())
                .fileSizeBytes(r.getFileSizeBytes())
                // Source
                .sourceUsername(src != null ? src.getUsername() : null)
                .sourceProtocol(src != null ? src.getProtocol().name() : null)
                .sourcePartnerName(src != null && src.getPartnerId() != null ? partnerMap.get(src.getPartnerId()) : null)
                .sourcePath(r.getSourceFilePath())
                // Destination
                .destUsername(dest != null ? dest.getUsername() : null)
                .destProtocol(dest != null ? dest.getProtocol().name() : null)
                .destPartnerName(dest != null && dest.getPartnerId() != null ? partnerMap.get(dest.getPartnerId()) : null)
                .destPath(r.getDestinationFilePath())
                // External destination
                .externalDestName(extDest != null ? extDest.getName() : null)
                // Checksums
                .sourceChecksum(r.getSourceChecksum())
                .destinationChecksum(r.getDestinationChecksum())
                .integrityStatus(integrityStatus)
                // Encryption
                .encryptionOption(fm != null && fm.getEncryptionOption() != null ? fm.getEncryptionOption().name() : null)
                // Flow
                .flowName(flowExec != null && flowExec.getFlow() != null ? flowExec.getFlow().getName() : null)
                .flowStatus(flowExec != null ? flowExec.getStatus().name() : null)
                // Timestamps
                .uploadedAt(r.getUploadedAt())
                .routedAt(r.getRoutedAt())
                .downloadedAt(r.getDownloadedAt())
                .completedAt(r.getCompletedAt())
                // Error & retry
                .retryCount(r.getRetryCount())
                .errorMessage(r.getErrorMessage())
                // Fabric enrichment
                .currentStep(fabricCurrentStep)
                .currentStepType(fabricCurrentStepType)
                .processingInstance(fabricProcessingInstance)
                .leaseRemainingMs(fabricLeaseRemainingMs)
                .isStuck(fabricIsStuck)
                .fabricStatus(fabricStatus)
                .build();
    }
}
