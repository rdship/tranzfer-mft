package com.filetransfer.shared.cache;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async batch writer for FileTransferRecord — eliminates synchronous DB INSERT from the hot path.
 *
 * <p>Records are buffered in a bounded queue and flushed every 100ms or 50 records
 * (whichever comes first). If the JVM crashes before flush, the file is still in
 * RabbitMQ (not ACK'd until flow completes) — the record will be re-created on redelivery.
 *
 * <p>If the buffer is full (5000 entries), falls back to synchronous write as backpressure signal.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pipeline.records.batch-write", havingValue = "true", matchIfMissing = false)
public class TransferRecordBatchWriter {

    private static final int BUFFER_CAPACITY = 5000;
    private static final int BATCH_SIZE = 50;

    private final BlockingQueue<FileTransferRecord> buffer =
            new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    private final FileTransferRecordRepository repository;
    private final AtomicLong totalFlushed = new AtomicLong();
    private final AtomicLong totalSyncFallback = new AtomicLong();

    public TransferRecordBatchWriter(FileTransferRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Non-blocking submit. Returns immediately.
     * Record is durable within 100ms (flush interval).
     *
     * @return true if queued, false if sync fallback was used (buffer full)
     */
    public boolean submit(FileTransferRecord record) {
        if (buffer.offer(record)) {
            return true;
        }
        // Backpressure: buffer full — write synchronously
        log.warn("Transfer record batch buffer full ({}) — sync fallback", BUFFER_CAPACITY);
        repository.save(record);
        totalSyncFallback.incrementAndGet();
        return false;
    }

    /** Flush pending records every 100ms. Uses Hibernate batch_size=25. */
    @Scheduled(fixedDelay = 100)
    public void flush() {
        List<FileTransferRecord> batch = new ArrayList<>(BATCH_SIZE);
        buffer.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return;

        try {
            repository.saveAll(batch);
            totalFlushed.addAndGet(batch.size());
            if (batch.size() >= BATCH_SIZE) {
                log.debug("Transfer record batch flushed: {} records (total: {})",
                        batch.size(), totalFlushed.get());
            }
        } catch (Exception e) {
            log.error("Batch flush failed ({} records) — retrying individually: {}",
                    batch.size(), e.getMessage());
            // Fallback: save individually (some may have constraint violations)
            for (FileTransferRecord record : batch) {
                try { repository.save(record); } catch (Exception ex) {
                    log.error("Individual record save failed (trackId={}): {}",
                            record.getTrackId(), ex.getMessage());
                }
            }
        }
    }

    /** Flush remaining records on shutdown — prevents data loss during rolling deployments. */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("TransferRecordBatchWriter shutting down — flushing {} pending records", buffer.size());
        List<FileTransferRecord> remaining = new ArrayList<>();
        buffer.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try {
                repository.saveAll(remaining);
                log.info("TransferRecordBatchWriter shutdown flush: {} records persisted", remaining.size());
            } catch (Exception e) {
                log.error("TransferRecordBatchWriter shutdown flush failed ({} records): {}",
                        remaining.size(), e.getMessage());
                // Last resort: save individually
                for (FileTransferRecord r : remaining) {
                    try { repository.save(r); } catch (Exception ex) {
                        log.error("Lost record on shutdown (trackId={}): {}", r.getTrackId(), ex.getMessage());
                    }
                }
            }
        }
    }

    /** Pending records in buffer. */
    public int pendingCount() { return buffer.size(); }

    /** Total records flushed via batch path. */
    public long totalFlushed() { return totalFlushed.get(); }

    /** Total records that hit sync fallback (buffer full). */
    public long totalSyncFallback() { return totalSyncFallback.get(); }
}
