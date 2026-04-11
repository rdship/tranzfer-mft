package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.security.Roles;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Transfer Journey Tracker — single screen showing the complete lifecycle
 * of a file transfer across all microservices.
 *
 * For a given trackId, shows:
 * - Upload event (which client, protocol, time)
 * - AI classification result (PCI/PII scan)
 * - Flow execution steps (each step with duration)
 * - Screening result (OFAC/AML)
 * - Routing decision (source → destination)
 * - Delivery status
 * - Audit trail (every event in chronological order)
 * - Checksum verification (source vs destination integrity)
 */
@RestController @RequestMapping("/api/journey") @RequiredArgsConstructor @Slf4j
@PreAuthorize(Roles.USER)
public class TransferJourneyController {

    private final FileTransferRecordRepository transferRepo;
    private final AuditLogRepository auditLogRepo;
    private final FlowExecutionRepository flowExecRepo;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FlowStepSnapshotRepository stepSnapshotRepo;

    /**
     * Get complete journey for a single transfer by trackId.
     */
    @GetMapping("/{trackId}")
    public ResponseEntity<TransferJourney> getJourney(@PathVariable String trackId) {
        // Find transfer record
        var transfer = transferRepo.findByTrackId(trackId).orElse(null);

        // Find flow execution
        var flowExec = flowExecRepo.findByTrackId(trackId).orElse(null);

        // Find all audit entries for this trackId (indexed query — replaces full-table scan)
        var auditLogs = auditLogRepo.findByTrackIdOrderByTimestampAsc(trackId);

        if (transfer == null && flowExec == null && auditLogs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Build journey stages
        List<JourneyStage> stages = new ArrayList<>();

        // Stage: Upload
        if (transfer != null) {
            stages.add(JourneyStage.builder()
                    .order(1).service("sftp-service / ftp-service")
                    .stage("FILE_RECEIVED").status("COMPLETED")
                    .detail("File: " + transfer.getOriginalFilename())
                    .metadata(Map.of(
                            "filename", transfer.getOriginalFilename(),
                            "source", transfer.getSourceFilePath(),
                            "size", transfer.getFileSizeBytes() != null ? transfer.getFileSizeBytes() + " bytes" : "unknown",
                            "sourceChecksum", transfer.getSourceChecksum() != null ? transfer.getSourceChecksum() : "not computed"
                    ))
                    .timestamp(transfer.getUploadedAt())
                    .build());
        }

        // Stage: AI Classification (from audit logs)
        var classifyLog = auditLogs.stream().filter(a -> "FILE_UPLOAD".equals(a.getAction())).findFirst();
        if (classifyLog.isPresent()) {
            var cl = classifyLog.get();
            stages.add(JourneyStage.builder()
                    .order(2).service("ai-engine")
                    .stage("AI_CLASSIFICATION").status(cl.isSuccess() ? "PASSED" : "BLOCKED")
                    .detail(cl.getSha256Checksum() != null ? "SHA-256: " + cl.getSha256Checksum().substring(0, 16) + "..." : "Scanned")
                    .timestamp(cl.getTimestamp())
                    .build());
        }

        // Stage: Flow Execution
        if (flowExec != null) {
            stages.add(JourneyStage.builder()
                    .order(3).service("flow-engine")
                    .stage("FLOW_PROCESSING").status(flowExec.getStatus().name())
                    .detail("Flow: " + (flowExec.getFlow() != null ? flowExec.getFlow().getName() : "unknown")
                            + " (" + flowExec.getCurrentStep() + "/" +
                            (flowExec.getFlow() != null ? flowExec.getFlow().getSteps().size() : "?") + " steps)")
                    .timestamp(flowExec.getStartedAt())
                    .build());

            // Individual flow steps
            if (flowExec.getStepResults() != null) {
                for (var step : flowExec.getStepResults()) {
                    stages.add(JourneyStage.builder()
                            .order(3 + step.getStepIndex())
                            .service("flow-engine")
                            .stage("FLOW_STEP_" + step.getStepType())
                            .status(step.getStatus())
                            .detail(step.getStepType() + " (" + step.getDurationMs() + "ms)")
                            .metadata(Map.of(
                                    "input", step.getInputFile() != null ? step.getInputFile() : "",
                                    "output", step.getOutputFile() != null ? step.getOutputFile() : "",
                                    "durationMs", String.valueOf(step.getDurationMs())
                            ))
                            .timestamp(flowExec.getStartedAt())
                            .build());
                }
            }
        }

        // Stage: Screening (from audit logs)
        var screenLog = auditLogs.stream().filter(a -> a.getAction() != null && a.getAction().contains("SCREEN")).findFirst();
        if (screenLog.isPresent()) {
            stages.add(JourneyStage.builder()
                    .order(10).service("screening-service")
                    .stage("SANCTIONS_SCREENING")
                    .status(screenLog.get().isSuccess() ? "CLEAR" : "HIT")
                    .timestamp(screenLog.get().getTimestamp())
                    .build());
        }

        // Stage: Routing
        if (transfer != null && transfer.getRoutedAt() != null) {
            stages.add(JourneyStage.builder()
                    .order(20).service("routing-engine")
                    .stage("FILE_ROUTED").status("COMPLETED")
                    .detail("Destination: " + transfer.getDestinationFilePath())
                    .metadata(Map.of(
                            "destination", transfer.getDestinationFilePath(),
                            "destChecksum", transfer.getDestinationChecksum() != null ? transfer.getDestinationChecksum() : "pending"
                    ))
                    .timestamp(transfer.getRoutedAt())
                    .build());
        }

        // Stage: Delivery / Download
        if (transfer != null && transfer.getDownloadedAt() != null) {
            stages.add(JourneyStage.builder()
                    .order(30).service("sftp-service / ftp-service")
                    .stage("FILE_DELIVERED").status("COMPLETED")
                    .detail("Downloaded by recipient")
                    .timestamp(transfer.getDownloadedAt())
                    .build());
        }

        // Stage: Completion
        if (transfer != null && transfer.getCompletedAt() != null) {
            stages.add(JourneyStage.builder()
                    .order(40).service("platform")
                    .stage("TRANSFER_COMPLETE").status("COMPLETED")
                    .timestamp(transfer.getCompletedAt())
                    .build());
        }

        // Failure stage
        if (transfer != null && "FAILED".equals(transfer.getStatus().name())) {
            stages.add(JourneyStage.builder()
                    .order(99).service("platform")
                    .stage("TRANSFER_FAILED").status("FAILED")
                    .detail(transfer.getErrorMessage())
                    .metadata(Map.of("retryCount", String.valueOf(transfer.getRetryCount())))
                    .build());
        }

        stages.sort(Comparator.comparingInt(JourneyStage::getOrder));

        // Build integrity check
        String integrityStatus = "PENDING";
        if (transfer != null && transfer.getSourceChecksum() != null && transfer.getDestinationChecksum() != null) {
            integrityStatus = transfer.getSourceChecksum().equals(transfer.getDestinationChecksum()) ? "VERIFIED" : "MISMATCH";
        }

        // Enrich with per-step snapshots (input/output previews, checksums, durations)
        List<FlowStepSnapshot> stepSnapshots = null;
        if (stepSnapshotRepo != null) {
            try {
                stepSnapshots = stepSnapshotRepo.findByTrackIdOrderByStepIndex(trackId);
            } catch (Exception e) {
                log.debug("FlowStepSnapshot query failed for trackId={}: {}", trackId, e.getMessage());
            }
        }

        TransferJourney journey = TransferJourney.builder()
                .trackId(trackId)
                .filename(transfer != null ? transfer.getOriginalFilename() : flowExec != null ? flowExec.getOriginalFilename() : "unknown")
                .overallStatus(transfer != null ? transfer.getStatus().name() : flowExec != null ? flowExec.getStatus().name() : "UNKNOWN")
                .stages(stages)
                .stepSnapshots(stepSnapshots)
                .auditTrail(auditLogs.stream().map(a -> AuditEntry.builder()
                        .action(a.getAction()).success(a.isSuccess())
                        .principal(a.getPrincipal()).timestamp(a.getTimestamp())
                        .detail(a.getErrorMessage()).build())
                        .collect(Collectors.toList()))
                .integrityStatus(integrityStatus)
                .sourceChecksum(transfer != null ? transfer.getSourceChecksum() : null)
                .destinationChecksum(transfer != null ? transfer.getDestinationChecksum() : null)
                .totalDurationMs(transfer != null && transfer.getUploadedAt() != null && transfer.getCompletedAt() != null
                        ? java.time.Duration.between(transfer.getUploadedAt(), transfer.getCompletedAt()).toMillis() : null)
                .build();

        return ResponseEntity.ok(journey);
    }

    /**
     * Search journeys by various criteria.
     */
    @GetMapping
    public List<Map<String, Object>> searchJourneys(
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {

        return transferRepo.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "uploadedAt")))
                .getContent().stream()
                .filter(r -> filename == null || (r.getOriginalFilename() != null && r.getOriginalFilename().contains(filename)))
                .filter(r -> status == null || r.getStatus().name().equals(status))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trackId", r.getTrackId());
                    m.put("filename", r.getOriginalFilename());
                    m.put("status", r.getStatus().name());
                    m.put("uploadedAt", r.getUploadedAt());
                    m.put("retryCount", r.getRetryCount());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // === DTOs ===

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TransferJourney {
        private String trackId;
        private String filename;
        private String overallStatus;
        private List<JourneyStage> stages;
        private List<FlowStepSnapshot> stepSnapshots;
        private List<AuditEntry> auditTrail;
        private String integrityStatus;
        private String sourceChecksum;
        private String destinationChecksum;
        private Long totalDurationMs;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JourneyStage {
        private int order;
        private String service;
        private String stage;
        private String status;
        private String detail;
        private Map<String, String> metadata;
        private java.time.Instant timestamp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuditEntry {
        private String action;
        private boolean success;
        private String principal;
        private java.time.Instant timestamp;
        private String detail;
    }
}
