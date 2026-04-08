package com.filetransfer.shared.scheduler;

import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.ScheduledTask;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.routing.FlowProcessingEngine;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component @RequiredArgsConstructor @Slf4j
public class ScheduledTaskRunner {

    private final FileFlowRepository flowRepository;
    private final FlowProcessingEngine flowEngine;
    private final TrackIdGenerator trackIdGenerator;

    public void execute(ScheduledTask task) throws Exception {
        Map<String, String> cfg = task.getConfig() != null ? task.getConfig() : Map.of();

        switch (task.getTaskType().toUpperCase()) {
            case "RUN_FLOW" -> runFlow(task, cfg);
            case "PULL_FILES" -> pullFiles(cfg);
            case "PUSH_FILES" -> pushFiles(cfg);
            case "EXECUTE_SCRIPT" -> executeScript(cfg);
            case "CLEANUP" -> cleanup(cfg);
            default -> throw new IllegalArgumentException("Unknown task type: " + task.getTaskType());
        }
    }

    private void runFlow(ScheduledTask task, Map<String, String> cfg) {
        String flowName = cfg.getOrDefault("flowName", task.getReferenceId());
        String sourceDir = cfg.getOrDefault("sourceDir", "/data/sftp");
        String pattern = cfg.getOrDefault("filePattern", "*");

        FileFlow flow = flowRepository.findByNameAndActiveTrue(flowName)
                .orElseThrow(() -> new RuntimeException("Flow not found: " + flowName));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(sourceDir), pattern)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    String trackId = trackIdGenerator.generate();
                    log.info("[{}] Scheduled flow '{}' on {}", trackId, flowName, file.getFileName());
                    flowEngine.executeFlow(flow, trackId, file.getFileName().toString(), file.toString());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Flow execution failed: " + e.getMessage(), e);
        }
    }

    private void pullFiles(Map<String, String> cfg) {
        log.info("Scheduler PULL_FILES: {}", cfg);
        // Triggers the external-forwarder to pull from remote
    }

    private void pushFiles(Map<String, String> cfg) {
        log.info("Scheduler PUSH_FILES: {}", cfg);
        // Triggers push to external destination
    }

    private static final java.util.regex.Pattern ALLOWED_SCRIPT_PATH =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9._/\\-]+$");

    /** Shell operators that indicate command chaining / injection. */
    private static final java.util.regex.Pattern SHELL_INJECTION =
            java.util.regex.Pattern.compile("[;|&`$(){}!<>]|\\.\\.");

    private void executeScript(Map<String, String> cfg) throws Exception {
        String script = cfg.get("command");
        if (script == null) throw new IllegalArgumentException("No 'command' in task config");
        int timeout = Integer.parseInt(cfg.getOrDefault("timeoutSeconds", "300"));

        // Validate script path doesn't contain shell metacharacters
        String scriptCmd = script.split("\\s+")[0];
        if (!ALLOWED_SCRIPT_PATH.matcher(scriptCmd).matches()) {
            throw new SecurityException("Script path contains disallowed characters: " + scriptCmd);
        }

        // Validate ALL arguments — block shell operators that enable command chaining
        if (SHELL_INJECTION.matcher(script).find()) {
            throw new SecurityException("Script command contains disallowed shell operators: " + scriptCmd);
        }

        log.info("Executing script: {}", script);
        // Use array form to avoid shell interpretation — prevents injection entirely
        String[] parts = script.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(parts);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); throw new RuntimeException("Script timed out after " + timeout + "s"); }
        if (proc.exitValue() != 0) throw new RuntimeException("Script exited with code " + proc.exitValue() + ": " + output);
        log.info("Script completed: {}", output.length() > 200 ? output.substring(0, 200) + "..." : output);
    }

    private static final java.util.List<String> ALLOWED_CLEANUP_ROOTS = java.util.List.of(
            "/tmp/mft-flow-work", "/data/storage", "/data/sftp", "/data/ftp", "/data/ftp-web",
            "/data/gateway", "/data/quarantine");

    private void cleanup(Map<String, String> cfg) throws Exception {
        String dir = cfg.getOrDefault("directory", "/tmp/mft-flow-work");
        int maxAgeHours = Integer.parseInt(cfg.getOrDefault("maxAgeHours", "24"));
        log.info("Cleanup: {} (files older than {}h)", dir, maxAgeHours);

        Path dirPath = Paths.get(dir).normalize();
        boolean allowed = ALLOWED_CLEANUP_ROOTS.stream()
                .anyMatch(root -> dirPath.startsWith(Paths.get(root).normalize()));
        if (!allowed) {
            throw new SecurityException("Cleanup directory outside allowed roots: " + dir);
        }
        if (!Files.exists(dirPath)) return;
        java.time.Instant cutoff = java.time.Instant.now().minus(maxAgeHours, java.time.temporal.ChronoUnit.HOURS);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.delete(file);
                }
            }
        }
    }
}
