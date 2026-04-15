package com.filetransfer.shared.routing;

import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import com.filetransfer.shared.repository.transfer.FolderMappingRepository;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.ServiceType;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Evaluates folder mappings for a given upload event and produces RoutingDecisions.
 * Lives in shared module so all services (SFTP, FTP, FTP-Web) reuse the same logic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingEvaluator {

    private final FolderMappingRepository mappingRepository;
    private final FileTransferRecordRepository recordRepository;
    private final ClusterService clusterService;

    @Data
    @Builder
    public static class RoutingDecision {
        private FileTransferRecord record;
        private FolderMapping mapping;
        /** Null means no active service instance found for the destination protocol */
        private ServiceRegistration destinationService;
    }

    /**
     * Given an account that just uploaded a file, find all matching folder mappings
     * and create pending FileTransferRecords for each match.
     *
     * @param sourceAccount   account that uploaded the file
     * @param relativeFilePath path relative to user home, e.g. "/inbox/report.csv"
     * @param absoluteSourcePath absolute path on disk
     */
    @Transactional
    public List<RoutingDecision> evaluate(TransferAccount sourceAccount,
                                          String relativeFilePath,
                                          String absoluteSourcePath) {
        String filename = extractFilename(relativeFilePath);
        List<FolderMapping> mappings = mappingRepository.findActiveBySourceAccountId(sourceAccount.getId());
        List<RoutingDecision> decisions = new ArrayList<>();

        for (FolderMapping mapping : mappings) {
            if (!pathUnderMappedDir(relativeFilePath, mapping.getSourcePath())) continue;
            if (!filenameMatches(filename, mapping.getFilenamePattern())) continue;

            TransferAccount dest = mapping.getDestinationAccount();
            String destAbsolutePath = dest.getHomeDir() + mapping.getDestinationPath() + "/" + filename;

            FileTransferRecord record = FileTransferRecord.builder()
                    .folderMapping(mapping)
                    .originalFilename(filename)
                    .sourceFilePath(absoluteSourcePath)
                    .destinationFilePath(destAbsolutePath)
                    .status(FileTransferStatus.PENDING)
                    .uploadedAt(Instant.now())
                    .build();
            recordRepository.save(record);

            ServiceType destServiceType = protocolToServiceType(dest.getProtocol());
            ServiceRegistration destService = clusterService.discoverService(destServiceType)
                    .orElse(null);

            if (destService == null) {
                log.warn("No active {} service found for routing record={} (mode={})",
                        destServiceType, record.getId(), clusterService.getCommunicationMode());
            }

            decisions.add(RoutingDecision.builder()
                    .record(record)
                    .mapping(mapping)
                    .destinationService(destService)
                    .build());
        }
        return decisions;
    }

    /**
     * Look up a FileTransferRecord for a file being downloaded from an outbox.
     * Used to trigger the IN_OUTBOX → DOWNLOADED transition.
     */
    public Optional<FileTransferRecord> findOutboxRecord(TransferAccount destAccount, String absoluteFilePath) {
        return recordRepository.findByDestinationAndStatus(
                destAccount.getId(), absoluteFilePath, FileTransferStatus.IN_OUTBOX);
    }

    // --- helpers ---

    private boolean pathUnderMappedDir(String relativeFilePath, String mappedSourcePath) {
        // relativeFilePath: /inbox/file.csv  mappedSourcePath: /inbox
        String normalizedFile = relativeFilePath.startsWith("/") ? relativeFilePath : "/" + relativeFilePath;
        String normalizedDir = mappedSourcePath.endsWith("/") ? mappedSourcePath : mappedSourcePath + "/";
        return normalizedFile.startsWith(normalizedDir) || normalizedFile.equals(mappedSourcePath);
    }

    private boolean filenameMatches(String filename, String pattern) {
        if (pattern == null || pattern.isBlank()) return true;
        try {
            return Pattern.compile(pattern).matcher(filename).matches();
        } catch (Exception e) {
            log.warn("Invalid filename pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    private String extractFilename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private ServiceType protocolToServiceType(com.filetransfer.shared.enums.Protocol protocol) {
        return switch (protocol) {
            case SFTP -> ServiceType.SFTP;
            case FTP -> ServiceType.FTP;
            case FTP_WEB, HTTPS -> ServiceType.FTP_WEB;
            case AS2, AS4 -> ServiceType.SFTP; // AS2/AS4 files route through SFTP service type
        };
    }
}
