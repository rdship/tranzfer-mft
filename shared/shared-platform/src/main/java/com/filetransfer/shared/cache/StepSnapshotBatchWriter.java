package com.filetransfer.shared.cache;

import com.filetransfer.shared.entity.FlowStepSnapshot;
import com.filetransfer.shared.repository.FlowStepSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 5.3: Async batch writer for FlowStepSnapshot — same pattern as TransferRecordBatchWriter.
 *
 * <p>At 1M files/day with avg 3 steps/flow = 3M snapshots/day = 35 INSERTs/sec.
 * Batch writer reduces DB round-trips by 25x (Hibernate batch_size=25).
 * Buffer: 10,000 entries, flush every 200ms or 100 records.
 *
 * <p>Auto-activates in any service that has FlowStepSnapshotRepository.
 */
@Slf4j
@Component
@ConditionalOnBean(FlowStepSnapshotRepository.class)
public class StepSnapshotBatchWriter {

    private static final int BUFFER_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 100;

    private final BlockingQueue<FlowStepSnapshot> buffer =
            new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    private final FlowStepSnapshotRepository repository;
    private final AtomicLong totalFlushed = new AtomicLong();

    public StepSnapshotBatchWriter(FlowStepSnapshotRepository repository) {
        this.repository = repository;
    }

    /** Non-blocking submit. Returns true if queued, false if sync fallback used. */
    public boolean submit(FlowStepSnapshot snapshot) {
        if (buffer.offer(snapshot)) return true;
        // Buffer full — save synchronously (backpressure)
        try { repository.save(snapshot); } catch (Exception ignored) {}
        return false;
    }

    @Scheduled(fixedDelay = 200)
    public void flush() {
        List<FlowStepSnapshot> batch = new ArrayList<>(BATCH_SIZE);
        buffer.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) return;

        try {
            repository.saveAll(batch);
            totalFlushed.addAndGet(batch.size());
        } catch (Exception e) {
            log.debug("Snapshot batch flush failed ({} records) — retrying individually: {}",
                    batch.size(), e.getMessage());
            for (FlowStepSnapshot snap : batch) {
                try { repository.save(snap); } catch (Exception ignored) {}
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("StepSnapshotBatchWriter shutting down — flushing {} pending snapshots", buffer.size());
        List<FlowStepSnapshot> remaining = new ArrayList<>();
        buffer.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try { repository.saveAll(remaining); }
            catch (Exception e) {
                for (FlowStepSnapshot s : remaining) {
                    try { repository.save(s); } catch (Exception ignored) {}
                }
            }
        }
    }

    public int pendingCount() { return buffer.size(); }
    public long totalFlushed() { return totalFlushed.get(); }
}
