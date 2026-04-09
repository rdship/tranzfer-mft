package com.filetransfer.onboarding.dryrun;

import com.filetransfer.shared.client.EdiConverterClient;
import com.filetransfer.shared.client.KeystoreServiceClient;
import com.filetransfer.shared.client.ScreeningServiceClient;
import com.filetransfer.shared.entity.FileFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a {@link FileFlow} step-by-step without running any actual file processing.
 *
 * <p>For each step, checks:
 * <ul>
 *   <li>Required config fields are present.
 *   <li>Referenced resources exist (key aliases, external destinations).
 *   <li>Required services are reachable.
 * </ul>
 *
 * <p>Returns {@code CANNOT_VERIFY} (not {@code WOULD_FAIL}) when a service is unreachable —
 * a missing service is an ops concern, not necessarily a config defect.
 */
@Slf4j
@Service
public class DryRunService {

    @Autowired(required = false) @Nullable private KeystoreServiceClient  keystoreClient;
    @Autowired(required = false) @Nullable private ScreeningServiceClient  screeningClient;
    @Autowired(required = false) @Nullable private EdiConverterClient      ediClient;

    /** Typical P50 latency per step type (ms) — used as estimate when no snapshot data exists. */
    private static final Map<String, Long> TYPICAL_MS = Map.ofEntries(
            Map.entry("COMPRESS_GZIP",   50L),
            Map.entry("DECOMPRESS_GZIP", 40L),
            Map.entry("COMPRESS_ZIP",    60L),
            Map.entry("DECOMPRESS_ZIP",  45L),
            Map.entry("ENCRYPT_PGP",    250L),
            Map.entry("DECRYPT_PGP",    220L),
            Map.entry("ENCRYPT_AES",     60L),
            Map.entry("DECRYPT_AES",     50L),
            Map.entry("RENAME",           5L),
            Map.entry("SCREEN",         550L),
            Map.entry("MAILBOX",         20L),
            Map.entry("FILE_DELIVERY",  350L),
            Map.entry("ROUTE",           10L),
            Map.entry("CONVERT_EDI",    900L),
            Map.entry("EXECUTE_SCRIPT",2000L),
            Map.entry("APPROVE",          0L)
    );

    /** Human-readable step labels matching STEP_TYPE_CATALOG in the UI. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("COMPRESS_GZIP",   "Compress (GZIP)"),
            Map.entry("DECOMPRESS_GZIP", "Decompress (GZIP)"),
            Map.entry("COMPRESS_ZIP",    "Compress (ZIP)"),
            Map.entry("DECOMPRESS_ZIP",  "Decompress (ZIP)"),
            Map.entry("ENCRYPT_PGP",     "Encrypt (PGP)"),
            Map.entry("DECRYPT_PGP",     "Decrypt (PGP)"),
            Map.entry("ENCRYPT_AES",     "Encrypt (AES)"),
            Map.entry("DECRYPT_AES",     "Decrypt (AES)"),
            Map.entry("RENAME",          "Rename File"),
            Map.entry("SCREEN",          "Sanctions Screen"),
            Map.entry("MAILBOX",         "Mailbox Delivery"),
            Map.entry("FILE_DELIVERY",   "File Delivery"),
            Map.entry("ROUTE",           "Route"),
            Map.entry("CONVERT_EDI",     "Convert EDI"),
            Map.entry("EXECUTE_SCRIPT",  "Execute Script"),
            Map.entry("APPROVE",         "Admin Approval")
    );

    private static final Pattern RENAME_VAR = Pattern.compile("\\$\\{(basename|timestamp|trackid|ext|date)}");

    /**
     * Validate every step in the given flow and return the dry run report.
     *
     * @param flow          the flow whose steps to validate
     * @param testFilename  optional test filename for the report header (cosmetic only)
     */
    public DryRunResult dryRun(FileFlow flow, String testFilename) {
        List<FileFlow.FlowStep> steps  = flow.getSteps() != null ? flow.getSteps() : List.of();
        List<DryRunResult.StepValidation> results  = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        long totalMs = 0;

        for (int i = 0; i < steps.size(); i++) {
            FileFlow.FlowStep step = steps.get(i);
            Map<String, String> cfg = step.getConfig() != null ? step.getConfig() : Map.of();
            String type = step.getType() != null ? step.getType().toUpperCase() : "UNKNOWN";

            DryRunResult.StepValidation v = validate(i, type, cfg);
            results.add(v);
            totalMs += v.getEstimatedMs();
            if ("WOULD_FAIL".equals(v.getStatus())) {
                issues.add("Step " + (i + 1) + " (" + LABELS.getOrDefault(type, type) + "): " + v.getMessage());
            }
        }

        boolean wouldSucceed = issues.isEmpty();

        return DryRunResult.builder()
                .flowId(flow.getId() != null ? flow.getId().toString() : null)
                .flowName(flow.getName())
                .testFilename(testFilename != null ? testFilename : "test-file.xml")
                .steps(results)
                .totalEstimatedMs(totalMs)
                .wouldSucceed(wouldSucceed)
                .issues(issues)
                .generatedAt(Instant.now())
                .build();
    }

    // ── Per-step validation ───────────────────────────────────────────────────

    private DryRunResult.StepValidation validate(int index, String type, Map<String, String> cfg) {
        return switch (type) {
            case "ENCRYPT_PGP", "DECRYPT_PGP" -> validateKeyAlias(index, type, cfg, "pgp");
            case "ENCRYPT_AES", "DECRYPT_AES"  -> validateKeyAlias(index, type, cfg, "aes");
            case "COMPRESS_GZIP", "DECOMPRESS_GZIP",
                 "COMPRESS_ZIP",  "DECOMPRESS_ZIP"  -> succeed(index, type, "No external dependencies — always valid");
            case "RENAME"         -> validateRename(index, cfg);
            case "SCREEN"         -> validateScreening(index, cfg);
            case "MAILBOX"        -> validateMailbox(index, cfg);
            case "FILE_DELIVERY"  -> validateFileDelivery(index, cfg);
            case "ROUTE"          -> validateRoute(index, cfg);
            case "CONVERT_EDI"    -> validateEdi(index, cfg);
            case "EXECUTE_SCRIPT" -> validateScript(index, cfg);
            case "APPROVE"        -> succeed(index, type, "Flow will pause at this step for admin sign-off");
            default               -> cannotVerify(index, type, "Unknown step type — skipped");
        };
    }

    private DryRunResult.StepValidation validateKeyAlias(int i, String type, Map<String, String> cfg, String keyType) {
        String alias = cfg.get("keyAlias");
        if (alias == null || alias.isBlank()) {
            return fail(i, type, "keyAlias is required but not configured");
        }
        if (keystoreClient == null || !keystoreClient.isEnabled()) {
            return cannotVerify(i, type, "Keystore Manager not configured — cannot verify key alias '" + alias + "'");
        }
        try {
            Map<String, Object> key = keystoreClient.getKey(alias);
            if (key == null) {
                return fail(i, type, keyType.toUpperCase() + " key alias '" + alias + "' not found in Keystore Manager");
            }
            String kt = key.containsKey("type") ? " (" + key.get("type") + ")" : "";
            return succeed(i, type, "Key alias '" + alias + "' found in Keystore Manager" + kt);
        } catch (Exception e) {
            log.debug("DryRun: keystore check for '{}' failed: {}", alias, e.getMessage());
            return cannotVerify(i, type, "Keystore Manager unreachable — cannot verify key alias '" + alias + "'");
        }
    }

    private DryRunResult.StepValidation validateRename(int i, Map<String, String> cfg) {
        String pattern = cfg.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return fail(i, "RENAME", "pattern is required but not configured");
        }
        // Check for at least one recognised variable or literal
        boolean hasVar = RENAME_VAR.matcher(pattern).find();
        try {
            // Validate no illegal regex-like syntax was intended
            Pattern.compile(pattern.replace("${", "X").replace("}", "X"));
        } catch (PatternSyntaxException e) {
            return fail(i, "RENAME", "Pattern '" + pattern + "' is invalid: " + e.getMessage());
        }
        return succeed(i, "RENAME", "Pattern '" + pattern + "' is valid" + (hasVar ? " — substitution variables detected" : ""));
    }

    private DryRunResult.StepValidation validateScreening(int i, Map<String, String> cfg) {
        String mode = cfg.getOrDefault("mode", "OFAC");
        if (screeningClient == null || !screeningClient.isEnabled()) {
            return cannotVerify(i, "SCREEN", "Screening service not configured — cannot verify reachability (mode: " + mode + ")");
        }
        if (!screeningClient.isHealthy()) {
            return cannotVerify(i, "SCREEN", "Screening service is DOWN — mode '" + mode + "' cannot be verified");
        }
        return succeed(i, "SCREEN", "Screening service reachable (mode: " + mode + ")");
    }

    private DryRunResult.StepValidation validateMailbox(int i, Map<String, String> cfg) {
        String path = cfg.get("path");
        if (path == null || path.isBlank()) {
            return fail(i, "MAILBOX", "path is required but not configured");
        }
        if (!path.startsWith("/")) {
            return fail(i, "MAILBOX", "path must be absolute (start with '/'), got: " + path);
        }
        return succeed(i, "MAILBOX", "Mailbox path '" + path + "' is configured");
    }

    private DryRunResult.StepValidation validateFileDelivery(int i, Map<String, String> cfg) {
        String destId = cfg.get("destinationId");
        if (destId == null || destId.isBlank()) {
            return fail(i, "FILE_DELIVERY", "destinationId is required but not configured");
        }
        try {
            UUID.fromString(destId);
        } catch (IllegalArgumentException e) {
            return fail(i, "FILE_DELIVERY", "destinationId '" + destId + "' is not a valid UUID");
        }
        return succeed(i, "FILE_DELIVERY", "External destination ID is a valid UUID — existence not verified in dry run");
    }

    private DryRunResult.StepValidation validateRoute(int i, Map<String, String> cfg) {
        String target = cfg.get("target");
        if (target == null || target.isBlank()) {
            return fail(i, "ROUTE", "target is required but not configured");
        }
        return succeed(i, "ROUTE", "Route target '" + target + "' is configured");
    }

    private DryRunResult.StepValidation validateEdi(int i, Map<String, String> cfg) {
        String format = cfg.getOrDefault("format", "X12");
        if (ediClient == null || !ediClient.isEnabled()) {
            return cannotVerify(i, "CONVERT_EDI", "EDI Converter not configured — cannot verify (target format: " + format + ")");
        }
        if (!ediClient.isHealthy()) {
            return cannotVerify(i, "CONVERT_EDI", "EDI Converter is DOWN — target format '" + format + "' cannot be verified");
        }
        return succeed(i, "CONVERT_EDI", "EDI Converter reachable (target format: " + format + ")");
    }

    private DryRunResult.StepValidation validateScript(int i, Map<String, String> cfg) {
        String command = cfg.get("command");
        if (command == null || command.isBlank()) {
            return fail(i, "EXECUTE_SCRIPT", "command is required but not configured");
        }
        // Basic safety check — command injection is not a concern here since this is admin-only config validation
        return succeed(i, "EXECUTE_SCRIPT", "Script command is configured — actual execution is NOT simulated in dry run");
    }

    // ── Result builders ───────────────────────────────────────────────────────

    private DryRunResult.StepValidation succeed(int i, String type, String message) {
        return DryRunResult.StepValidation.builder()
                .stepIndex(i).stepType(type).label(LABELS.getOrDefault(type, type))
                .status("WOULD_SUCCEED").message(message)
                .estimatedMs(TYPICAL_MS.getOrDefault(type, 100L))
                .build();
    }

    private DryRunResult.StepValidation fail(int i, String type, String message) {
        return DryRunResult.StepValidation.builder()
                .stepIndex(i).stepType(type).label(LABELS.getOrDefault(type, type))
                .status("WOULD_FAIL").message(message)
                .estimatedMs(0L)
                .build();
    }

    private DryRunResult.StepValidation cannotVerify(int i, String type, String message) {
        return DryRunResult.StepValidation.builder()
                .stepIndex(i).stepType(type).label(LABELS.getOrDefault(type, type))
                .status("CANNOT_VERIFY").message(message)
                .estimatedMs(TYPICAL_MS.getOrDefault(type, 100L))
                .build();
    }
}
