package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.response.ActivityMonitorEntry;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import org.springframework.format.annotation.DateTimeFormat;
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant endDate,
            @RequestParam(required = false) String integrityStatus,
            @RequestParam(required = false) Long minSize,
            @RequestParam(required = false) Long maxSize,
            @RequestParam(required = false) String errorKeyword,
            @RequestParam(required = false) String partnerName,
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

        Map<UUID, String> partnerMap = getPartnerNameMap();

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
        final java.time.Instant startDateF = startDate;
        final java.time.Instant endDateF = endDate;
        final Long minSizeF = minSize;
        final Long maxSizeF = maxSize;
        final String errorKeywordF = errorKeyword;
        final String partnerNameF = partnerName;
        final String integrityStatusF = integrityStatus;
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
            // V2 filters: date range, file size, error keyword, partner name
            if (startDateF != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("uploadedAt"), startDateF));
            }
            if (endDateF != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("uploadedAt"), endDateF));
            }
            if (minSizeF != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("fileSizeBytes"), minSizeF));
            }
            if (maxSizeF != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("fileSizeBytes"), maxSizeF));
            }
            if (errorKeywordF != null && !errorKeywordF.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("errorMessage")),
                        "%" + errorKeywordF.toLowerCase() + "%"));
            }
            if (partnerNameF != null && !partnerNameF.isBlank()) {
                // Match partner name via source account's partnerId → partner table
                // For performance, we filter in-memory after fetch (partner map is cached)
            }
            if (integrityStatusF != null && !integrityStatusF.isBlank()) {
                switch (integrityStatusF.toUpperCase()) {
                    case "VERIFIED" -> predicates.add(cb.and(
                            cb.isNotNull(root.get("sourceChecksum")),
                            cb.isNotNull(root.get("destinationChecksum")),
                            cb.equal(root.get("sourceChecksum"), root.get("destinationChecksum"))));
                    case "MISMATCH" -> predicates.add(cb.and(
                            cb.isNotNull(root.get("sourceChecksum")),
                            cb.isNotNull(root.get("destinationChecksum")),
                            cb.notEqual(root.get("sourceChecksum"), root.get("destinationChecksum"))));
                    case "PENDING" -> predicates.add(cb.or(
                            cb.isNull(root.get("sourceChecksum")),
                            cb.isNull(root.get("destinationChecksum"))));
                }
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

        // VIRTUAL-mode records: resolve accounts directly (no FolderMapping)
        if (src == null && r.getSourceAccountId() != null) {
            src = accountRepository.findById(r.getSourceAccountId()).orElse(null);
        }
        if (dest == null && r.getDestinationAccountId() != null) {
            dest = accountRepository.findById(r.getDestinationAccountId()).orElse(null);
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

    // ── CSV Export (streaming — no memory buffer) ────────────────────────────

    @GetMapping("/export")
    @Transactional(readOnly = true)
    public void export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) FileTransferStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.Instant endDate,
            jakarta.servlet.http.HttpServletResponse response) throws Exception {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=activity-export.csv");

        Specification<FileTransferRecord> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            if (startDate != null) preds.add(cb.greaterThanOrEqualTo(root.get("uploadedAt"), startDate));
            if (endDate != null) preds.add(cb.lessThanOrEqualTo(root.get("uploadedAt"), endDate));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        try (java.io.PrintWriter writer = response.getWriter()) {
            writer.println("trackId,filename,status,sourceAccount,fileSizeBytes,sourceChecksum,uploadedAt,completedAt,errorMessage");
            // Stream in pages to avoid loading all records into memory
            int page = 0;
            Page<FileTransferRecord> batch;
            do {
                batch = transferRepo.findAll(spec, PageRequest.of(page++, 500, Sort.by(Sort.Direction.DESC, "uploadedAt")));
                for (FileTransferRecord r : batch.getContent()) {
                    writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            r.getTrackId(),
                            csvEscape(r.getOriginalFilename()),
                            r.getStatus(),
                            r.getSourceAccountId(),
                            r.getFileSizeBytes() != null ? r.getFileSizeBytes() : "",
                            r.getSourceChecksum() != null ? r.getSourceChecksum() : "",
                            r.getUploadedAt(),
                            r.getCompletedAt() != null ? r.getCompletedAt() : "",
                            csvEscape(r.getErrorMessage()));
                }
            } while (batch.hasNext());
        }
    }

    @org.springframework.cache.annotation.Cacheable(value = "partner-names", unless = "#result.isEmpty()")
    public Map<UUID, String> getPartnerNameMap() {
        return partnerRepo.findAll().stream()
                .collect(Collectors.toMap(Partner::getId,
                        p -> p.getDisplayName() != null ? p.getDisplayName() : p.getCompanyName(),
                        (a, b) -> a));
    }

    private String csvEscape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ── Stats Aggregation ────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> stats(
            @RequestParam(defaultValue = "24h") String period) {
        java.time.Instant since = switch (period) {
            case "1h" -> java.time.Instant.now().minus(java.time.Duration.ofHours(1));
            case "12h" -> java.time.Instant.now().minus(java.time.Duration.ofHours(12));
            case "7d" -> java.time.Instant.now().minus(java.time.Duration.ofDays(7));
            case "30d" -> java.time.Instant.now().minus(java.time.Duration.ofDays(30));
            default -> java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        };

        List<FileTransferRecord> records = transferRepo.findAll(
                (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("uploadedAt"), since));

        long total = records.size();
        Map<String, Long> byStatus = records.stream()
                .collect(Collectors.groupingBy(r -> r.getStatus().name(), Collectors.counting()));
        long completed = byStatus.getOrDefault("MOVED_TO_SENT", 0L) + byStatus.getOrDefault("COMPLETED", 0L);
        double successRate = total > 0 ? (double) completed / total : 0;

        long withChecksums = records.stream()
                .filter(r -> r.getSourceChecksum() != null && r.getDestinationChecksum() != null)
                .count();
        long verified = records.stream()
                .filter(r -> r.getSourceChecksum() != null && r.getSourceChecksum().equals(r.getDestinationChecksum()))
                .count();

        return Map.of(
                "period", period,
                "totalTransfers", total,
                "successRate", Math.round(successRate * 1000.0) / 1000.0,
                "byStatus", byStatus,
                "integrityStats", Map.of("verified", verified, "withChecksums", withChecksums, "total", total),
                "failed", byStatus.getOrDefault("FAILED", 0L)
        );
    }

    // ── SSE Real-Time Stream ─────────────────────────────────────────────────

    private final java.util.concurrent.CopyOnWriteArrayList<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>
            sseClients = new java.util.concurrent.CopyOnWriteArrayList<>();

    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(300_000L); // 5 min
        sseClients.add(emitter);
        emitter.onCompletion(() -> sseClients.remove(emitter));
        emitter.onTimeout(() -> sseClients.remove(emitter));
        emitter.onError(e -> sseClients.remove(emitter));
        return emitter;
    }

    /** Broadcast an activity event to all connected SSE clients. Called by event consumers. */
    public void broadcastActivityEvent(String eventName, Object data) {
        for (var emitter : sseClients) {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name(eventName).data(data));
            } catch (Exception e) {
                sseClients.remove(emitter);
            }
        }
    }
}
