package com.filetransfer.shared.routing;

import com.filetransfer.shared.client.EncryptionServiceClient;
import com.filetransfer.shared.client.ForwarderServiceClient;
import com.filetransfer.shared.client.ScreeningServiceClient;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.connector.PartnerWebhookDispatcher;
import com.filetransfer.shared.flow.FlowFunction;
import com.filetransfer.shared.flow.FlowFunctionContext;
import com.filetransfer.shared.flow.FlowFunctionRegistry;
import com.filetransfer.shared.flow.FlowStageManager;
import com.filetransfer.shared.event.FlowStepEvent;
import com.filetransfer.shared.vfs.FileRef;
import com.filetransfer.shared.vfs.VfsFlowBridge;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Core file processing pipeline engine.
 * Evaluates matching flows for incoming files and executes each step in order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowProcessingEngine {

    private final FileFlowRepository flowRepository;
    private final FlowExecutionRepository executionRepository;
    private final FlowApprovalRepository approvalRepository;
    private final TrackIdGenerator trackIdGenerator;
    private final TransferAccountRepository accountRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final ClusterService clusterService;
    private final RestTemplate restTemplate;
    private final PlatformConfig platformConfig;
    private final StorageServiceClient storageClient;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeWorkloadClient;
    private final ServiceClientProperties serviceProps;

    /** Injected only when VFS is on the classpath (shared-platform). Null for PHYSICAL-only deployments. */
    @Autowired(required = false)
    @Nullable
    private VfsFlowBridge vfsBridge;

    /** Admin-level connector dispatch (Slack, PagerDuty, etc.). Optional — null if not configured. */
    @Autowired(required = false)
    @Nullable
    private ConnectorDispatcher connectorDispatcher;

    /** Partner-level webhook dispatch. Optional — null if no webhooks configured. */
    @Autowired(required = false)
    @Nullable
    private PartnerWebhookDispatcher partnerWebhookDispatcher;

    /** Append-only event journal for flow execution audit trail and actor recovery. */
    @Autowired(required = false)
    @Nullable
    private FlowEventJournal flowEventJournal;

    /** SEDA stage manager for bounded-queue flow execution. Optional — runs synchronously if absent. */
    @Autowired(required = false)
    @Nullable
    private FlowStageManager stageManager;

    /** Plugin function registry. When present, custom/plugin FlowFunctions are tried for unknown step types. */
    @Autowired(required = false)
    @Nullable
    private FlowFunctionRegistry functionRegistry;

    /**
     * Execute a specific flow for a file. Creates execution record and processes each step.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename, String inputPath) {
        return executeFlow(flow, trackId, filename, inputPath, null);
    }

    /**
     * Execute a specific flow with matched criteria snapshot for audit trail.
     * When SEDA stages are available, step execution is submitted to the INTAKE
     * stage (bounded-queue, virtual-thread pool) and this method returns immediately
     * with status PROCESSING. When SEDA is absent, runs synchronously as before.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename,
                                      String inputPath, com.filetransfer.shared.matching.MatchCriteria matchedCriteria) {
        FlowExecution exec = FlowExecution.builder()
                .trackId(trackId)
                .flow(flow)
                .originalFilename(filename)
                .currentFilePath(inputPath)
                .status(FlowExecution.FlowStatus.PROCESSING)
                .matchedCriteria(matchedCriteria)
                .stepResults(new ArrayList<>())
                .build();
        exec = executionRepository.save(exec);

        // ── Journal: record execution start ──
        if (flowEventJournal != null) {
            flowEventJournal.recordExecutionStarted(trackId, exec.getId(), null, flow.getSteps().size());
        }

        if (stageManager != null) {
            final FlowExecution sedaExec = exec;
            stageManager.submit("INTAKE", () -> executeFlowSteps(sedaExec, flow, trackId, filename, inputPath));
            log.info("[{}] Flow '{}' submitted to SEDA INTAKE stage", trackId, flow.getName());
            return exec;
        }

        // ── Synchronous fallback (no SEDA) ──
        executeFlowSteps(exec, flow, trackId, filename, inputPath);
        return exec;
    }

    /**
     * Internal: execute all flow steps for a PHYSICAL-mode flow execution.
     * Called synchronously or from SEDA INTAKE stage worker thread.
     */
    private void executeFlowSteps(FlowExecution exec, FileFlow flow, String trackId,
                                   String filename, String inputPath) {
        String currentFile = inputPath;
        List<FlowExecution.StepResult> results = new ArrayList<>();

        for (int i = 0; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();

            // ── APPROVE step: pause for admin sign-off (pass-through in PHYSICAL mode) ──
            if ("APPROVE".equalsIgnoreCase(step.getType())) {
                FlowApproval approval = FlowApproval.builder()
                        .executionId(exec.getId())
                        .trackId(trackId)
                        .flowName(flow.getName())
                        .originalFilename(filename)
                        .stepIndex(i)
                        .requiredApprovers(stepCfg.get("requiredApprovers"))
                        .build();
                approvalRepository.save(approval);
                exec.setStatus(FlowExecution.FlowStatus.PAUSED);
                exec.setCurrentStep(i);
                exec.setStepResults(results);
                executionRepository.save(exec);
                if (flowEventJournal != null) {
                    flowEventJournal.recordExecutionPaused(trackId, exec.getId(), i, "awaiting admin approval");
                }
                log.info("[{}] Flow PAUSED at APPROVE step {} — awaiting admin sign-off", trackId, i);
                return;
            }

            int maxStepRetries = Integer.parseInt(stepCfg.getOrDefault("retryCount", "0"));
            boolean stepSucceeded = false;

            for (int attempt = 0; attempt <= maxStepRetries; attempt++) {
                long start = System.currentTimeMillis();
                try {
                    if (attempt > 0) {
                        // Exponential backoff between step retries: 2s, 4s, 8s...
                        long backoff = 2000L * (1L << (attempt - 1));
                        log.info("[{}] Step {}/{} ({}) retry {}/{} — waiting {}ms",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt, maxStepRetries, backoff);
                        Thread.sleep(Math.min(backoff, 30000));
                    }
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepStarted(trackId, exec.getId(), i, step.getType(), null, attempt + 1);
                    }
                    String outputFile = processStep(step, currentFile, trackId, i);
                    long duration = System.currentTimeMillis() - start;
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status(attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK")
                            .inputFile(currentFile).outputFile(outputFile)
                            .durationMs(duration).build());
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepCompleted(trackId, exec.getId(), i, step.getType(), null, 0, duration);
                    }
                    currentFile = outputFile;
                    exec.setCurrentStep(i + 1);
                    exec.setCurrentFilePath(currentFile);
                    log.info("[{}] Step {}/{} ({}) completed in {}ms{}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(), duration,
                            attempt > 0 ? " (after " + attempt + " retries)" : "");
                    stepSucceeded = true;
                    break;
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    if (attempt < maxStepRetries && isRetryableStepError(e)) {
                        if (flowEventJournal != null) {
                            long backoff = 2000L * (1L << attempt);
                            flowEventJournal.recordStepRetrying(trackId, exec.getId(), i, step.getType(), attempt + 2, Math.min(backoff, 30000));
                        }
                        log.warn("[{}] Step {}/{} ({}) attempt {} failed (retryable): {}",
                                trackId, i + 1, flow.getSteps().size(), step.getType(), attempt + 1, e.getMessage());
                        continue;
                    }
                    // Final failure — no more retries
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status("FAILED").inputFile(currentFile)
                            .durationMs(duration).error(e.getMessage()).build());
                    exec.setStatus(FlowExecution.FlowStatus.FAILED);
                    exec.setErrorMessage("Step " + i + " (" + step.getType() + ") failed"
                            + (attempt > 0 ? " after " + (attempt + 1) + " attempts" : "")
                            + ": " + e.getMessage());
                    exec.setStepResults(results);
                    exec.setCompletedAt(Instant.now());
                    executionRepository.save(exec);
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepFailed(trackId, exec.getId(), i, step.getType(), e.getMessage(), attempt + 1);
                        flowEventJournal.recordExecutionFailed(trackId, exec.getId(), exec.getErrorMessage());
                    }
                    dispatchFlowEvent("FLOW_FAILED", exec, flow);
                    log.error("[{}] Step {}/{} ({}) FAILED: {}", trackId, i + 1, flow.getSteps().size(),
                            step.getType(), e.getMessage());
                    return;
                }
            }
            if (!stepSucceeded) {
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") exhausted all retries");
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                dispatchFlowEvent("FLOW_FAILED", exec, flow);
                return;
            }
        }

        exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
        exec.setStepResults(results);
        exec.setCompletedAt(Instant.now());
        executionRepository.save(exec);
        if (flowEventJournal != null) {
            long totalDuration = exec.getStartedAt() != null
                    ? Instant.now().toEpochMilli() - exec.getStartedAt().toEpochMilli() : 0;
            flowEventJournal.recordExecutionCompleted(trackId, exec.getId(), totalDuration, results.size());
        }
        dispatchFlowEvent("FLOW_COMPLETED", exec, flow);
        log.info("[{}] Flow '{}' completed successfully for '{}'", trackId, flow.getName(), filename);
    }

    /**
     * Fire FLOW_COMPLETED or FLOW_FAILED to both the admin ConnectorDispatcher
     * and the partner-level PartnerWebhookDispatcher. Both are optional — null-safe.
     */
    private void dispatchFlowEvent(String eventType, FlowExecution exec, FileFlow flow) {
        String flowName = (flow != null && flow.getName() != null) ? flow.getName() : "unknown";
        if (connectorDispatcher != null) {
            try {
                connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                        .eventType(eventType)
                        .severity("FLOW_FAILED".equals(eventType) ? "HIGH" : "INFO")
                        .trackId(exec.getTrackId())
                        .filename(exec.getOriginalFilename())
                        .summary(("FLOW_FAILED".equals(eventType) ? "Flow failed: " : "Flow completed: ") + flowName)
                        .details(exec.getErrorMessage() != null ? exec.getErrorMessage() : flowName)
                        .service("flow-engine")
                        .build());
            } catch (Exception e) {
                log.debug("[{}] ConnectorDispatcher skipped: {}", exec.getTrackId(), e.getMessage());
            }
        }
        if (partnerWebhookDispatcher != null) {
            try {
                partnerWebhookDispatcher.dispatch(eventType, exec, flowName);
            } catch (Exception e) {
                log.debug("[{}] PartnerWebhookDispatcher skipped: {}", exec.getTrackId(), e.getMessage());
            }
        }
    }

    private String processStep(FileFlow.FlowStep step, String inputPath, String trackId, int stepIndex) throws Exception {
        Path input = Paths.get(inputPath);
        Path workDir = input.getParent().resolve(".flow-work");
        Files.createDirectories(workDir);
        Map<String, String> cfg = step.getConfig() != null ? step.getConfig() : Map.of();

        return switch (step.getType().toUpperCase()) {
            case "COMPRESS_GZIP" -> compressGzip(input, workDir);
            case "DECOMPRESS_GZIP" -> decompressGzip(input, workDir);
            case "COMPRESS_ZIP" -> compressZip(input, workDir);
            case "DECOMPRESS_ZIP" -> decompressZip(input, workDir);
            case "ENCRYPT_PGP" -> callEncryptionService(input, workDir, "pgp", "encrypt", cfg);
            case "DECRYPT_PGP" -> callEncryptionService(input, workDir, "pgp", "decrypt", cfg);
            case "ENCRYPT_AES" -> callEncryptionService(input, workDir, "aes", "encrypt", cfg);
            case "DECRYPT_AES" -> callEncryptionService(input, workDir, "aes", "decrypt", cfg);
            case "RENAME" -> renameFile(input, workDir, cfg, trackId);
            case "SCREEN" -> callScreeningService(input, trackId, cfg);
            case "EXECUTE_SCRIPT" -> executeScript(input, workDir, cfg, trackId);
            case "MAILBOX" -> executeMailbox(input, cfg, trackId);
            case "FILE_DELIVERY" -> executeFileDelivery(input, cfg, trackId);
            case "CONVERT_EDI" -> callEdiConverter(input, workDir, cfg, trackId);
            case "ROUTE"   -> inputPath; // Route is handled by RoutingEngine after flow completes
            case "APPROVE" -> inputPath; // Handled above loop; pass-through if reached
            default -> {
                // Try FlowFunctionRegistry for custom/plugin functions
                if (functionRegistry != null) {
                    Optional<FlowFunction> fn = functionRegistry.get(step.getType());
                    if (fn.isPresent()) {
                        FlowFunctionContext ctx = new FlowFunctionContext(
                                input, workDir, cfg, trackId,
                                input.getFileName().toString());
                        log.info("[{}] Executing plugin function: {} (step {})",
                                trackId, step.getType(), stepIndex);
                        yield fn.get().executePhysical(ctx);
                    }
                }
                throw new IllegalArgumentException("Unknown step type: " + step.getType());
            }
        };
    }

    // --- Step implementations ---

    private String compressGzip(Path input, Path workDir) throws IOException {
        Path output = workDir.resolve(input.getFileName() + ".gz");
        try (InputStream in = Files.newInputStream(input);
             GZIPOutputStream gzOut = new GZIPOutputStream(Files.newOutputStream(output))) {
            in.transferTo(gzOut);
        }
        return output.toString();
    }

    private String decompressGzip(Path input, Path workDir) throws IOException {
        String name = input.getFileName().toString();
        if (name.endsWith(".gz")) name = name.substring(0, name.length() - 3);
        Path output = workDir.resolve(name);
        try (GZIPInputStream gzIn = new GZIPInputStream(Files.newInputStream(input));
             OutputStream out = Files.newOutputStream(output)) {
            gzIn.transferTo(out);
        }
        return output.toString();
    }

    private String compressZip(Path input, Path workDir) throws IOException {
        Path output = workDir.resolve(input.getFileName() + ".zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(output))) {
            zipOut.putNextEntry(new ZipEntry(input.getFileName().toString()));
            Files.copy(input, zipOut);
            zipOut.closeEntry();
        }
        return output.toString();
    }

    private static final long MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_ZIP_TOTAL_SIZE = 512L * 1024 * 1024; // 512MB zip bomb guard

    private String decompressZip(Path input, Path workDir) throws IOException {
        String lastEntry = null;
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        long entryCount = 0;
        long totalBytes = 0;

        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(input))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("ZIP archive exceeds maximum entry count (" + MAX_ZIP_ENTRIES + ")");
                }
                if (!entry.isDirectory()) {
                    // ZipSlip protection: ensure resolved path stays within workDir
                    Path out = normalizedWorkDir.resolve(entry.getName()).normalize();
                    if (!out.startsWith(normalizedWorkDir)) {
                        throw new IOException("ZIP entry escapes target directory: " + entry.getName());
                    }
                    Files.createDirectories(out.getParent());
                    try (OutputStream fout = Files.newOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zipIn.read(buf)) != -1) {
                            totalBytes += n;
                            if (totalBytes > MAX_ZIP_TOTAL_SIZE) {
                                throw new IOException("ZIP archive exceeds maximum uncompressed size ("
                                        + MAX_ZIP_TOTAL_SIZE / (1024 * 1024) + "MB)");
                            }
                            fout.write(buf, 0, n);
                        }
                    }
                    lastEntry = out.toString();
                }
                zipIn.closeEntry();
            }
        }
        if (lastEntry == null) throw new IOException("ZIP archive was empty");
        return lastEntry;
    }

    private String callEncryptionService(Path input, Path workDir, String algo, String operation,
                                          Map<String, String> cfg) throws Exception {
        String encryptionUrl = serviceProps.getEncryptionService().getUrl();

        String keyId = cfg.get("keyId");
        String name = input.getFileName().toString();
        if (operation.equals("decrypt") && name.endsWith(".enc")) {
            name = name.substring(0, name.length() - 4);
        } else if (operation.equals("encrypt")) {
            name = name + ".enc";
        }
        Path output = workDir.resolve(name);

        if (keyId == null || keyId.isBlank()) {
            // No key configured — pass through (allows flows without encryption keys)
            log.warn("No keyId configured for {} step — passing through unchanged", operation);
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            return output.toString();
        }

        // Call encryption-service REST API with Base64 payload
        byte[] fileBytes = Files.readAllBytes(input);
        String base64Input = java.util.Base64.getEncoder().encodeToString(fileBytes);

        String endpoint = encryptionUrl + "/api/encrypt/" + operation + "/base64?keyId=" + keyId;
        log.info("Calling encryption-service: {} {} (keyId={})", operation.toUpperCase(), algo, keyId);

        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(base64Input, headers);
            String base64Result = restTemplate.postForObject(endpoint, entity, String.class);

            byte[] resultBytes = java.util.Base64.getDecoder().decode(base64Result);
            Files.write(output, resultBytes);
            log.info("{}({}) complete: {} -> {} ({} bytes)", operation.toUpperCase(), algo,
                    input.getFileName(), output.getFileName(), resultBytes.length);
        } catch (Exception e) {
            // SECURITY: Never pass through unencrypted — encryption was explicitly configured.
            // Let the flow retry mechanism handle transient failures (encryption-service or keystore-manager not ready).
            throw new RuntimeException("Encryption step failed (keyId=" + keyId + "): " + e.getMessage(), e);
        }
        return output.toString();
    }

    private String renameFile(Path input, Path workDir, Map<String, String> cfg, String trackId) throws IOException {
        String pattern = cfg.getOrDefault("pattern", "${filename}");
        String originalName = input.getFileName().toString();
        String baseName = originalName.contains(".") ?
                originalName.substring(0, originalName.lastIndexOf('.')) : originalName;
        String ext = originalName.contains(".") ?
                originalName.substring(originalName.lastIndexOf('.')) : "";

        String newName = pattern
                .replace("${filename}", originalName)
                .replace("${basename}", baseName)
                .replace("${ext}", ext)
                .replace("${trackid}", trackId)
                .replace("${timestamp}", String.valueOf(System.currentTimeMillis()));

        Path output = workDir.resolve(newName);
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        return output.toString();
    }

    private static final Pattern SAFE_SHELL_ARG = Pattern.compile("^[a-zA-Z0-9._/\\-]+$");

    /** Shell operators that indicate command chaining / injection in templates. */
    private static final Pattern TEMPLATE_INJECTION =
            Pattern.compile("[;|&`!<>]|\\$\\(|\\.\\.|\\.\\\\");

    /** Shell-escape a value to prevent command injection. */
    private static String shellEscape(String value) {
        if (SAFE_SHELL_ARG.matcher(value).matches()) return value;
        // Single-quote the value, escaping any embedded single quotes
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Execute a shell script as a flow step. The script receives the input file path
     * as an argument. If it exits non-zero, the flow fails.
     * Config: {"command": "python3 /scripts/validate.py ${file}", "timeoutSeconds": "60"}
     */
    private String executeScript(Path input, Path workDir, Map<String, String> cfg, String trackId) throws Exception {
        String cmdTemplate = cfg.getOrDefault("command", "echo ${file}");
        int timeout = Integer.parseInt(cfg.getOrDefault("timeoutSeconds", "300"));

        // Validate the command template itself — strip known placeholders, then check remainder
        String templateCheck = cmdTemplate
                .replace("${file}", "").replace("${trackid}", "").replace("${workdir}", "");
        if (TEMPLATE_INJECTION.matcher(templateCheck).find()) {
            throw new SecurityException("[" + trackId + "] Script template contains disallowed shell operators");
        }

        // Validate the script path (first token) is a safe path
        String scriptPath = cmdTemplate.split("\\s+")[0];
        if (!SAFE_SHELL_ARG.matcher(scriptPath).matches()) {
            throw new SecurityException("[" + trackId + "] Script path contains disallowed characters: " + scriptPath);
        }

        // Shell-escape all interpolated values to prevent command injection
        String cmd = cmdTemplate
                .replace("${file}", shellEscape(input.toAbsolutePath().toString()))
                .replace("${trackid}", shellEscape(trackId))
                .replace("${workdir}", shellEscape(workDir.toAbsolutePath().toString()));

        log.info("[{}] Executing script: {}", trackId, cmd);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        pb.directory(workDir.toFile());
        Process proc = pb.start();

        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); throw new RuntimeException("Script timed out after " + timeout + "s"); }
        if (proc.exitValue() != 0) throw new RuntimeException("Script exit code " + proc.exitValue() + ": " + output);

        log.info("[{}] Script completed (exit 0)", trackId);
        // If script produced an output file, use it; otherwise pass through
        String outputFile = cfg.get("outputFile");
        if (outputFile != null) {
            Path out = Paths.get(outputFile.replace("${workdir}", workDir.toAbsolutePath().toString()));
            if (Files.exists(out)) return out.toString();
        }
        return input.toString();
    }

    /**
     * Call the screening-service to scan the file against OFAC/AML sanctions lists.
     * If a HIT is found, throws an exception to BLOCK the transfer.
     */
    private String callScreeningService(Path input, String trackId, Map<String, String> cfg) throws Exception {
        String screeningUrl = serviceProps.getScreeningService().getUrl();

        log.info("[{}] Screening file against sanctions lists...", trackId);

        try {
            // Call screening service with multipart file upload
            org.springframework.core.io.FileSystemResource fileResource =
                    new org.springframework.core.io.FileSystemResource(input.toFile());
            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", fileResource);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            addInternalAuth(headers, "screening-service");

            String url = screeningUrl + "/api/v1/screening/scan?trackId=" + trackId;
            if (cfg.containsKey("columns")) url += "&columns=" + cfg.get("columns");

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(body, headers);
            org.springframework.http.ResponseEntity<java.util.Map> response =
                    restTemplate.postForEntity(url, request, java.util.Map.class);

            if (response.getBody() != null) {
                String outcome = (String) response.getBody().get("outcome");
                String action = (String) response.getBody().get("actionTaken");
                int hitsFound = response.getBody().get("hitsFound") instanceof Number n ? n.intValue() : 0;

                log.info("[{}] Screening result: {} ({} hits, action={})", trackId, outcome, hitsFound, action);

                if ("BLOCKED".equals(action)) {
                    throw new SecurityException("SANCTIONS HIT: File blocked by screening service. "
                            + hitsFound + " match(es) found against OFAC/AML sanctions lists.");
                }
            }
        } catch (SecurityException se) {
            throw se; // re-throw blocking exceptions
        } catch (Exception e) {
            // Screening service unreachable — configurable behavior
            String onFailure = cfg.getOrDefault("onFailure", "PASS");
            if ("BLOCK".equals(onFailure)) {
                throw new Exception("Screening service unreachable — blocking as configured. " + e.getMessage());
            }
            log.warn("[{}] Screening service unreachable: {} — allowing transfer (graceful degradation)", trackId, e.getMessage());
        }

        return input.toString(); // File passes through unchanged
    }

    /**
     * MAILBOX step — delivers the file to a destination user's outbox within the platform.
     * Config: {"destinationUsername": "bob", "protocol": "SFTP"}
     * The file is always placed in the destination user's outbox folder.
     */
    private String executeMailbox(Path input, Map<String, String> cfg, String trackId) throws Exception {
        String destUsername = cfg.get("destinationUsername");
        if (destUsername == null || destUsername.isBlank()) {
            throw new IllegalArgumentException("MAILBOX step requires 'destinationUsername' in config");
        }

        String protocolStr = cfg.getOrDefault("protocol", "SFTP");
        Protocol protocol;
        try {
            protocol = Protocol.valueOf(protocolStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            protocol = Protocol.SFTP;
        }
        final Protocol resolvedProtocol = protocol;

        TransferAccount destAccount = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(destUsername, resolvedProtocol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination account not found: " + destUsername + " (" + resolvedProtocol + ")"));

        // Build outbox path: {homeDir}/outbox/{filename}
        String homeDir = destAccount.getHomeDir();
        String outboxDir = homeDir + "/outbox";
        String filename = input.getFileName().toString();
        Path outboxPath = Paths.get(outboxDir, filename);

        // Determine which service hosts this account
        ServiceType serviceType = protocolToServiceType(protocol);
        Optional<ServiceRegistration> destService = clusterService.discoverService(serviceType);

        if (destService.isEmpty() || clusterService.isLocalService(destService.get())) {
            // Local delivery — copy file directly to outbox
            Files.createDirectories(outboxPath.getParent());
            Files.copy(input, outboxPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[{}] MAILBOX: delivered locally to {} outbox: {}", trackId, destUsername, outboxPath);
        } else {
            // Remote delivery — POST to the destination service
            ServiceRegistration svc = destService.get();
            byte[] fileBytes = Files.readAllBytes(input);
            String encoded = Base64.getEncoder().encodeToString(fileBytes);

            String url = "http://" + svc.getHost() + ":" + svc.getControlPort()
                    + "/internal/files/receive";

            Map<String, Object> payload = Map.of(
                    "destinationUsername", destUsername,
                    "destinationAbsolutePath", outboxPath.toString(),
                    "originalFilename", filename,
                    "fileContentBase64", encoded
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            addInternalAuth(headers, svc.getHost());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(url, entity, Void.class);
            log.info("[{}] MAILBOX: forwarded to {}:{} for user {}", trackId,
                    svc.getHost(), svc.getControlPort(), destUsername);
        }

        return input.toString(); // Pass through — file delivered as side effect
    }

    /**
     * FILE_DELIVERY step — delivers the file to one or many external delivery endpoints.
     * Config: {"deliveryEndpointIds": "uuid1,uuid2,uuid3"}
     * Each endpoint is called via the external-forwarder-service.
     */
    private String executeFileDelivery(Path input, Map<String, String> cfg, String trackId) throws Exception {
        String endpointIdsStr = cfg.get("deliveryEndpointIds");
        if (endpointIdsStr == null || endpointIdsStr.isBlank()) {
            throw new IllegalArgumentException("FILE_DELIVERY step requires 'deliveryEndpointIds' in config");
        }

        List<UUID> endpointIds = Arrays.stream(endpointIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();

        List<DeliveryEndpoint> endpoints = deliveryEndpointRepository.findByIdInAndActiveTrue(endpointIds);

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No active delivery endpoints found for IDs: " + endpointIdsStr);
        }

        String forwarderUrl = serviceProps.getForwarderService().getUrl();

        byte[] fileBytes = Files.readAllBytes(input);
        String base64Content = Base64.getEncoder().encodeToString(fileBytes);
        String filename = input.getFileName().toString();

        int successCount = 0;
        List<String> failures = new ArrayList<>();

        for (DeliveryEndpoint ep : endpoints) {
            try {
                String url = forwarderUrl + "/api/forward/deliver/" + ep.getId() + "/base64"
                        + "?filename=" + java.net.URLEncoder.encode(filename, "UTF-8")
                        + "&trackId=" + java.net.URLEncoder.encode(trackId != null ? trackId : "", "UTF-8");

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.TEXT_PLAIN);
                addInternalAuth(headers, "external-forwarder-service");
                HttpEntity<String> entity = new HttpEntity<>(base64Content, headers);

                restTemplate.postForEntity(url, entity, Map.class);
                successCount++;
                log.info("[{}] FILE_DELIVERY: delivered to '{}' ({}://{}:{}) OK",
                        trackId, ep.getName(), ep.getProtocol(), ep.getHost(), ep.getPort());
            } catch (Exception e) {
                String msg = ep.getName() + ": " + e.getMessage();
                failures.add(msg);
                log.error("[{}] FILE_DELIVERY: failed for '{}': {}", trackId, ep.getName(), e.getMessage());
            }
        }

        if (successCount == 0) {
            throw new RuntimeException("FILE_DELIVERY failed for all " + endpoints.size()
                    + " endpoints: " + String.join("; ", failures));
        }

        if (!failures.isEmpty()) {
            log.warn("[{}] FILE_DELIVERY: {}/{} endpoints succeeded, {} failed: {}",
                    trackId, successCount, endpoints.size(), failures.size(), failures);
        }

        log.info("[{}] FILE_DELIVERY: completed — {}/{} endpoints delivered", trackId, successCount, endpoints.size());
        return input.toString(); // Pass through — delivery is a side effect
    }

    /**
     * Determine if a step failure is transient and worth retrying.
     * Network errors, timeouts, and temporary service unavailability are retryable.
     * Auth errors, format errors, and security blocks are not.
     */
    private boolean isRetryableStepError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // Not retryable: auth, format, security, sanctions
        if (msg.contains("permission denied") || msg.contains("auth") || msg.contains("401") || msg.contains("403")) return false;
        if (msg.contains("sanctions") || msg.contains("blocked") || msg.contains("ofac")) return false;
        if (msg.contains("schema") || msg.contains("format error") || msg.contains("malformed")) return false;
        if (msg.contains("key expired") || msg.contains("key not found")) return false;
        if (e instanceof IllegalArgumentException) return false;
        // Retryable: network, timeout, temporary errors
        return true;
    }

    private void addInternalAuth(HttpHeaders headers, String targetService) {
        if (spiffeWorkloadClient != null && spiffeWorkloadClient.isAvailable()) {
            String token = spiffeWorkloadClient.getJwtSvidFor(targetService);
            if (token != null) headers.setBearerAuth(token);
        }
    }

    private ServiceType protocolToServiceType(Protocol protocol) {
        return switch (protocol) {
            case SFTP -> ServiceType.SFTP;
            case FTP -> ServiceType.FTP;
            case FTP_WEB -> ServiceType.FTP_WEB;
            case HTTPS -> ServiceType.FTP_WEB;
            case AS2, AS4 -> ServiceType.SFTP; // AS2/AS4 route through platform storage
        };
    }

    /**
     * CONVERT_EDI step — calls the EDI Converter service to convert the file using a trained map.
     * Config: {"targetFormat": "JSON|XML|CSV", "partnerId": "optional-partner-id"}
     */
    private String callEdiConverter(Path input, Path workDir, Map<String, String> cfg, String trackId) throws Exception {
        String converterUrl = serviceProps.getEdiConverter().getUrl();
        String targetFormat = cfg.getOrDefault("targetFormat", "JSON");
        String partnerId = cfg.get("partnerId");

        String content = Files.readString(input);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("targetFormat", targetFormat);
        if (partnerId != null && !partnerId.isBlank()) {
            body.put("partnerId", partnerId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(headers, "edi-converter");
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                converterUrl + "/api/v1/convert/trained",
                HttpMethod.POST, entity,
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        String output = response.getBody() != null ? (String) response.getBody().get("output") : "";

        String ext = switch (targetFormat.toUpperCase()) {
            case "JSON" -> ".json";
            case "XML" -> ".xml";
            case "CSV" -> ".csv";
            case "YAML" -> ".yaml";
            default -> ".txt";
        };

        String baseName = input.getFileName().toString();
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        Path outputFile = workDir.resolve(baseName + ext);
        Files.writeString(outputFile, output);

        log.info("[{}] CONVERT_EDI: {} -> {} (targetFormat={}, partnerId={})",
                trackId, input.getFileName(), outputFile.getFileName(), targetFormat, partnerId);
        return outputFile.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // VIRTUAL-mode pipeline — ephemeral streaming agent, zero local disk I/O
    // ══════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute a flow for a VIRTUAL-mode account.
     *
     * <p>Each transform step reads from / writes to storage-manager as a streaming
     * operation. No local disk is touched. A single {@link FlowExecution} record is
     * written at the start (status=PROCESSING) and updated once at the end
     * (COMPLETED or FAILED) — minimising DB round-trips per step.
     */
    @Transactional
    public FlowExecution executeFlowRef(FileFlow flow, String trackId, String filename,
                                         FileRef ref,
                                         com.filetransfer.shared.matching.MatchCriteria matchedCriteria) {
        return executeFlowRef(flow, trackId, filename, ref, matchedCriteria, null, 0);
    }

    /**
     * Execute a flow from a specific step (used by restart-from-step).
     * When SEDA stages are available, step execution is submitted to the INTAKE
     * stage and this method returns immediately with status PROCESSING.
     *
     * @param existingExec if non-null, update this record rather than creating a new one
     * @param startFromStep 0 = full run; N = skip first N steps, start at step N
     */
    @Transactional
    public FlowExecution executeFlowRef(FileFlow flow, String trackId, String filename,
                                         FileRef ref,
                                         com.filetransfer.shared.matching.MatchCriteria matchedCriteria,
                                         FlowExecution existingExec,
                                         int startFromStep) {
        FlowExecution exec;
        if (existingExec != null) {
            exec = existingExec;
            exec.setStatus(FlowExecution.FlowStatus.PROCESSING);
            exec.setCurrentStorageKey(ref.storageKey());
            exec.setCurrentStep(startFromStep);
            exec.setErrorMessage(null);
            exec.setCompletedAt(null);
            exec.setStepResults(new ArrayList<>());
            exec.setTerminationRequested(false);
        } else {
            exec = FlowExecution.builder()
                    .trackId(trackId).flow(flow).originalFilename(filename)
                    .currentStorageKey(ref.storageKey())
                    .initialStorageKey(ref.storageKey())
                    .status(FlowExecution.FlowStatus.PROCESSING)
                    .matchedCriteria(matchedCriteria).stepResults(new ArrayList<>())
                    .build();
        }
        exec = executionRepository.save(exec);

        if (stageManager != null) {
            final FlowExecution sedaExec = exec;
            stageManager.submit("INTAKE", () -> executeFlowRefSteps(sedaExec, flow, trackId, filename, ref, startFromStep));
            log.info("[{}] Flow '{}' submitted to SEDA INTAKE stage (VIRTUAL)", trackId, flow.getName());
            return exec;
        }

        // ── Synchronous fallback (no SEDA) ──
        executeFlowRefSteps(exec, flow, trackId, filename, ref, startFromStep);
        return exec;
    }

    /**
     * Internal: execute all flow steps for a VIRTUAL-mode flow execution.
     * Called synchronously or from SEDA INTAKE stage worker thread.
     */
    private void executeFlowRefSteps(FlowExecution exec, FileFlow flow, String trackId,
                                      String filename, FileRef ref, int startFromStep) {
        String currentKey  = ref.storageKey();
        String currentPath = ref.virtualPath();
        long   currentSize = ref.sizeBytes();
        List<FlowExecution.StepResult> results = new ArrayList<>();

        for (int i = startFromStep; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();

            // ── APPROVE step: record pass-through snapshot, pause for admin sign-off ──
            if ("APPROVE".equalsIgnoreCase(step.getType())) {
                // Snapshot with identical in/out keys — so resume can find the key via step index
                eventPublisher.publishEvent(new FlowStepEvent(
                        trackId, exec.getId(), i, "APPROVE", "PENDING_APPROVAL",
                        currentKey, currentKey,
                        currentPath, currentPath,
                        currentSize, currentSize,
                        0L, null));
                FlowApproval approval = FlowApproval.builder()
                        .executionId(exec.getId())
                        .trackId(trackId)
                        .flowName(flow.getName())
                        .originalFilename(filename)
                        .stepIndex(i)
                        .pausedStorageKey(currentKey)
                        .pausedVirtualPath(currentPath)
                        .pausedSizeBytes(currentSize)
                        .requiredApprovers(stepCfg.get("requiredApprovers"))
                        .build();
                approvalRepository.save(approval);
                exec.setStatus(FlowExecution.FlowStatus.PAUSED);
                exec.setCurrentStep(i);
                exec.setStepResults(results);
                executionRepository.save(exec);
                log.info("[{}] Flow PAUSED at APPROVE step {} (VIRTUAL) — awaiting admin sign-off, key={}",
                        trackId, i, abbrev(currentKey));
                return;
            }

            int maxRetries = Integer.parseInt(stepCfg.getOrDefault("retryCount", "0"));
            boolean stepSucceeded = false;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                long start = System.currentTimeMillis();
                try {
                    if (attempt > 0) {
                        long backoff = 2000L * (1L << (attempt - 1));
                        log.info("[{}] Step {}/{} ({}) retry {}/{} — waiting {}ms",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt, maxRetries, backoff);
                        Thread.sleep(Math.min(backoff, 30000));
                    }
                    StepOutcome outcome = processStepRef(step, currentKey, currentPath,
                            currentSize, ref, trackId, i);
                    long duration = System.currentTimeMillis() - start;
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status(attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK")
                            .inputFile(currentPath).outputFile(outcome.virtualPath())
                            .durationMs(duration).build());
                    // ── fire-and-forget snapshot (async, non-blocking) ──────────────
                    final String snapInKey  = currentKey;
                    final String snapInPath = currentPath;
                    final long   snapInSize = currentSize;
                    currentKey  = outcome.storageKey();
                    currentPath = outcome.virtualPath();
                    currentSize = outcome.sizeBytes();
                    exec.setCurrentStep(i + 1);
                    exec.setCurrentStorageKey(currentKey);
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(),
                            attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK",
                            snapInKey, currentKey,
                            snapInPath, currentPath,
                            snapInSize, currentSize,
                            duration, null));
                    log.info("[{}] Step {}/{} ({}) completed in {}ms — key={}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(),
                            duration, abbrev(currentKey));
                    stepSucceeded = true;
                    break;
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    if (attempt < maxRetries && isRetryableStepError(e)) {
                        log.warn("[{}] Step {}/{} ({}) attempt {} failed (retryable): {}",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt + 1, e.getMessage());
                        continue;
                    }
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType()).status("FAILED")
                            .inputFile(currentPath).durationMs(duration)
                            .error(e.getMessage()).build());
                    // ── fire-and-forget failure snapshot ──────────────────────────
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(), "FAILED",
                            currentKey, null,
                            currentPath, null,
                            currentSize, 0L,
                            duration, e.getMessage()));
                    exec.setStatus(FlowExecution.FlowStatus.FAILED);
                    exec.setErrorMessage("Step " + i + " (" + step.getType() + ") failed"
                            + (attempt > 0 ? " after " + (attempt + 1) + " attempts" : "")
                            + ": " + e.getMessage());
                    exec.setStepResults(results);
                    exec.setCompletedAt(Instant.now());
                    executionRepository.save(exec);
                    log.error("[{}] Step {}/{} ({}) FAILED: {}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(), e.getMessage());
                    return;
                }
            }
            // ── termination check between steps (non-blocking poll) ───────────
            FlowExecution fresh = executionRepository.findById(exec.getId()).orElse(null);
            if (fresh != null && fresh.isTerminationRequested()) {
                exec.setStatus(FlowExecution.FlowStatus.CANCELLED);
                exec.setErrorMessage("Terminated by " + fresh.getTerminatedBy() + " after step " + i);
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                log.info("[{}] Flow CANCELLED by {} after step {}", trackId, fresh.getTerminatedBy(), i);
                return;
            }
            if (!stepSucceeded) {
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") exhausted all retries");
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                dispatchFlowEvent("FLOW_FAILED", exec, flow);
                return;
            }
        }

        exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
        exec.setCurrentStorageKey(currentKey);
        exec.setStepResults(results);
        exec.setCompletedAt(Instant.now());
        executionRepository.save(exec);
        dispatchFlowEvent("FLOW_COMPLETED", exec, flow);
        log.info("[{}] Flow '{}' completed (VIRTUAL) for '{}' — final key={}",
                trackId, flow.getName(), filename, abbrev(currentKey));
    }

    /** Carries the new storage key + virtual path after a VIRTUAL-mode step completes. */
    private record StepOutcome(String storageKey, String virtualPath, long sizeBytes) {}

    private StepOutcome processStepRef(FileFlow.FlowStep step, String storageKey,
                                        String virtualPath, long sizeBytes, FileRef origin,
                                        String trackId, int stepIndex) throws Exception {
        Map<String, String> cfg = step.getConfig() != null ? step.getConfig() : Map.of();
        return switch (step.getType().toUpperCase()) {
            case "COMPRESS_GZIP"   -> refCompressGzip(storageKey, virtualPath, origin, trackId);
            case "DECOMPRESS_GZIP" -> refDecompressGzip(storageKey, virtualPath, origin, trackId);
            case "COMPRESS_ZIP"    -> refCompressZip(storageKey, virtualPath, origin, trackId);
            case "DECOMPRESS_ZIP"  -> refDecompressZip(storageKey, virtualPath, origin, trackId);
            case "ENCRYPT_AES", "DECRYPT_AES",
                 "ENCRYPT_PGP", "DECRYPT_PGP" ->
                    refEncryptDecrypt(storageKey, virtualPath, origin, trackId, step.getType(), cfg);
            case "RENAME"          -> refRename(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "SCREEN"          -> refScreen(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "MAILBOX"         -> refMailbox(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "FILE_DELIVERY"   -> refFileDelivery(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "CONVERT_EDI"     -> refConvertEdi(storageKey, virtualPath, origin, trackId, cfg);
            case "EXECUTE_SCRIPT"  -> throw new UnsupportedOperationException(
                    "EXECUTE_SCRIPT is not supported for VIRTUAL-mode accounts");
            case "ROUTE"           -> new StepOutcome(storageKey, virtualPath, sizeBytes);
            case "APPROVE"         -> new StepOutcome(storageKey, virtualPath, sizeBytes); // handled above loop; pass-through if reached
            default -> {
                // Try FlowFunctionRegistry for custom/plugin functions (physical-mode fallback for VIRTUAL accounts).
                // Plugin receives a temporary materialized copy; result is stored back into storage-manager.
                if (functionRegistry != null) {
                    Optional<FlowFunction> fn = functionRegistry.get(step.getType());
                    if (fn.isPresent()) {
                        log.info("[{}] Executing plugin function (VIRTUAL): {} (step {})",
                                trackId, step.getType(), stepIndex);
                        // Materialize bytes from storage-manager to a temp file
                        byte[] raw = storageClient.retrieveBySha256(storageKey);
                        Path tempDir = Files.createTempDirectory("flow-plugin-");
                        String filename = VirtualFileSystem.nameOf(virtualPath);
                        Path tempInput = tempDir.resolve(filename);
                        Files.write(tempInput, raw);

                        FlowFunctionContext ctx = new FlowFunctionContext(
                                tempInput, tempDir, cfg, trackId, filename);
                        String outputPathStr = fn.get().executePhysical(ctx);
                        Path outputPath = Paths.get(outputPathStr);

                        // If plugin returned the same file, pass through unchanged
                        if (outputPath.equals(tempInput)) {
                            yield new StepOutcome(storageKey, virtualPath, sizeBytes);
                        }

                        // Store the output back into storage-manager
                        byte[] outputBytes = Files.readAllBytes(outputPath);
                        String newFilename = outputPath.getFileName().toString();
                        String dir = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
                        String newVirtualPath = dir + newFilename;
                        Map<String, Object> stored = storageClient.storeStream(
                                new ByteArrayInputStream(outputBytes), outputBytes.length,
                                newFilename, origin.accountId().toString(), trackId);
                        String newKey  = (String) stored.get("sha256");
                        long   newSize = ((Number) stored.get("sizeBytes")).longValue();
                        vfsBridge.registerRef(origin.accountId(), newVirtualPath,
                                newKey, newSize, trackId, origin.contentType());

                        // Clean up temp files
                        try { Files.deleteIfExists(outputPath); Files.deleteIfExists(tempInput); Files.deleteIfExists(tempDir); }
                        catch (IOException ignored) {}

                        yield new StepOutcome(newKey, newVirtualPath, newSize);
                    }
                }
                throw new IllegalArgumentException("Unknown step type: " + step.getType());
            }
        };
    }

    // ── VIRTUAL step implementations ──────────────────────────────────────────────

    /**
     * COMPRESS_GZIP — read raw bytes → compress in a virtual thread via PipedStream →
     * stream to storage-manager. One read, one write, no intermediate buffer beyond the pipe.
     */
    private StepOutcome refCompressGzip(String storageKey, String virtualPath,
                                         FileRef origin, String trackId) throws IOException {
        byte[] raw = storageClient.retrieveBySha256(storageKey);
        String newPath = virtualPath + ".gz";
        String filename = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("gzip-" + trackId).start(() -> {
            try (GZIPOutputStream gz = new GZIPOutputStream(pipedOut)) {
                gz.write(raw);
            } catch (IOException e) {
                log.warn("[{}] COMPRESS_GZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, "application/gzip");
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * DECOMPRESS_GZIP — wrap compressed bytes in GZIPInputStream in a virtual thread →
     * pipe decompressed stream to storage-manager.
     */
    private StepOutcome refDecompressGzip(String storageKey, String virtualPath,
                                           FileRef origin, String trackId) throws IOException {
        byte[] compressed = storageClient.retrieveBySha256(storageKey);
        String newPath = virtualPath.endsWith(".gz")
                ? virtualPath.substring(0, virtualPath.length() - 3) : virtualPath + ".ungz";
        String filename = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("gunzip-" + trackId).start(() -> {
            try (GZIPInputStream gzIn = new GZIPInputStream(new ByteArrayInputStream(compressed));
                 OutputStream out = pipedOut) {
                gzIn.transferTo(out);
            } catch (IOException e) {
                log.warn("[{}] DECOMPRESS_GZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, origin.contentType());
        return new StepOutcome(newKey, newPath, newSize);
    }

    /** COMPRESS_ZIP — zip raw bytes in a virtual thread → pipe to storage-manager. */
    private StepOutcome refCompressZip(String storageKey, String virtualPath,
                                        FileRef origin, String trackId) throws IOException {
        byte[] raw = storageClient.retrieveBySha256(storageKey);
        String entryName = VirtualFileSystem.nameOf(virtualPath);
        String newPath   = virtualPath + ".zip";
        String filename  = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("zip-" + trackId).start(() -> {
            try (ZipOutputStream zipOut = new ZipOutputStream(pipedOut)) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(raw);
                zipOut.closeEntry();
            } catch (IOException e) {
                log.warn("[{}] COMPRESS_ZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, "application/zip");
        return new StepOutcome(newKey, newPath, newSize);
    }

    /** DECOMPRESS_ZIP — extract first file entry in-memory → store-stream to storage-manager. */
    private StepOutcome refDecompressZip(String storageKey, String virtualPath,
                                          FileRef origin, String trackId) throws IOException {
        byte[] compressed = storageClient.retrieveBySha256(storageKey);
        String newPath = virtualPath.endsWith(".zip")
                ? virtualPath.substring(0, virtualPath.length() - 4) : virtualPath + ".unzip";
        String filename = VirtualFileSystem.nameOf(newPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(compressed))) {
            ZipEntry entry;
            long totalBytes = 0;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zipIn.read(buf)) != -1) {
                        totalBytes += n;
                        if (totalBytes > MAX_ZIP_TOTAL_SIZE)
                            throw new IOException("ZIP exceeds max uncompressed size ("
                                    + MAX_ZIP_TOTAL_SIZE / (1024 * 1024) + " MB)");
                        baos.write(buf, 0, n);
                    }
                    break; // first non-directory entry only
                }
                zipIn.closeEntry();
            }
        }
        byte[] decompressed = baos.toByteArray();
        if (decompressed.length == 0) throw new IOException("ZIP archive was empty");

        Map<String, Object> result = storageClient.storeStream(
                new ByteArrayInputStream(decompressed), decompressed.length,
                filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, origin.contentType());
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * ENCRYPT/DECRYPT — fetch bytes from storage-manager → call encryption-service
     * with Base64 payload (in-memory, no disk) → stream result back to storage-manager.
     * AesService lives in encryption-service, not shared, so we call the REST API.
     */
    private StepOutcome refEncryptDecrypt(String storageKey, String virtualPath, FileRef origin,
                                           String trackId, String stepType,
                                           Map<String, String> cfg) throws Exception {
        String[] parts     = stepType.toUpperCase().split("_"); // e.g. ENCRYPT_AES
        String   operation = parts[0].toLowerCase();            // encrypt | decrypt
        String   keyId     = cfg.get("keyId");
        String   name      = VirtualFileSystem.nameOf(virtualPath);
        String   newName;
        if ("decrypt".equals(operation) && name.endsWith(".enc"))
            newName = name.substring(0, name.length() - 4);
        else if ("encrypt".equals(operation))
            newName = name + ".enc";
        else
            newName = name;
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;

        if (keyId == null || keyId.isBlank()) {
            log.warn("[{}] No keyId for {} step — passing through unchanged", trackId, operation);
            return new StepOutcome(storageKey, newPath, -1);
        }

        byte[] fileBytes    = storageClient.retrieveBySha256(storageKey);
        String base64Input  = java.util.Base64.getEncoder().encodeToString(fileBytes);
        String endpoint     = serviceProps.getEncryptionService().getUrl()
                + "/api/encrypt/" + operation + "/base64?keyId=" + keyId;
        log.info("[{}] {} (VIRTUAL) via encryption-service (keyId={})", trackId, stepType, keyId);

        HttpHeaders encHdr = new HttpHeaders();
        encHdr.setContentType(MediaType.TEXT_PLAIN);
        addInternalAuth(encHdr, "encryption-service");
        String base64Result = restTemplate.postForObject(
                endpoint, new HttpEntity<>(base64Input, encHdr), String.class);

        byte[] resultBytes = java.util.Base64.getDecoder().decode(base64Result);
        Map<String, Object> stored = storageClient.storeStream(
                new ByteArrayInputStream(resultBytes), resultBytes.length,
                newName, origin.accountId().toString(), trackId);
        String newKey  = (String) stored.get("sha256");
        long   newSize = ((Number) stored.get("sizeBytes")).longValue();
        String ct      = "encrypt".equals(operation) ? "application/octet-stream" : origin.contentType();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, ct);
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * RENAME — pure VFS metadata operation. Same storageKey, new virtual path.
     * Zero bytes moved, zero network I/O beyond the single DB write.
     */
    private StepOutcome refRename(String storageKey, String virtualPath, long sizeBytes,
                                   FileRef origin, String trackId,
                                   Map<String, String> cfg) throws IOException {
        String pattern  = cfg.getOrDefault("pattern", "${filename}");
        String name     = VirtualFileSystem.nameOf(virtualPath);
        String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext      = name.contains(".") ? name.substring(name.lastIndexOf('.'))    : "";
        String newName  = pattern
                .replace("${filename}", name).replace("${basename}", baseName)
                .replace("${ext}", ext).replace("${trackid}", trackId)
                .replace("${timestamp}", String.valueOf(System.currentTimeMillis()));
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;
        vfsBridge.registerRef(origin.accountId(), newPath, storageKey, sizeBytes, trackId, origin.contentType());
        log.debug("[{}] RENAME (VIRTUAL): {} → {}", trackId, virtualPath, newPath);
        return new StepOutcome(storageKey, newPath, sizeBytes);
    }

    /**
     * SCREEN — fetch bytes, send to screening-service via ByteArrayResource (no disk).
     * Returns same storageKey (file is unchanged); throws SecurityException on BLOCKED.
     */
    private StepOutcome refScreen(String storageKey, String virtualPath, long sizeBytes,
                                   FileRef origin, String trackId,
                                   Map<String, String> cfg) throws Exception {
        log.info("[{}] SCREEN (VIRTUAL) key={}", trackId, abbrev(storageKey));
        try {
            byte[] fileBytes = storageClient.retrieveBySha256(storageKey);
            final String displayName = VirtualFileSystem.nameOf(virtualPath);
            org.springframework.util.LinkedMultiValueMap<String, Object> body =
                    new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(fileBytes) {
                @Override public String getFilename() { return displayName; }
            });
            HttpHeaders screenHdr = new HttpHeaders();
            screenHdr.setContentType(MediaType.MULTIPART_FORM_DATA);
            addInternalAuth(screenHdr, "screening-service");
            String url = serviceProps.getScreeningService().getUrl()
                    + "/api/v1/screening/scan?trackId=" + trackId;
            if (cfg.containsKey("columns")) url += "&columns=" + cfg.get("columns");
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, screenHdr),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
            if (resp.getBody() != null) {
                String action = (String) resp.getBody().get("actionTaken");
                int hits = resp.getBody().get("hitsFound") instanceof Number n ? n.intValue() : 0;
                log.info("[{}] SCREEN result: {} hits={}", trackId, action, hits);
                if ("BLOCKED".equals(action))
                    throw new SecurityException("SANCTIONS HIT: " + hits + " match(es) found.");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            if ("BLOCK".equals(cfg.getOrDefault("onFailure", "PASS")))
                throw new Exception("Screening unreachable — blocking as configured: " + e.getMessage());
            log.warn("[{}] SCREEN unreachable: {} — allowing (graceful degradation)", trackId, e.getMessage());
        }
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * MAILBOX — zero-copy cross-account delivery.
     * Creates one new VirtualEntry row pointing to the same storageKey. No bytes copied.
     */
    private StepOutcome refMailbox(String storageKey, String virtualPath, long sizeBytes,
                                    FileRef origin, String trackId,
                                    Map<String, String> cfg) throws Exception {
        String destUsername = cfg.get("destinationUsername");
        if (destUsername == null || destUsername.isBlank())
            throw new IllegalArgumentException("MAILBOX step requires 'destinationUsername'");
        Protocol proto;
        try { proto = Protocol.valueOf(cfg.getOrDefault("protocol", "SFTP").toUpperCase()); }
        catch (IllegalArgumentException ignored) { proto = Protocol.SFTP; }
        final Protocol resolvedProtocol = proto;
        TransferAccount dest = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(destUsername, resolvedProtocol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination account not found: " + destUsername + " (" + resolvedProtocol + ")"));
        String filename = VirtualFileSystem.nameOf(virtualPath);
        String destPath = "/outbox/" + filename;
        FileRef src = new FileRef(storageKey, virtualPath, origin.accountId(),
                sizeBytes, trackId, origin.contentType(), "STANDARD");
        vfsBridge.linkToAccount(src, dest.getId(), destPath, trackId);
        log.info("[{}] MAILBOX (VIRTUAL): zero-copy → account={} path={}", trackId, destUsername, destPath);
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * FILE_DELIVERY — fetch bytes from storage-manager → deliver to external endpoints
     * via forwarder-service. No disk I/O; bytes only transit JVM heap during Base64 encoding.
     */
    private StepOutcome refFileDelivery(String storageKey, String virtualPath, long sizeBytes,
                                         FileRef origin, String trackId,
                                         Map<String, String> cfg) throws Exception {
        String endpointIdsStr = cfg.get("deliveryEndpointIds");
        if (endpointIdsStr == null || endpointIdsStr.isBlank())
            throw new IllegalArgumentException("FILE_DELIVERY step requires 'deliveryEndpointIds'");
        List<UUID> ids = Arrays.stream(endpointIdsStr.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        List<DeliveryEndpoint> endpoints = deliveryEndpointRepository.findByIdInAndActiveTrue(ids);
        if (endpoints.isEmpty())
            throw new IllegalArgumentException("No active delivery endpoints for: " + endpointIdsStr);

        byte[] fileBytes = storageClient.retrieveBySha256(storageKey);
        String base64    = Base64.getEncoder().encodeToString(fileBytes);
        String filename  = VirtualFileSystem.nameOf(virtualPath);
        String fwdUrl    = serviceProps.getForwarderService().getUrl();
        int ok = 0; List<String> failures = new ArrayList<>();

        for (DeliveryEndpoint ep : endpoints) {
            try {
                String url = fwdUrl + "/api/forward/deliver/" + ep.getId() + "/base64"
                        + "?filename=" + java.net.URLEncoder.encode(filename, "UTF-8")
                        + "&trackId=" + java.net.URLEncoder.encode(trackId != null ? trackId : "", "UTF-8");
                HttpHeaders fwdHdr = new HttpHeaders();
                fwdHdr.setContentType(MediaType.TEXT_PLAIN);
                addInternalAuth(fwdHdr, "external-forwarder-service");
                restTemplate.postForEntity(url, new HttpEntity<>(base64, fwdHdr), Map.class);
                ok++;
                log.info("[{}] FILE_DELIVERY (VIRTUAL) to '{}' OK", trackId, ep.getName());
            } catch (Exception e) {
                failures.add(ep.getName() + ": " + e.getMessage());
                log.error("[{}] FILE_DELIVERY (VIRTUAL) failed for '{}': {}", trackId, ep.getName(), e.getMessage());
            }
        }
        if (ok == 0)
            throw new RuntimeException("FILE_DELIVERY failed for all " + endpoints.size()
                    + " endpoints: " + String.join("; ", failures));
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * CONVERT_EDI — fetch text content from storage-manager → call EDI converter →
     * stream result back to storage-manager. No disk I/O.
     */
    private StepOutcome refConvertEdi(String storageKey, String virtualPath, FileRef origin,
                                       String trackId, Map<String, String> cfg) throws Exception {
        String targetFormat = cfg.getOrDefault("targetFormat", "JSON");
        String partnerId    = cfg.get("partnerId");

        byte[] raw     = storageClient.retrieveBySha256(storageKey);
        String content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("content", content); body.put("targetFormat", targetFormat);
        if (partnerId != null && !partnerId.isBlank()) body.put("partnerId", partnerId);

        HttpHeaders ediHdr = new HttpHeaders();
        ediHdr.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(ediHdr, "edi-converter");
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                serviceProps.getEdiConverter().getUrl() + "/api/v1/convert/trained",
                HttpMethod.POST, new HttpEntity<>(body, ediHdr),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        String output = resp.getBody() != null ? (String) resp.getBody().get("output") : "";
        String ext    = switch (targetFormat.toUpperCase()) {
            case "JSON" -> ".json"; case "XML" -> ".xml";
            case "CSV"  -> ".csv";  case "YAML" -> ".yaml"; default -> ".txt";
        };
        String baseName = VirtualFileSystem.nameOf(virtualPath);
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        String newName = baseName + ext;
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;

        byte[] outputBytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String ct = switch (targetFormat.toUpperCase()) {
            case "JSON" -> "application/json"; case "XML" -> "application/xml";
            case "CSV"  -> "text/csv";         default    -> "text/plain";
        };
        Map<String, Object> stored = storageClient.storeStream(
                new ByteArrayInputStream(outputBytes), outputBytes.length,
                newName, origin.accountId().toString(), trackId);
        String newKey  = (String) stored.get("sha256");
        long   newSize = ((Number) stored.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, ct);
        log.info("[{}] CONVERT_EDI (VIRTUAL): {} → {} (format={})", trackId, virtualPath, newPath, targetFormat);
        return new StepOutcome(newKey, newPath, newSize);
    }

    private static String abbrev(String key) {
        if (key == null) return "null";
        return key.length() > 8 ? key.substring(0, 8) + "…" : key;
    }

}
