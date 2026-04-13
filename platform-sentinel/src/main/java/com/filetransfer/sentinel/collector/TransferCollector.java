package com.filetransfer.sentinel.collector;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferCollector {

    private final FileTransferRecordRepository transferRepository;

    @Getter
    private volatile List<FileTransferRecord> recentTransfers = List.of();

    public void collect(int windowMinutes) {
        try {
            Instant cutoff = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            recentTransfers = transferRepository.findAll().stream()
                    .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(cutoff))
                    .toList();
            log.debug("TransferCollector: {} transfers in last {} min", recentTransfers.size(), windowMinutes);
        } catch (Exception e) {
            log.warn("TransferCollector failed: {}", e.getMessage());
        }
    }

    public long countFailed() {
        return recentTransfers.stream()
                .filter(r -> r.getStatus() == FileTransferStatus.FAILED)
                .count();
    }

    public long countTotal() {
        return recentTransfers.size();
    }

    public double getFailureRate() {
        long total = countTotal();
        return total > 0 ? (double) countFailed() / total * 100.0 : 0.0;
    }

    public List<FileTransferRecord> getIntegrityMismatches() {
        return recentTransfers.stream()
                .filter(r -> r.getSourceChecksum() != null && r.getDestinationChecksum() != null
                        && !r.getSourceChecksum().equals(r.getDestinationChecksum()))
                .toList();
    }
}
