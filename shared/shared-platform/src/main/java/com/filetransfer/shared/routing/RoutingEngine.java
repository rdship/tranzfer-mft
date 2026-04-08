package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.matching.CompiledFlowRule;
import com.filetransfer.shared.matching.FlowRuleRegistry;
import com.filetransfer.shared.matching.MatchContext;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Core routing engine. Called by each service when files arrive or are downloaded.
 * Now integrates with FlowProcessingEngine for file processing pipelines.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingEngine {

    private final RoutingEvaluator evaluator;
    private final FileTransferRecordRepository recordRepository;
    private final ClusterService clusterService;
    private final RestTemplate restTemplate;
    private final TrackIdGenerator trackIdGenerator;
    private final FlowProcessingEngine flowEngine;

    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeWorkloadClient;
    private final FileFlowRepository flowRepository;
    private final AuditService auditService;
    private final AiClassificationClient aiClassifier;
    private final ConnectorDispatcher connectorDispatcher;
    private final PlatformConfig platformConfig;
    private final FlowRuleRegistry flowRuleRegistry;
    private final FlowExecutionRepository executionRepository;
    private final PartnerRepository partnerRepository;

    /**
     * Called by each service when a file upload completes.
     * 1. Assigns a Track ID
     * 2. Evaluates folder mappings for routing
     * 3. Checks for matching file flows (encrypt/compress/rename)
     * 4. Executes flow steps on the file
     * 5. Routes the processed file to destination
     */
    @Async
    public void onFileUploaded(TransferAccount sourceAccount, String relativeFilePath, String absoluteSourcePath) {
        onFileUploaded(sourceAccount, relativeFilePath, absoluteSourcePath, null);
    }

    @Async
    public void onFileUploaded(TransferAccount sourceAccount, String relativeFilePath,
                                String absoluteSourcePath, String sourceIp) {
        String filename = relativeFilePath.contains("/") ?
                relativeFilePath.substring(relativeFilePath.lastIndexOf('/') + 1) : relativeFilePath;
        String trackId = trackIdGenerator.generate();

        log.info("[{}] File received: account={} file={}", trackId, sourceAccount.getUsername(), filename);

        // Step 1: Build match context and find matching flow
        long fileSizeBytes = -1;
        try { fileSizeBytes = Files.size(Paths.get(absoluteSourcePath)); } catch (Exception ignored) {}

        // Resolve partner slug for matching
        String partnerSlug = null;
        if (sourceAccount.getPartnerId() != null) {
            partnerSlug = partnerRepository.findById(sourceAccount.getPartnerId())
                    .map(p -> p.getSlug()).orElse(null);
        }

        MatchContext matchContext = MatchContext.builder()
                .fromUploadEvent(sourceAccount.getProtocol(), sourceAccount.getUsername(),
                        sourceAccount.getId(), sourceAccount.getPartnerId(),
                        relativeFilePath, absoluteSourcePath)
                .withDirection(MatchContext.Direction.INBOUND)
                .withFileSize(fileSizeBytes)
                .withEdiDetection(Paths.get(absoluteSourcePath))
                .withSourceIp(sourceIp)
                .withPartnerSlug(partnerSlug)
                .withTimeNow()
                .build();

        String processedFilePath = absoluteSourcePath;
        // Zero-I/O matching — pre-compiled rules evaluated in-memory
        CompiledFlowRule matchedRule = flowRuleRegistry.findMatch(matchContext);
        FileFlow matchedFlow = matchedRule != null
                ? flowRepository.findById(matchedRule.flowId()).orElse(null) : null;

        if (matchedFlow != null) {
            log.info("[{}] Executing flow '{}' ({} steps)", trackId, matchedFlow.getName(),
                    matchedFlow.getSteps().size());
            try {
                FlowExecution exec = flowEngine.executeFlow(matchedFlow, trackId, filename,
                        processedFilePath, matchedFlow.getMatchCriteria());
                if (exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
                    processedFilePath = exec.getCurrentFilePath();
                    log.info("[{}] Flow '{}' completed. Output: {}", trackId, matchedFlow.getName(),
                            processedFilePath);
                } else {
                    log.error("[{}] Flow '{}' failed: {}", trackId, matchedFlow.getName(),
                            exec.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("[{}] Flow execution error: {}", trackId, e.getMessage());
            }
        }

        // Step 2: AI Classification — scan for PCI/PII before routing
        boolean isEncrypted = processedFilePath.endsWith(".enc") || processedFilePath.endsWith(".pgp");
        AiClassificationClient.ClassificationDecision classification =
                aiClassifier.classify(Paths.get(processedFilePath), trackId, isEncrypted);
        if (!classification.allowed()) {
            log.error("[{}] BLOCKED by AI classification: {}", trackId, classification.blockReason());
            auditService.logFailure(sourceAccount, trackId, "AI_BLOCKED",
                    processedFilePath, classification.blockReason());
            connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                    .eventType("AI_BLOCKED").severity("CRITICAL").trackId(trackId)
                    .filename(filename).account(sourceAccount.getUsername())
                    .summary("Transfer blocked: sensitive data detected without encryption")
                    .details(classification.blockReason()).service("routing-engine").build());
            return;
        }

        // Step 3: Evaluate folder mappings and route
        List<RoutingEvaluator.RoutingDecision> decisions =
                evaluator.evaluate(sourceAccount, relativeFilePath, absoluteSourcePath);

        if (decisions.isEmpty() && matchedFlow == null) {
            log.warn("[{}] UNMATCHED file: account={} file={}", trackId,
                    sourceAccount.getUsername(), filename);
            FlowExecution unmatchedExec = FlowExecution.builder()
                    .trackId(trackId)
                    .originalFilename(filename)
                    .currentFilePath(absoluteSourcePath)
                    .status(FlowExecution.FlowStatus.UNMATCHED)
                    .stepResults(List.of())
                    .build();
            executionRepository.save(unmatchedExec);
            return;
        }

        for (RoutingEvaluator.RoutingDecision decision : decisions) {
            try {
                // Assign trackId + compute source checksum (PCI 11.5 integrity)
                decision.getRecord().setTrackId(trackId);
                Path processedPath = Paths.get(processedFilePath);
                try {
                    decision.getRecord().setFileSizeBytes(Files.size(processedPath));
                } catch (Exception ignored) {}
                decision.getRecord().setSourceChecksum(AuditService.sha256(processedPath));
                recordRepository.save(decision.getRecord());

                // Audit: file upload recorded
                auditService.logFileUpload(sourceAccount, trackId, relativeFilePath,
                        filename, Paths.get(absoluteSourcePath), null, null);

                // Route the processed file (not the original)
                route(decision, processedFilePath, trackId);

                // Audit: file routed
                auditService.logFileRoute(trackId, absoluteSourcePath,
                        decision.getRecord().getDestinationFilePath(), processedPath);
            } catch (Exception e) {
                log.error("[{}] Routing failed: {}", trackId, e.getMessage(), e);
                markFailed(decision.getRecord(), e.getMessage());
                auditService.logFailure(sourceAccount, trackId, "ROUTE_FAIL",
                        absoluteSourcePath, e.getMessage());
            }
        }
    }

    @Async
    public void onFileDownloaded(TransferAccount destAccount, String absoluteFilePath) {
        Optional<FileTransferRecord> opt = evaluator.findOutboxRecord(destAccount, absoluteFilePath);
        if (opt.isEmpty()) {
            log.debug("No pending outbox record for downloaded file: {}", absoluteFilePath);
            return;
        }

        FileTransferRecord record = opt.get();
        try {
            Path outboxPath = Paths.get(absoluteFilePath);
            Path sentDir = outboxPath.getParent().getParent().resolve("sent");
            Files.createDirectories(sentDir);
            Path sentPath = sentDir.resolve(outboxPath.getFileName());
            Files.move(outboxPath, sentPath, StandardCopyOption.REPLACE_EXISTING);

            record.setStatus(FileTransferStatus.MOVED_TO_SENT);
            record.setDownloadedAt(Instant.now());
            record.setCompletedAt(Instant.now());
            record.setDestinationFilePath(sentPath.toString());
            recordRepository.save(record);
            log.info("[{}] Transfer complete: {} -> {}", record.getTrackId(), record.getOriginalFilename(), sentPath);
        } catch (IOException e) {
            log.error("Failed to move file to sent: record={}", record.getId(), e);
            markFailed(record, e.getMessage());
        }
    }

    @Transactional
    public void receiveForwardedFile(FileForwardRequest request) throws IOException {
        Path dest = Paths.get(request.getDestinationAbsolutePath());
        Files.createDirectories(dest.getParent());
        byte[] bytes = Base64.getDecoder().decode(request.getFileContentBase64());
        Files.write(dest, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        FileTransferRecord record = recordRepository.findById(request.getRecordId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown record: " + request.getRecordId()));
        record.setStatus(FileTransferStatus.IN_OUTBOX);
        record.setRoutedAt(Instant.now());
        recordRepository.save(record);
        log.info("[{}] Received forwarded file: {}", record.getTrackId(), dest);
    }

    // --- private ---

    private void route(RoutingEvaluator.RoutingDecision decision, String processedFilePath, String trackId)
            throws IOException {
        FileTransferRecord record = decision.getRecord();
        ServiceRegistration destService = decision.getDestinationService();
        String destAbsPath = record.getDestinationFilePath();

        // Archive source file
        Path sourcePath = Paths.get(record.getSourceFilePath());
        Path archiveDir = sourcePath.getParent().getParent().resolve("archive");
        Files.createDirectories(archiveDir);
        Path archivePath = archiveDir.resolve(sourcePath.getFileName());
        Files.copy(sourcePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
        record.setArchiveFilePath(archivePath.toString());

        // Use processed file for routing (may differ from original if flow ran)
        String fileToRoute = processedFilePath;

        if (destService == null || isLocalService(destService)) {
            routeLocally(fileToRoute, destAbsPath, record, trackId);
        } else {
            routeRemotely(fileToRoute, record, destService, trackId);
        }
    }

    private void routeLocally(String sourceAbsPath, String destAbsPath, FileTransferRecord record, String trackId)
            throws IOException {
        Path src = Paths.get(sourceAbsPath);
        // Embed trackId in destination filename: abc.txt -> abc.txt#TRZXXXXXX
        Path originalDst = Paths.get(destAbsPath);
        String destFilename = embedTrackId(originalDst.getFileName().toString(), trackId);
        Path dst = originalDst.getParent().resolve(destFilename);

        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);

        record.setDestinationFilePath(dst.toString());
        record.setStatus(FileTransferStatus.IN_OUTBOX);
        record.setRoutedAt(Instant.now());
        // Compute destination checksum for integrity verification
        record.setDestinationChecksum(com.filetransfer.shared.audit.AuditService.sha256(dst));
        recordRepository.save(record);
        log.info("[{}] Routed locally: {} -> {}", trackId, src.getFileName(), dst.getFileName());
    }

    private void routeRemotely(String sourceAbsPath, FileTransferRecord record,
                                ServiceRegistration destService, String trackId) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(sourceAbsPath));
        String encoded = Base64.getEncoder().encodeToString(bytes);

        // Embed trackId in destination filename
        String destPath = record.getDestinationFilePath();
        Path destP = Paths.get(destPath);
        String destWithTrack = destP.getParent().resolve(embedTrackId(destP.getFileName().toString(), trackId)).toString();

        FileForwardRequest req = FileForwardRequest.builder()
                .recordId(record.getId())
                .destinationAbsolutePath(destWithTrack)
                .originalFilename(embedTrackId(record.getOriginalFilename(), trackId))
                .fileContentBase64(encoded)
                .build();

        String url = "http://" + destService.getHost() + ":" + destService.getControlPort()
                + "/internal/files/receive";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(headers, destService.getHost());
        HttpEntity<FileForwardRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            record.setStatus(FileTransferStatus.IN_OUTBOX);
            record.setRoutedAt(Instant.now());
            recordRepository.save(record);
            log.info("[{}] Routed remotely to {}:{}", trackId, destService.getHost(), destService.getControlPort());
        } else {
            throw new RuntimeException("Remote forward returned " + response.getStatusCode());
        }
    }

    private boolean isLocalService(ServiceRegistration service) {
        return clusterService.isLocalService(service);
    }

    private void addInternalAuth(HttpHeaders headers, String targetService) {
        if (spiffeWorkloadClient != null && spiffeWorkloadClient.isAvailable()) {
            String token = spiffeWorkloadClient.getJwtSvidFor(targetService);
            if (token != null) headers.setBearerAuth(token);
        }
    }

    @Transactional
    protected void markFailed(FileTransferRecord record, String error) {
        record.setStatus(FileTransferStatus.FAILED);
        record.setErrorMessage(error);
        recordRepository.save(record);
        // Dispatch to external connectors (ServiceNow, Slack, etc.)
        connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                .eventType("TRANSFER_FAILED").severity("HIGH").trackId(record.getTrackId())
                .filename(record.getOriginalFilename())
                .summary("File transfer failed: " + record.getOriginalFilename())
                .details(error).service("routing-engine").build());
    }

    /**
     * Embed track ID into filename: abc.txt -> abc.txt#TRZXXXXXX
     * This ensures the receiving client can see the track ID in the filename.
     */
    private String embedTrackId(String filename, String trackId) {
        if (trackId == null || trackId.isBlank()) return filename;
        return filename + "#" + trackId;
    }

}
