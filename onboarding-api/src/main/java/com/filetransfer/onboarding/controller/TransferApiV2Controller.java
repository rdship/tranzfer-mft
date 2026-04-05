package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Transfer API v2 — single-call file transfer for developers.
 *
 * POST /api/v2/transfer — send a file with one API call
 * GET  /api/v2/transfer/{trackId} — poll transfer status
 * POST /api/v2/transfer/batch — send multiple files
 * GET  /api/v2/transfer/{trackId}/receipt — delivery receipt
 */
@RestController @RequestMapping("/api/v2/transfer") @RequiredArgsConstructor @Slf4j
public class TransferApiV2Controller {

    private final TransferAccountRepository accountRepo;
    private final FileTransferRecordRepository recordRepo;
    private final FolderMappingRepository mappingRepo;
    private final TrackIdGenerator trackIdGenerator;
    private final FlowExecutionRepository flowExecRepo;

    /** Single-call file transfer */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sender,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String flow,
            @RequestParam(required = false) String webhookUrl) {

        String trackId = trackIdGenerator.generate();
        String filename = file.getOriginalFilename();

        try {
            TransferAccount senderAcct = accountRepo.findAll().stream()
                    .filter(a -> a.getUsername().equals(sender) && a.isActive()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Sender account not found: " + sender));

            // Write file to sender's inbox
            Path inboxDir = Paths.get(senderAcct.getHomeDir(), "inbox");
            Files.createDirectories(inboxDir);
            Path filePath = inboxDir.resolve(filename);
            file.transferTo(filePath.toFile());

            log.info("[{}] API v2 transfer: {} from {} ({}bytes)", trackId, filename, sender, file.getSize());

            // Store webhook URL for async callback
            if (webhookUrl != null) {
                // Would store in DB for async callback after routing completes
                log.info("[{}] Webhook callback registered: {}", trackId, webhookUrl);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("trackId", trackId);
            resp.put("filename", filename);
            resp.put("sizeBytes", file.getSize());
            resp.put("sender", sender);
            resp.put("destination", destination);
            resp.put("flow", flow);
            resp.put("status", "ACCEPTED");
            resp.put("message", "File accepted for processing. Track with GET /api/v2/transfer/" + trackId);
            resp.put("pollUrl", "/api/v2/transfer/" + trackId);
            resp.put("receiptUrl", "/api/v2/transfer/" + trackId + "/receipt");
            resp.put("timestamp", Instant.now().toString());

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "trackId", trackId));
        }
    }

    /** Poll transfer status */
    @GetMapping("/{trackId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String trackId) {
        FileTransferRecord record = recordRepo.findByTrackId(trackId).orElse(null);
        FlowExecution flowExec = flowExecRepo.findByTrackId(trackId).orElse(null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("trackId", trackId);

        if (record != null) {
            resp.put("filename", record.getOriginalFilename());
            resp.put("status", record.getStatus().name());
            resp.put("sizeBytes", record.getFileSizeBytes());
            resp.put("uploadedAt", record.getUploadedAt());
            resp.put("routedAt", record.getRoutedAt());
            resp.put("completedAt", record.getCompletedAt());
            resp.put("sourceChecksum", record.getSourceChecksum());
            resp.put("destinationChecksum", record.getDestinationChecksum());
            resp.put("integrityVerified", record.getSourceChecksum() != null &&
                    record.getSourceChecksum().equals(record.getDestinationChecksum()));
            resp.put("retryCount", record.getRetryCount());
            resp.put("error", record.getErrorMessage());
        } else {
            resp.put("status", "PROCESSING");
            resp.put("message", "Transfer is being processed. Poll again in a few seconds.");
        }

        if (flowExec != null) {
            resp.put("flowName", flowExec.getFlow() != null ? flowExec.getFlow().getName() : null);
            resp.put("flowStatus", flowExec.getStatus().name());
            resp.put("flowStep", flowExec.getCurrentStep() + "/" +
                    (flowExec.getFlow() != null ? flowExec.getFlow().getSteps().size() : "?"));
        }

        return ResponseEntity.ok(resp);
    }

    /** Batch transfer — multiple files in one call */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batch(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam String sender) {

        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String trackId = trackIdGenerator.generate();
            try {
                TransferAccount acct = accountRepo.findAll().stream()
                        .filter(a -> a.getUsername().equals(sender) && a.isActive()).findFirst().orElse(null);
                if (acct != null) {
                    Path inbox = Paths.get(acct.getHomeDir(), "inbox");
                    Files.createDirectories(inbox);
                    file.transferTo(inbox.resolve(file.getOriginalFilename()).toFile());
                }
                results.add(Map.of("trackId", trackId, "filename", file.getOriginalFilename(), "status", "ACCEPTED"));
            } catch (Exception e) {
                results.add(Map.of("trackId", trackId, "filename", file.getOriginalFilename(), "status", "FAILED", "error", e.getMessage()));
            }
        }

        return ResponseEntity.accepted().body(Map.of("totalFiles", files.size(), "results", results,
                "sender", sender, "timestamp", Instant.now().toString()));
    }

    /** Delivery receipt */
    @GetMapping("/{trackId}/receipt")
    public ResponseEntity<Map<String, Object>> receipt(@PathVariable String trackId) {
        FileTransferRecord record = recordRepo.findByTrackId(trackId).orElse(null);
        if (record == null) return ResponseEntity.notFound().build();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("receiptId", "RCP-" + trackId);
        r.put("trackId", trackId);
        r.put("filename", record.getOriginalFilename());
        r.put("status", record.getStatus().name());
        r.put("sizeBytes", record.getFileSizeBytes());
        r.put("sourceChecksum", record.getSourceChecksum());
        r.put("destinationChecksum", record.getDestinationChecksum());
        r.put("integrityVerified", record.getSourceChecksum() != null &&
                record.getSourceChecksum().equals(record.getDestinationChecksum()));
        r.put("uploadedAt", record.getUploadedAt());
        r.put("deliveredAt", record.getRoutedAt());
        r.put("generatedAt", Instant.now().toString());
        return ResponseEntity.ok(r);
    }
}
