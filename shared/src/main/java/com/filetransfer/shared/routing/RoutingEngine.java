package com.filetransfer.shared.routing;

import com.filetransfer.shared.cluster.ClusterContext;
import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Shared routing engine. All services autowire this bean.
 * Handles: upload routing, cross-cluster forwarding, download lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingEngine {

    private final RoutingEvaluator evaluator;
    private final FileTransferRecordRepository recordRepository;
    private final ClusterContext clusterContext;
    private final RestTemplate restTemplate;

    /**
     * Called by each service when a file upload completes.
     *
     * @param sourceAccount      the account that uploaded
     * @param relativeFilePath   path relative to user home, e.g. "/inbox/file.csv"
     * @param absoluteSourcePath absolute on-disk path of the uploaded file
     */
    @Async
    public void onFileUploaded(TransferAccount sourceAccount, String relativeFilePath, String absoluteSourcePath) {
        log.info("Routing evaluation: account={} path={}", sourceAccount.getUsername(), relativeFilePath);
        List<RoutingEvaluator.RoutingDecision> decisions =
                evaluator.evaluate(sourceAccount, relativeFilePath, absoluteSourcePath);

        if (decisions.isEmpty()) {
            log.debug("No routing rules matched for {}", absoluteSourcePath);
            return;
        }

        for (RoutingEvaluator.RoutingDecision decision : decisions) {
            try {
                route(decision);
            } catch (Exception e) {
                log.error("Routing failed for record={}: {}", decision.getRecord().getId(), e.getMessage(), e);
                markFailed(decision.getRecord(), e.getMessage());
            }
        }
    }

    /**
     * Called by each service when a file download completes.
     * Moves the file from outbox → sent and updates the record.
     */
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

            // Move file from /outbox/file to /sent/file
            Files.move(outboxPath, sentPath, StandardCopyOption.REPLACE_EXISTING);

            record.setStatus(FileTransferStatus.MOVED_TO_SENT);
            record.setDownloadedAt(Instant.now());
            record.setCompletedAt(Instant.now());
            record.setDestinationFilePath(sentPath.toString());
            recordRepository.save(record);
            log.info("File lifecycle complete: record={} moved to {}", record.getId(), sentPath);
        } catch (IOException e) {
            log.error("Failed to move file to sent: record={}", record.getId(), e);
            markFailed(record, e.getMessage());
        }
    }

    /**
     * Receives a forwarded file from another service instance / cluster.
     * Writes the file to disk and marks the record as IN_OUTBOX.
     */
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
        log.info("Received forwarded file: record={} dest={}", record.getId(), dest);
    }

    // --- private ---

    private void route(RoutingEvaluator.RoutingDecision decision) throws IOException {
        FileTransferRecord record = decision.getRecord();
        FolderMapping mapping = decision.getMapping();
        ServiceRegistration destService = decision.getDestinationService();

        String destAbsPath = record.getDestinationFilePath();
        String sourceAbsPath = record.getSourceFilePath();

        // Archive source file (inbox → archive)
        Path sourcePath = Paths.get(sourceAbsPath);
        Path archiveDir = sourcePath.getParent().getParent().resolve("archive");
        Files.createDirectories(archiveDir);
        Path archivePath = archiveDir.resolve(sourcePath.getFileName());
        Files.copy(sourcePath, archivePath, StandardCopyOption.REPLACE_EXISTING);
        record.setArchiveFilePath(archivePath.toString());

        // Route to destination
        if (destService == null || isLocalService(destService)) {
            routeLocally(sourceAbsPath, destAbsPath, record);
        } else {
            routeRemotely(sourceAbsPath, record, destService);
        }
    }

    private void routeLocally(String sourceAbsPath, String destAbsPath, FileTransferRecord record) throws IOException {
        Path src = Paths.get(sourceAbsPath);
        Path dst = Paths.get(destAbsPath);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);

        record.setStatus(FileTransferStatus.IN_OUTBOX);
        record.setRoutedAt(Instant.now());
        recordRepository.save(record);
        log.info("Local routing complete: record={} → {}", record.getId(), destAbsPath);
    }

    private void routeRemotely(String sourceAbsPath, FileTransferRecord record,
                                ServiceRegistration destService) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(sourceAbsPath));
        String encoded = Base64.getEncoder().encodeToString(bytes);

        FileForwardRequest req = FileForwardRequest.builder()
                .recordId(record.getId())
                .destinationAbsolutePath(record.getDestinationFilePath())
                .originalFilename(record.getOriginalFilename())
                .fileContentBase64(encoded)
                .build();

        String url = "http://" + destService.getHost() + ":" + destService.getControlPort()
                + "/internal/files/receive";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<FileForwardRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<Void> response = restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            record.setStatus(FileTransferStatus.IN_OUTBOX);
            record.setRoutedAt(Instant.now());
            recordRepository.save(record);
            log.info("Remote routing complete: record={} → {}:{}", record.getId(),
                    destService.getHost(), destService.getControlPort());
        } else {
            throw new RuntimeException("Remote forward returned " + response.getStatusCode());
        }
    }

    private boolean isLocalService(ServiceRegistration service) {
        return service.getServiceInstanceId().equals(clusterContext.getServiceInstanceId());
    }

    @Transactional
    protected void markFailed(FileTransferRecord record, String error) {
        record.setStatus(FileTransferStatus.FAILED);
        record.setErrorMessage(error);
        recordRepository.save(record);
    }


}
