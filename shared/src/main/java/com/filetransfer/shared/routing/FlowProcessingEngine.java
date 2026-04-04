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

    private String decompressZip(Path input, Path workDir) throws IOException {
        String lastEntry = null;
        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(input))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    Path out = workDir.resolve(entry.getName());
                    Files.createDirectories(out.getParent());
                    try (OutputStream fout = Files.newOutputStream(out)) {
                        zipIn.transferTo(fout);
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
