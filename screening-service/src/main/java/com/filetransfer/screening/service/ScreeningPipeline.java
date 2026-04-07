package com.filetransfer.screening.service;

import com.filetransfer.screening.entity.ScreeningResult;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Unified screening pipeline that orchestrates all security scans in order:
 *
 * <ol>
 *   <li><b>Antivirus</b> (ClamAV) — blocks malware first</li>
 *   <li><b>DLP</b> (Data Loss Prevention) — detects sensitive data</li>
 *   <li><b>Sanctions</b> (OFAC/EU/UN) — screens file content against watchlists</li>
 * </ol>
 *
 * <p>Pipeline is fail-fast: if any stage returns BLOCK, subsequent stages are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreeningPipeline {

    private final AntivirusEngine antivirusEngine;
    private final DlpEngine dlpEngine;
    private final ScreeningEngine sanctionsEngine;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchangeName;

    /**
     * Run the full screening pipeline on a file.
     *
     * @param filePath         path to the file
     * @param trackId          tracking ID
     * @param accountUsername   account that uploaded the file
     * @param columnsToScreen  columns to screen for sanctions (CSV files)
     * @return complete pipeline result
     */
    public PipelineResult screenFile(Path filePath, String trackId,
                                      String accountUsername,
                                      List<String> columnsToScreen) {
        long start = System.currentTimeMillis();
        String filename = filePath.getFileName().toString();
        log.info("[{}] Screening pipeline started: {}", trackId, filename);

        PipelineResult.PipelineResultBuilder resultBuilder = PipelineResult.builder()
                .trackId(trackId)
                .filename(filename);

        // === Stage 1: Antivirus Scan ===
        AntivirusEngine.AvScanResult avResult = antivirusEngine.scanFile(
                filePath, trackId, accountUsername);
        resultBuilder.avResult(avResult);

        if (!avResult.isClean()) {
            log.warn("[{}] BLOCKED by AV: {}", trackId, avResult.getReason());

            // Publish malware detection event
            publishEvent("file.malware.detected", trackId, filename,
                    accountUsername, avResult.getReason());

            resultBuilder
                    .action("BLOCKED")
                    .blockReason(avResult.getReason())
                    .blockedByStage("ANTIVIRUS")
                    .durationMs(System.currentTimeMillis() - start);
            return resultBuilder.build();
        }

        // === Stage 2: DLP Scan ===
        DlpEngine.DlpScanResult dlpResult = dlpEngine.scanFile(filePath, trackId);
        resultBuilder.dlpResult(dlpResult);

        if ("BLOCK".equals(dlpResult.getAction())) {
            String reason = "Sensitive data detected: " + dlpResult.getFindings().size()
                    + " finding(s) — " + dlpResult.getFindings().stream()
                    .map(DlpEngine.DlpFinding::getType).distinct()
                    .reduce((a, b) -> a + ", " + b).orElse("unknown");

            log.warn("[{}] BLOCKED by DLP: {}", trackId, reason);

            publishEvent("file.dlp.blocked", trackId, filename, accountUsername, reason);

            resultBuilder
                    .action("BLOCKED")
                    .blockReason(reason)
                    .blockedByStage("DLP")
                    .durationMs(System.currentTimeMillis() - start);
            return resultBuilder.build();
        }

        if ("FLAG".equals(dlpResult.getAction())) {
            log.info("[{}] FLAGGED by DLP: {} findings", trackId, dlpResult.getFindings().size());
            publishEvent("file.dlp.flagged", trackId, filename, accountUsername,
                    dlpResult.getFindings().size() + " DLP findings");
        }

        // === Stage 3: Sanctions Screening ===
        ScreeningResult sanctionsResult = sanctionsEngine.screenFile(
                filePath, trackId, accountUsername, columnsToScreen);
        resultBuilder.sanctionsResult(sanctionsResult);

        if ("BLOCKED".equals(sanctionsResult.getActionTaken())) {
            String reason = "Sanctions match: " + sanctionsResult.getHitsFound()
                    + " hit(s) — " + sanctionsResult.getOutcome();

            log.warn("[{}] BLOCKED by sanctions: {}", trackId, reason);

            publishEvent("file.sanctions.blocked", trackId, filename, accountUsername, reason);

            resultBuilder
                    .action("BLOCKED")
                    .blockReason(reason)
                    .blockedByStage("SANCTIONS")
                    .durationMs(System.currentTimeMillis() - start);
            return resultBuilder.build();
        }

        // All clear (or DLP was FLAG/LOG which allows pass-through)
        String action = "FLAG".equals(dlpResult.getAction()) ? "FLAGGED"
                : "FLAGGED".equals(sanctionsResult.getActionTaken()) ? "FLAGGED"
                : "PASSED";

        long durationMs = System.currentTimeMillis() - start;
        log.info("[{}] Screening pipeline complete: {} — {} ({}ms)",
                trackId, filename, action, durationMs);

        resultBuilder
                .action(action)
                .durationMs(durationMs);
        return resultBuilder.build();
    }

    private void publishEvent(String routingKey, String trackId, String filename,
                               String account, String detail) {
        try {
            var event = java.util.Map.of(
                    "trackId", trackId != null ? trackId : "",
                    "filename", filename != null ? filename : "",
                    "account", account != null ? account : "",
                    "detail", detail != null ? detail : "",
                    "timestamp", java.time.Instant.now().toString()
            );
            rabbitTemplate.convertAndSend(exchangeName, routingKey, event);
            log.debug("[{}] Published event: {}", trackId, routingKey);
        } catch (Exception e) {
            log.warn("[{}] Failed to publish event {}: {}", trackId, routingKey, e.getMessage());
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PipelineResult {
        private String trackId;
        private String filename;
        /** PASSED, FLAGGED, BLOCKED */
        private String action;
        /** If blocked, which stage blocked it: ANTIVIRUS, DLP, SANCTIONS */
        private String blockedByStage;
        /** Human-readable block reason */
        private String blockReason;
        private long durationMs;

        private AntivirusEngine.AvScanResult avResult;
        private DlpEngine.DlpScanResult dlpResult;
        private ScreeningResult sanctionsResult;
    }
}
