package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.cache.PartnerCache;
import com.filetransfer.shared.cache.TransferRecordBatchWriter;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.dto.FileUploadedEvent;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.matching.CompiledFlowRule;
import com.filetransfer.shared.matching.FlowRuleRegistry;
import com.filetransfer.shared.matching.FlowRuleRegistryInitializer;
import com.filetransfer.shared.matching.MatchContext;
import com.filetransfer.shared.matching.MatchContextBuilder;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.repository.core.PartnerRepository;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.TrackIdGenerator;
import com.filetransfer.shared.vfs.FileRef;
import com.filetransfer.shared.vfs.VfsFlowBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import org.slf4j.MDC;

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

    /** Present only when VFS is active. Null for PHYSICAL-only deployments. */
    @Autowired(required = false)
    @Nullable
    private VfsFlowBridge vfsBridge;
    private final AuditService auditService;
    private final AiClassificationClient aiClassifier;
    /**
     * ConnectorDispatcher is @ConditionalOnProperty(platform.connectors.enabled,
     * matchIfMissing=false). Absent in minimal-profile Spring contexts (db-migrate).
     * ObjectProvider lets RoutingEngine wire both ways; call sites use ifAvailable.
     */
    private final ObjectProvider<ConnectorDispatcher> connectorDispatcher;
    private final PlatformConfig platformConfig;
    private final FlowRuleRegistry flowRuleRegistry;
    private final FlowExecutionRepository executionRepository;
    private final PartnerRepository partnerRepository;

    /** Phase 1: provides cached FileFlow lookup — populated every 30s alongside rule registry. */
    @Autowired(required = false)
    @Nullable
    private FlowRuleRegistryInitializer flowRuleRegistryInitializer;

    /** Phase 1: L1+L2 partner cache — eliminates per-file partner slug DB lookup. */
    @Autowired(required = false)
    @Nullable
    private PartnerCache partnerCache;

    /** Phase 1: Async batch writer — eliminates synchronous FileTransferRecord DB INSERT. */
    @Autowired(required = false)
    @Nullable
    private TransferRecordBatchWriter recordBatchWriter;

    /** Append-only event journal for flow execution audit trail. Optional — null if not configured. */
    @Autowired(required = false)
    @Nullable
    private FlowEventJournal flowEventJournal;

    /** RabbitMQ publisher for file upload events (backpressure). Optional — falls back to synchronous. */
    @Autowired(required = false)
    @Nullable
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    /** Identifies this pod in published events so the same pod's consumer can skip self-broadcasts. */
    @Autowired(required = false)
    @Nullable
    private OriginPodId originPodId;

    /** Storage-manager client for persisting uploaded files (enables download button). */
    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.client.StorageServiceClient storageClient;

    /** Compliance enforcement — checks geo-blocking, file extensions, size, encryption, AI risk. */
    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.compliance.ComplianceEnforcementService complianceService;

    @org.springframework.beans.factory.annotation.Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

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

        // ── Publish file upload event to RabbitMQ for backpressure-controlled processing ──
        if (rabbitTemplate != null) {
            try {
                long fileSizeBytes = -1;
                try { fileSizeBytes = java.nio.file.Files.size(java.nio.file.Paths.get(absoluteSourcePath)); } catch (Exception ignored) {}
                FileUploadedEvent event = FileUploadedEvent.builder()
                        .trackId(trackId)
                        .accountId(sourceAccount.getId())
                        .username(sourceAccount.getUsername())
                        .protocol(sourceAccount.getProtocol())
                        .relativeFilePath(relativeFilePath)
                        .absoluteSourcePath(absoluteSourcePath)
                        .sourceIp(sourceIp)
                        .filename(filename)
                        .fileSizeBytes(fileSizeBytes)
                        // Phase 1: enrich with account snapshot — consumer skips DB fetch
                        .storageMode(sourceAccount.getStorageMode())
                        .partnerId(sourceAccount.getPartnerId())
                        .homeDir(sourceAccount.getHomeDir())
                        .build();
                // Stamp the event with our pod ID so FileUploadEventConsumer can
                // skip the message when it fanouts back to us — prevents the
                // duplicate local+consumer execution that produced spurious
                // "Flow X failed: null" log pairs alongside real COMPLETED lines.
                final String podId = originPodId != null ? originPodId.getId() : null;
                rabbitTemplate.convertAndSend(exchange, "file.uploaded", event,
                        msg -> {
                            if (podId != null) {
                                msg.getMessageProperties().setHeader(OriginPodId.HEADER, podId);
                            }
                            return msg;
                        });
                log.info("[{}] FileUploadedEvent published to RabbitMQ (broadcast)", trackId);
                // Both VIRTUAL and PHYSICAL: process locally on originating service.
                // RabbitMQ is for broadcast (Activity Monitor, cache eviction, monitoring).
                // Flow execution runs here — the originating service has full context.
            } catch (Exception e) {
                log.warn("[{}] RabbitMQ publish failed — falling back to synchronous: {}", trackId, e.getMessage());
            }
        }

        // ── Fallback: synchronous processing (when RabbitMQ unavailable) ──
        MDC.put("trackId", trackId);
        try {
        onFileUploadedInternal(sourceAccount, relativeFilePath, absoluteSourcePath, sourceIp, filename, trackId);
        } finally {
            MDC.remove("trackId");
        }
    }

    /** Internal implementation — called by @Async fallback or by FileUploadEventConsumer. */
    void onFileUploadedInternal(TransferAccount sourceAccount, String relativeFilePath,
                                         String absoluteSourcePath, String sourceIp,
                                         String filename, String trackId) {
        // Phase 7.1: Idempotency — skip if trackId already processed (RabbitMQ redelivery)
        if (recordRepository.findByTrackId(trackId).isPresent()) {
            log.info("[{}] Idempotent skip — trackId already exists in file_transfer_records", trackId);
            return;
        }
        log.info("[{}] File received: account={} file={}", trackId, sourceAccount.getUsername(), filename);

        // Step 0: Compliance check — geo-blocking, file extensions, size, encryption, AI risk
        long fileSizeBytes = -1;
        try { fileSizeBytes = Files.size(Paths.get(absoluteSourcePath)); } catch (Exception ignored) {}
        // VIRTUAL accounts: file isn't on disk — get size from VFS entry
        if (fileSizeBytes <= 0 && vfsBridge != null && !"PHYSICAL".equalsIgnoreCase(sourceAccount.getStorageMode())) {
            try {
                var vfsEntry = vfsBridge.getVfs().stat(sourceAccount.getId(), relativeFilePath);
                if (vfsEntry.isPresent() && vfsEntry.get().getSizeBytes() > 0) {
                    fileSizeBytes = vfsEntry.get().getSizeBytes();
                }
            } catch (Exception ignored) {}
        }

        // Compliance check — uses the platform's default compliance profile (if configured)
        // Future: per-partner or per-server compliance profiles via ComplianceProfile assignment
        if (complianceService != null) {
            try {
                var ctx = new com.filetransfer.shared.compliance.ComplianceEnforcementService.ComplianceContext(
                        trackId, null, null, null,
                        sourceAccount.getUsername(), filename, fileSizeBytes,
                        false, false, false,
                        absoluteSourcePath != null ? java.nio.file.Paths.get(absoluteSourcePath) : null,
                        sourceIp, null);
                var result = complianceService.evaluate(ctx);
                if (result.blocked()) {
                    log.warn("[{}] COMPLIANCE BLOCKED: {} violation(s) — {}", trackId,
                            result.violations().size(),
                            result.violations().stream().map(v -> v.getViolationType()).collect(java.util.stream.Collectors.joining(", ")));
                    // Record as REJECTED
                    FileTransferRecord record = FileTransferRecord.builder()
                            .trackId(trackId)
                            .sourceAccountId(sourceAccount.getId())
                            .originalFilename(filename)
                            .fileSizeBytes(fileSizeBytes)
                            .status(FileTransferStatus.FAILED)
                            .errorMessage("Compliance blocked: " + result.violations().stream()
                                    .map(v -> v.getViolationType()).collect(java.util.stream.Collectors.joining(", ")))
                            .uploadedAt(java.time.Instant.now())
                            .build();
                    recordRepository.save(record);
                    return;
                }
            } catch (Exception e) {
                log.warn("[{}] Compliance check failed (allowing file): {}", trackId, e.getMessage());
            }
        }

        // Step 1: Build match context and find matching flow

        // Resolve partner slug for matching — Phase 1: L1+L2 cache (was: DB query per file)
        String partnerSlug = null;
        if (sourceAccount.getPartnerId() != null) {
            if (partnerCache != null) {
                PartnerCache.PartnerSnapshot snap = partnerCache.get(sourceAccount.getPartnerId());
                partnerSlug = snap != null ? snap.slug() : null;
            } else {
                partnerSlug = partnerRepository.findById(sourceAccount.getPartnerId())
                        .map(p -> p.getSlug()).orElse(null);
            }
        }

        // Build metadata from available context (account, partner, platform)
        // Enables metadata.KEY_EQ rules like: metadata.region = "APAC"
        java.util.Map<String, String> matchMetadata = new java.util.HashMap<>();
        if (sourceAccount.getStorageMode() != null) {
            matchMetadata.put("storageMode", sourceAccount.getStorageMode());
        }
        if (partnerSlug != null) {
            matchMetadata.put("partner", partnerSlug);
        }
        if (partnerCache != null && sourceAccount.getPartnerId() != null) {
            PartnerCache.PartnerSnapshot snap = partnerCache.get(sourceAccount.getPartnerId());
            if (snap != null && snap.companyName() != null) {
                matchMetadata.put("partnerName", snap.companyName());
            }
        }
        // Protocol services can inject custom metadata via FileUploadedEvent in the future

        MatchContextBuilder ctxBuilder = MatchContext.builder()
                .fromUploadEvent(sourceAccount.getProtocol(), sourceAccount.getUsername(),
                        sourceAccount.getId(), sourceAccount.getPartnerId(),
                        relativeFilePath, absoluteSourcePath)
                .withDirection(MatchContext.Direction.INBOUND)
                .withFileSize(fileSizeBytes)
                .withEdiDetection(Paths.get(absoluteSourcePath))
                .withSourceIp(sourceIp)
                .withPartnerSlug(partnerSlug)
                .withTimeNow()
                .withMetadata(matchMetadata);

        // VIRTUAL accounts: physical path doesn't exist. Read first 128 bytes
        // from VFS for EDI header detection — never loads the full file.
        boolean isVirtualAccount = !"PHYSICAL".equalsIgnoreCase(sourceAccount.getStorageMode());
        if (isVirtualAccount && vfsBridge != null && ctxBuilder.needsEdiDetection()) {
            try {
                byte[] header = vfsBridge.getVfs().readHeader(sourceAccount.getId(), relativeFilePath, 128);
                if (header != null && header.length > 0) {
                    com.filetransfer.shared.matching.EdiDetector.EdiInfo info =
                            com.filetransfer.shared.matching.EdiDetector.detect(new String(header));
                    if (info != null) {
                        ctxBuilder.withEdiStandard(info.standard());
                        ctxBuilder.withEdiType(info.typeCode());
                        log.info("[{}] EDI detected from VFS: standard={} type={}", trackId, info.standard(), info.typeCode());
                    }
                }
            } catch (Exception e) {
                log.debug("[{}] VFS EDI detection skipped: {}", trackId, e.getMessage());
            }
        }

        MatchContext matchContext = ctxBuilder.build();

        String processedFilePath = absoluteSourcePath;
        // Zero-I/O matching — pre-compiled rules evaluated in-memory
        log.debug("[{}] Flow matching: filename={} protocol={} direction={} registry.size={} registry.initialized={}",
                trackId, matchContext.filename(), matchContext.protocol(), matchContext.direction(),
                flowRuleRegistry.size(), flowRuleRegistry.isInitialized());
        CompiledFlowRule matchedRule = flowRuleRegistry.findMatch(matchContext);
        // Phase 1: use cached flow from registry initializer (was: DB query per matched file)
        FileFlow matchedFlow = null;
        if (matchedRule != null) {
            if (flowRuleRegistryInitializer != null) {
                matchedFlow = flowRuleRegistryInitializer.getFlow(matchedRule.flowId());
            }
            if (matchedFlow == null) {
                matchedFlow = flowRepository.findById(matchedRule.flowId()).orElse(null);
            }
        }

        // ── Match decision logging ──
        if (matchedRule != null && matchedFlow != null) {
            log.info("[{}] Matched flow '{}' (priority={}, rule={})",
                    trackId, matchedRule.flowName(), matchedRule.priority(), matchedRule.flowId());
            if (flowEventJournal != null) {
                flowEventJournal.recordFlowMatched(trackId, null, matchedRule.flowName());
            }
        } else {
            log.warn("[{}] NO FLOW MATCH: filename={} protocol={} direction={} registrySize={}",
                    trackId, matchContext.filename(), matchContext.protocol(),
                    matchContext.direction(), flowRuleRegistry.size());
        }

        // ── Phase 1: Defer source checksum to storage-manager response or async task ──
        // Was: Files.readAllBytes() + SHA-256 (10-500ms blocking per file, size-dependent).
        // Now: checksum set later from storage-manager CAS write response (already computes SHA-256),
        //      or computed async for files not pushed to storage.
        // ── Compute source checksum BEFORE creating record (eliminates batch writer race) ──
        String sourceChecksum = null;

        // Push file to storage-manager (enables download from Activity Monitor)
        if (storageClient != null && Files.exists(Paths.get(absoluteSourcePath))) {
            try {
                java.io.InputStream fileStream = java.nio.file.Files.newInputStream(
                        java.nio.file.Paths.get(absoluteSourcePath));
                java.util.Map<String, Object> storeResult = storageClient.storeStream(
                        fileStream, fileSizeBytes, filename, sourceAccount.getUsername(), trackId);
                log.info("[{}] File stored in storage-manager (download enabled)", trackId);
                // Extract SHA-256 from CAS write response (already computed during storage)
                if (storeResult != null && storeResult.containsKey("sha256")) {
                    sourceChecksum = storeResult.get("sha256").toString();
                }
            } catch (Exception e) {
                log.debug("[{}] Storage push skipped: {}", trackId, e.getMessage());
            }
        }
        // Fallback checksum — only when storage-manager unavailable
        if (sourceChecksum == null && Files.exists(Paths.get(absoluteSourcePath))) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(Files.readAllBytes(Paths.get(absoluteSourcePath)));
                sourceChecksum = java.util.HexFormat.of().formatHex(hash);
            } catch (Exception e) {
                log.debug("[{}] Checksum fallback skipped: {}", trackId, e.getMessage());
            }
        }

        // ── Create FileTransferRecord WITH checksum already set (no post-save update needed) ──
        FileTransferRecord transferRecord = FileTransferRecord.builder()
                .trackId(trackId)
                .originalFilename(filename)
                .sourceFilePath(relativeFilePath)
                .destinationFilePath(relativeFilePath) // updated when delivery completes
                .sourceAccountId(sourceAccount.getId())
                .flowId(matchedFlow != null ? matchedFlow.getId() : null)
                .fileSizeBytes(fileSizeBytes > 0 ? fileSizeBytes : null)
                .sourceChecksum(sourceChecksum)
                .status(FileTransferStatus.PENDING)
                .build();
        try {
            if (recordBatchWriter != null) {
                recordBatchWriter.submit(transferRecord);
                log.info("[{}] FileTransferRecord queued (batch writer)", trackId);
            } else {
                recordRepository.save(transferRecord);
                log.info("[{}] FileTransferRecord created (Activity Monitor)", trackId);
            }
        } catch (Exception e) {
            log.warn("[{}] Could not create FileTransferRecord: {}", trackId, e.getMessage());
        }

        // ── VIRTUAL-mode accounts (default): FileRef-based streaming pipeline ──────────
        if (isVirtualAccount && vfsBridge != null) {
            Optional<com.filetransfer.shared.entity.vfs.VirtualEntry> entryOpt =
                    vfsBridge.getVfs().stat(sourceAccount.getId(), relativeFilePath);
            if (entryOpt.isPresent()) {
                com.filetransfer.shared.entity.vfs.VirtualEntry entry = entryOpt.get();
                FileRef ref = new FileRef(
                        entry.getStorageKey(), relativeFilePath, sourceAccount.getId(),
                        entry.getSizeBytes(), trackId, entry.getContentType(),
                        entry.getStorageBucket() != null ? entry.getStorageBucket() : "STANDARD");

                // Register CAS object in storage-manager (enables download button)
                if (storageClient != null && entry.getStorageKey() != null) {
                    try {
                        storageClient.register(trackId, entry.getStorageKey(), filename,
                                sourceAccount.getUsername(), entry.getSizeBytes());
                        log.info("[{}] VIRTUAL file registered in storage-manager (download enabled)", trackId);
                    } catch (Exception e) {
                        log.debug("[{}] Storage register skipped: {}", trackId, e.getMessage());
                    }
                }

                if (matchedFlow != null) {
                    log.info("[{}] Executing flow '{}' ({} steps) [VIRTUAL]",
                            trackId, matchedFlow.getName(), matchedFlow.getSteps().size());
                    try {
                        FlowExecution exec = flowEngine.executeFlowRef(matchedFlow, trackId,
                                filename, ref, matchedFlow.getMatchCriteria());
                        if (exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
                            transferRecord.setRoutedAt(Instant.now());
                            transferRecord.setCompletedAt(Instant.now());
                            transferRecord.setStatus(FileTransferStatus.MOVED_TO_SENT);
                            // VIRTUAL: destination checksum = source (zero-copy, same content)
                            if (transferRecord.getSourceChecksum() != null) {
                                transferRecord.setDestinationChecksum(transferRecord.getSourceChecksum());
                            }
                            recordRepository.save(transferRecord);
                            log.info("[{}] Flow '{}' completed (VIRTUAL). Final key={}",
                                    trackId, matchedFlow.getName(), exec.getCurrentStorageKey());
                        } else {
                            log.error("[{}] Flow '{}' status={}: {}", trackId,
                                    matchedFlow.getName(),
                                    exec.getStatus(),
                                    java.util.Objects.requireNonNullElse(exec.getErrorMessage(), "<no error message>"));
                        }
                    } catch (Exception e) {
                        log.error("[{}] Flow execution error (VIRTUAL): {}: {}", trackId,
                                e.getClass().getSimpleName(),
                                java.util.Objects.requireNonNullElse(e.getMessage(), "<no message>"));
                    }
                } else {
                    log.warn("[{}] UNMATCHED file (VIRTUAL): account={} file={}",
                            trackId, sourceAccount.getUsername(), filename);
                    executionRepository.save(FlowExecution.builder()
                            .trackId(trackId).originalFilename(filename)
                            .currentStorageKey(entry.getStorageKey())
                            .status(FlowExecution.FlowStatus.UNMATCHED)
                            .stepResults(List.of()).build());
                }
            } else {
                log.warn("[{}] VIRTUAL account but no VirtualEntry for path={} — skipping",
                        trackId, relativeFilePath);
            }
            // AI classification for VIRTUAL: handled by SCREEN step in the flow pipeline.
            // File bytes are in VFS (MinIO/DB), not on disk — aiClassifier.classify(Path)
            // can't read them. The SCREEN step materializes from VFS and scans properly.
            return; // VIRTUAL accounts: flow IS the routing; skip physical routing below
        }

        // ── PHYSICAL-mode accounts: flow-based pipeline ────────────────────────
        // If a FileFlow matches, it IS the complete pipeline — processing + delivery.
        // No folder mapping needed. The flow handles everything end-to-end.
        if (matchedFlow != null) {
            log.info("[{}] Executing flow '{}' ({} steps)", trackId, matchedFlow.getName(),
                    matchedFlow.getSteps().size());
            try {
                FlowExecution exec = flowEngine.executeFlow(matchedFlow, trackId, filename,
                        processedFilePath, matchedFlow.getMatchCriteria());
                if (exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
                    log.info("[{}] Flow '{}' completed. Output: {}", trackId, matchedFlow.getName(),
                            exec.getCurrentFilePath());
                    final String flowName = matchedFlow.getName();
                    connectorDispatcher.ifAvailable(d -> d.dispatch(ConnectorDispatcher.MftEvent.builder()
                            .eventType("FLOW_COMPLETED").severity("INFO").trackId(trackId)
                            .filename(filename).account(sourceAccount.getUsername())
                            .summary("Flow '" + flowName + "' completed successfully")
                            .details(flowName).service("routing-engine").build()));
                } else {
                    log.error("[{}] Flow '{}' status={}: {}", trackId, matchedFlow.getName(),
                            exec.getStatus(),
                            java.util.Objects.requireNonNullElse(exec.getErrorMessage(), "<no error message>"));
                }
            } catch (Exception e) {
                log.error("[{}] Flow execution error: {}: {}", trackId,
                        e.getClass().getSimpleName(),
                        java.util.Objects.requireNonNullElse(e.getMessage(), "<no message>"));
            }
            // Flow handled everything — skip legacy folder mapping routing
            return;
        }

        // ── No flow matched — fall back to legacy folder mapping routing ──────────

        // AI Classification — only needed for legacy path (flows include SCREEN step)
        boolean isEncrypted = processedFilePath.endsWith(".enc") || processedFilePath.endsWith(".pgp");
        AiClassificationClient.ClassificationDecision classification =
                aiClassifier.classify(Paths.get(processedFilePath), trackId, isEncrypted);
        if (!classification.allowed()) {
            log.error("[{}] BLOCKED by AI classification: {}", trackId, classification.blockReason());
            auditService.logFailure(sourceAccount, trackId, "AI_BLOCKED",
                    processedFilePath, classification.blockReason());
            connectorDispatcher.ifAvailable(d -> d.dispatch(ConnectorDispatcher.MftEvent.builder()
                    .eventType("AI_BLOCKED").severity("CRITICAL").trackId(trackId)
                    .filename(filename).account(sourceAccount.getUsername())
                    .summary("Transfer blocked: sensitive data detected without encryption")
                    .details(classification.blockReason()).service("routing-engine").build()));
            return;
        }

        // Evaluate folder mappings and route (legacy path — only when no flow matched)
        List<RoutingEvaluator.RoutingDecision> decisions =
                evaluator.evaluate(sourceAccount, relativeFilePath, absoluteSourcePath);

        if (decisions.isEmpty()) {
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
            record.setDestinationAccountId(destAccount.getId());

            // Compute destination checksum for integrity verification
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(Files.readAllBytes(sentPath));
                record.setDestinationChecksum(java.util.HexFormat.of().formatHex(hash));
            } catch (Exception ex) {
                log.debug("[{}] Destination checksum skipped: {}", record.getTrackId(), ex.getMessage());
            }

            recordRepository.save(record);
            log.info("[{}] Transfer complete: {} -> {}", record.getTrackId(), record.getOriginalFilename(), sentPath);

            // ── Audit: transfer completion ──
            if (auditService != null) {
                auditService.logFileDownload(destAccount, record.getTrackId(),
                        sentPath.toString(), record.getOriginalFilename(), sentPath, null, null);
            }
            // ── Lifecycle event: transfer completed ──
            connectorDispatcher.ifAvailable(d -> d.dispatch(ConnectorDispatcher.MftEvent.builder()
                    .eventType("TRANSFER_COMPLETED").severity("INFO").trackId(record.getTrackId())
                    .filename(record.getOriginalFilename()).account(destAccount.getUsername())
                    .summary("File " + record.getOriginalFilename() + " delivered successfully")
                    .details(sentPath.toString()).service("routing-engine").build()));
        } catch (IOException e) {
            log.error("Failed to move file to sent: record={}", record.getId(), e);
            markFailed(record, e.getMessage());
        }
    }

    /**
     * Receive a forwarded file via streaming multipart — no Base64 encoding.
     * File bytes stream directly from the HTTP request body to local disk.
     */
    @Transactional
    public void receiveStreamedFile(java.util.UUID recordId, String destinationPath,
                                     String originalFilename, java.io.InputStream fileData) throws IOException {
        Path dest = Paths.get(destinationPath);
        Files.createDirectories(dest.getParent());
        Files.copy(fileData, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        FileTransferRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown record: " + recordId));
        record.setStatus(FileTransferStatus.IN_OUTBOX);
        record.setRoutedAt(Instant.now());
        recordRepository.save(record);
        log.info("[{}] Received streamed file: {}", record.getTrackId(), dest);
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

    /**
     * Route file to a remote service instance via streaming multipart POST.
     * Bytes flow directly from local disk → HTTP body → remote service. No Base64, no full heap load.
     * Memory overhead: ~8 KB buffer (was: 233% of file size with Base64 in JSON body).
     */
    private void routeRemotely(String sourceAbsPath, FileTransferRecord record,
                                ServiceRegistration destService, String trackId) throws IOException {
        // Embed trackId in destination filename
        String destPath = record.getDestinationFilePath();
        Path destP = Paths.get(destPath);
        String destWithTrack = destP.getParent().resolve(embedTrackId(destP.getFileName().toString(), trackId)).toString();

        // Stream file directly from disk as multipart — no byte[] in heap
        org.springframework.core.io.FileSystemResource fileResource =
                new org.springframework.core.io.FileSystemResource(Paths.get(sourceAbsPath).toFile());

        String url = "http://" + destService.getHost() + ":" + destService.getControlPort()
                + "/internal/files/receive-stream"
                + "?recordId=" + record.getId()
                + "&destinationPath=" + java.net.URLEncoder.encode(destWithTrack, "UTF-8")
                + "&originalFilename=" + java.net.URLEncoder.encode(
                        embedTrackId(record.getOriginalFilename(), trackId), "UTF-8");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        addInternalAuth(headers, destService.getHost());
        org.springframework.util.LinkedMultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", fileResource);

        // ── Compute destination checksum before forwarding (PCI 11.5 integrity) ──
        try {
            record.setDestinationChecksum(AuditService.sha256(Paths.get(sourceAbsPath)));
        } catch (Exception e) {
            log.warn("[{}] Could not compute destination checksum for remote route: {}", trackId, e.getMessage());
        }

        ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), Void.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            record.setStatus(FileTransferStatus.IN_OUTBOX);
            record.setRoutedAt(Instant.now());
            recordRepository.save(record);
            log.info("[{}] Routed remotely (streaming) to {}:{}", trackId, destService.getHost(), destService.getControlPort());
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
        connectorDispatcher.ifAvailable(d -> d.dispatch(ConnectorDispatcher.MftEvent.builder()
                .eventType("TRANSFER_FAILED").severity("HIGH").trackId(record.getTrackId())
                .filename(record.getOriginalFilename())
                .summary("File transfer failed: " + record.getOriginalFilename())
                .details(error).service("routing-engine").build()));
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
