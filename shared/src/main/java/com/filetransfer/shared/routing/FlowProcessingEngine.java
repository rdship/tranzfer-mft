package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Called when a file arrives. Finds matching flows and executes them.
     * Returns the track IDs of all triggered flows.
     */
    @Async
    public void onFileArrived(TransferAccount account, String filename, String absolutePath) {
        List<FileFlow> flows = flowRepository.findMatchingFlows(account);

        for (FileFlow flow : flows) {
            if (matchesFlow(flow, filename, absolutePath)) {
                String trackId = trackIdGenerator.generate();
                log.info("[{}] Flow '{}' matched file '{}'", trackId, flow.getName(), filename);
                executeFlow(flow, trackId, filename, absolutePath);
            }
        }
    }

    /**
     * Execute a specific flow for a file. Creates execution record and processes each step.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename, String inputPath) {
        FlowExecution exec = FlowExecution.builder()
                .trackId(trackId)
                .flow(flow)
                .originalFilename(filename)
                .currentFilePath(inputPath)
                .status(FlowExecution.FlowStatus.PROCESSING)
                .stepResults(new ArrayList<>())
                .build();
        exec = executionRepository.save(exec);

        String currentFile = inputPath;
        List<FlowExecution.StepResult> results = new ArrayList<>();

        for (int i = 0; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            long start = System.currentTimeMillis();
            try {
                String outputFile = processStep(step, currentFile, trackId, i);
                long duration = System.currentTimeMillis() - start;
                results.add(FlowExecution.StepResult.builder()
                        .stepIndex(i).stepType(step.getType())
                        .status("OK").inputFile(currentFile).outputFile(outputFile)
                        .durationMs(duration).build());
                currentFile = outputFile;
                exec.setCurrentStep(i + 1);
                exec.setCurrentFilePath(currentFile);
                log.info("[{}] Step {}/{} ({}) completed in {}ms",
                        trackId, i + 1, flow.getSteps().size(), step.getType(), duration);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                results.add(FlowExecution.StepResult.builder()
                        .stepIndex(i).stepType(step.getType())
                        .status("FAILED").inputFile(currentFile)
                        .durationMs(duration).error(e.getMessage()).build());
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") failed: " + e.getMessage());
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                log.error("[{}] Step {}/{} ({}) FAILED: {}", trackId, i + 1, flow.getSteps().size(), step.getType(), e.getMessage());
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
        String encryptionUrl = System.getenv("ENCRYPTION_SERVICE_URL");
        if (encryptionUrl == null) encryptionUrl = "http://encryption-service:8086";

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
            org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();
            String base64Result = rest.postForObject(endpoint, entity, String.class);

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
        String screeningUrl = System.getenv("SCREENING_SERVICE_URL");
        if (screeningUrl == null) screeningUrl = "http://screening-service:8092";

        log.info("[{}] Screening file against sanctions lists...", trackId);

        try {
            // Call screening service with multipart file upload
            org.springframework.core.io.FileSystemResource fileResource =
                    new org.springframework.core.io.FileSystemResource(input.toFile());
            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", fileResource);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);

            String url = screeningUrl + "/api/v1/screening/scan?trackId=" + trackId;
            if (cfg.containsKey("columns")) url += "&columns=" + cfg.get("columns");

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(body, headers);
            org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();

            org.springframework.http.ResponseEntity<java.util.Map> response =
                    rest.postForEntity(url, request, java.util.Map.class);

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

    private boolean matchesFlow(FileFlow flow, String filename, String path) {
        // Check filename pattern
        if (flow.getFilenamePattern() != null && !flow.getFilenamePattern().isBlank()) {
            if (!Pattern.matches(flow.getFilenamePattern(), filename)) return false;
        }
        // Check source path
        if (flow.getSourcePath() != null && !flow.getSourcePath().isBlank()) {
            if (!path.contains(flow.getSourcePath())) return false;
        }
        return true;
    }
}
