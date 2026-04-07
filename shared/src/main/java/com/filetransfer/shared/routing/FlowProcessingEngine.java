package com.filetransfer.shared.routing;

import com.filetransfer.shared.client.EncryptionServiceClient;
import com.filetransfer.shared.client.ForwarderServiceClient;
import com.filetransfer.shared.client.ScreeningServiceClient;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
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
    private final TrackIdGenerator trackIdGenerator;
    private final TransferAccountRepository accountRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final ClusterService clusterService;
    private final RestTemplate restTemplate;
    private final PlatformConfig platformConfig;
    private final ServiceClientProperties serviceProps;

    /**
     * Execute a specific flow for a file. Creates execution record and processes each step.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename, String inputPath) {
        return executeFlow(flow, trackId, filename, inputPath, null);
    }

    /**
     * Execute a specific flow with matched criteria snapshot for audit trail.
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

        String currentFile = inputPath;
        List<FlowExecution.StepResult> results = new ArrayList<>();

        for (int i = 0; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();
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
                    String outputFile = processStep(step, currentFile, trackId, i);
                    long duration = System.currentTimeMillis() - start;
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status(attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK")
                            .inputFile(currentFile).outputFile(outputFile)
                            .durationMs(duration).build());
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
                    log.error("[{}] Step {}/{} ({}) FAILED: {}", trackId, i + 1, flow.getSteps().size(),
                            step.getType(), e.getMessage());
                    return exec;
                }
            }
            if (!stepSucceeded) {
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") exhausted all retries");
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                return exec;
            }
        }

        exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
        exec.setStepResults(results);
        exec.setCompletedAt(Instant.now());
        executionRepository.save(exec);
        log.info("[{}] Flow '{}' completed successfully for '{}'", trackId, flow.getName(), filename);
        return exec;
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
            case "ROUTE" -> inputPath; // Route is handled by RoutingEngine after flow completes
            default -> throw new IllegalArgumentException("Unknown step type: " + step.getType());
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
            log.warn("Encryption service call failed ({}): {} — passing through", endpoint, e.getMessage());
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
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
            headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());

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
            headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());
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
                headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());
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
        headers.set("X-Internal-Key", platformConfig.getSecurity().getControlApiKey());
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

}
