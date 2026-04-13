package com.filetransfer.screening.service;

import com.filetransfer.screening.client.ClamAvClient;
import com.filetransfer.shared.entity.security.QuarantineRecord;
import com.filetransfer.shared.repository.QuarantineRecordRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Antivirus scanning engine using ClamAV.
 *
 * <p>Security model: <b>fail-closed</b> — if ClamAV is unreachable or returns
 * an error, the file is BLOCKED. Malware must be proven absent before a file
 * proceeds through the screening pipeline.
 *
 * <p>On malware detection, the file is moved to quarantine storage
 * and a QuarantineRecord is persisted for admin review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntivirusEngine {

    private final ClamAvClient clamAvClient;
    private final QuarantineRecordRepository quarantineRepository;

    @Value("${screening.quarantine.path:/data/quarantine}")
    private String quarantinePath;

    /**
     * Scan a file for malware.
     *
     * @param filePath         path to the file to scan
     * @param trackId          tracking ID for the file transfer
     * @param accountUsername   account that uploaded the file
     * @return AV scan result with clean status and quarantine info
     */
    public AvScanResult scanFile(Path filePath, String trackId, String accountUsername) {
        long start = System.currentTimeMillis();
        String filename = filePath.getFileName().toString();

        log.info("[{}] AV scan started: {}", trackId, filename);

        ClamAvClient.ScanResult clamResult = clamAvClient.scanFile(filePath);
        long scanTimeMs = System.currentTimeMillis() - start;

        if (clamResult.isClean()) {
            log.info("[{}] AV scan CLEAN: {} ({}ms)", trackId, filename, scanTimeMs);
            return AvScanResult.builder()
                    .clean(true)
                    .scanTimeMs(scanTimeMs)
                    .build();
        }

        // Malware detected or ClamAV unreachable — quarantine the file
        String virusName = clamResult.getVirusName();
        String reason = virusName != null
                ? "Malware detected: " + virusName
                : "AV scan failed (fail-closed): " + clamResult.getError();

        log.warn("[{}] AV scan THREAT: {} — {} ({}ms)", trackId, filename, reason, scanTimeMs);

        // Quarantine the file
        QuarantineRecord record = quarantineFile(filePath, trackId, accountUsername,
                reason, virusName);

        return AvScanResult.builder()
                .clean(false)
                .virusName(virusName)
                .reason(reason)
                .scanTimeMs(scanTimeMs)
                .quarantineId(record.getId().toString())
                .error(clamResult.getError())
                .build();
    }

    /**
     * Move file to quarantine storage and create a quarantine record.
     */
    private QuarantineRecord quarantineFile(Path filePath, String trackId,
                                             String accountUsername, String reason,
                                             String detectedThreat) {
        String filename = filePath.getFileName().toString();
        String sha256 = null;
        long fileSize = 0;

        try {
            fileSize = Files.size(filePath);
            sha256 = computeSha256(filePath);
        } catch (Exception ignored) {}

        // Move to quarantine directory
        Path quarantineDir = Paths.get(quarantinePath, trackId != null ? trackId : "unknown");
        String quarantineFilePath = quarantineDir.resolve(filename).toString();

        try {
            Files.createDirectories(quarantineDir);
            Files.move(filePath, quarantineDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            log.info("[{}] File quarantined: {} -> {}", trackId, filename, quarantineFilePath);
        } catch (IOException e) {
            // If move fails, try copy + delete
            try {
                Files.copy(filePath, quarantineDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(filePath);
            } catch (IOException e2) {
                log.error("[{}] Failed to quarantine file {}: {}", trackId, filename, e2.getMessage());
                quarantineFilePath = filePath.toString(); // Keep original path if quarantine fails
            }
        }

        QuarantineRecord record = QuarantineRecord.builder()
                .trackId(trackId)
                .filename(filename)
                .accountUsername(accountUsername)
                .originalPath(filePath.toString())
                .quarantinePath(quarantineFilePath)
                .reason(reason)
                .detectedThreat(detectedThreat)
                .detectionSource("AV")
                .status("QUARANTINED")
                .fileSizeBytes(fileSize)
                .sha256(sha256)
                .build();

        return quarantineRepository.save(record);
    }

    /**
     * Re-scan a quarantined file (used when releasing from quarantine).
     */
    public ClamAvClient.ScanResult rescan(Path filePath) {
        return clamAvClient.scanFile(filePath);
    }

    private String computeSha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AvScanResult {
        private boolean clean;
        private String virusName;
        private String reason;
        private long scanTimeMs;
        private String quarantineId;
        private String error;
    }
}
