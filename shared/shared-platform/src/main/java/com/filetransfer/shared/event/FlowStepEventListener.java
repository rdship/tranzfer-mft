package com.filetransfer.shared.event;

import com.filetransfer.shared.entity.FlowStepSnapshot;
import com.filetransfer.shared.repository.FlowStepSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asynchronous consumer of {@link FlowStepEvent}.
 *
 * <p>The {@code @Async} annotation ensures this runs on the shared task-executor
 * (configured in {@code SharedConfig}) — never on the virtual-thread agent that fired
 * the event. The hot-path agent calls {@code publishEvent()} and returns immediately.
 *
 * <p>Failure here is non-critical: a warn log is emitted and the transfer continues
 * unaffected. Snapshots are best-effort observability, not transactional guarantees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowStepEventListener {

    private final FlowStepSnapshotRepository snapshotRepo;

    /** Phase 5.3: batch writer for snapshots — reduces DB round-trips by 25x. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.filetransfer.shared.cache.StepSnapshotBatchWriter batchWriter;

    @Async
    @EventListener
    public void onFlowStep(FlowStepEvent event) {
        try {
            FlowStepSnapshot snap = FlowStepSnapshot.builder()
                    .trackId(event.trackId())
                    .flowExecutionId(event.flowExecutionId())
                    .stepIndex(event.stepIndex())
                    .stepType(event.stepType())
                    .stepStatus(event.stepStatus())
                    .inputStorageKey(event.inputStorageKey())
                    .outputStorageKey(event.outputStorageKey())
                    .inputVirtualPath(event.inputVirtualPath())
                    .outputVirtualPath(event.outputVirtualPath())
                    .inputSizeBytes(event.inputSizeBytes())
                    .outputSizeBytes(event.outputSizeBytes())
                    .durationMs(event.durationMs())
                    .errorMessage(event.errorMessage())
                    .build();
            // Phase 5.3: use batch writer when available (200ms flush cycle)
            if (batchWriter != null) {
                batchWriter.submit(snap);
            } else {
                snapshotRepo.save(snap);
            }
            log.debug("[{}] Step snapshot queued: step={} type={} status={}",
                    event.trackId(), event.stepIndex(), event.stepType(), event.stepStatus());
        } catch (DataIntegrityViolationException dup) {
            log.debug("[{}] Step snapshot already exists for step={} (idempotent skip)",
                    event.trackId(), event.stepIndex());
        } catch (Exception e) {
            log.warn("[{}] Failed to save step snapshot step={} type={} (non-critical): {}",
                    event.trackId(), event.stepIndex(), event.stepType(), e.getMessage());
        }
    }
}
