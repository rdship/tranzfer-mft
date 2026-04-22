package com.filetransfer.onboarding.messaging;

import com.filetransfer.onboarding.controller.ActivityMonitorController;
import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Broadcasts file-transfer lifecycle changes to connected SSE clients via
 * {@link ActivityMonitorController}.
 *
 * <p><b>R134Y — Sprint 8:</b> migrated off RabbitMQ. Pre-R134Y this class
 * was a {@code @RabbitListener} on the {@code activity-stream} queue bound
 * to {@code file.uploaded + transfer.#}. Per
 * {@code docs/rd/2026-04-R134-external-dep-retirement/02-rabbitmq-retirement.md}
 * the SSE feed is now driven by a 1s poll of the {@code file_transfer_records}
 * table — the row is always written before the old event fired anyway, so
 * the observable UI behaviour is unchanged (modulo ≤1s added latency).
 *
 * <p>Poll cursor {@code lastSeen} starts at service boot so we don't replay
 * historical rows. After each tick it advances to the latest row's
 * {@code updatedAt}. Admin UI SSE traffic is low volume (one browser tab
 * per admin), so the poll overhead is negligible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityStreamConsumer {

    private final ActivityMonitorController activityMonitor;
    private final FileTransferRecordRepository recordRepository;

    /** Initialized to service boot-time so we don't replay historical rows. */
    private final AtomicReference<Instant> lastSeen = new AtomicReference<>(Instant.now());

    @Scheduled(fixedDelayString = "${activity-stream.poll-ms:1000}",
               initialDelayString = "${activity-stream.initial-delay-ms:2000}")
    public void pollAndBroadcast() {
        Instant cursor = lastSeen.get();
        List<FileTransferRecord> recent;
        try {
            recent = recordRepository.findByUpdatedAtAfterOrderByUpdatedAtAsc(cursor);
        } catch (Exception e) {
            log.warn("[R134Y][ActivityStreamConsumer] poll failed — next tick will retry: {}", e.getMessage());
            return;
        }
        if (recent.isEmpty()) return;

        int broadcast = 0;
        for (FileTransferRecord r : recent) {
            try {
                String sseEvent = mapStatusToSseEvent(String.valueOf(r.getStatus()));
                activityMonitor.broadcastActivityEvent(sseEvent, toEventMap(r));
                broadcast++;
            } catch (Exception e) {
                log.debug("[R134Y][ActivityStreamConsumer] broadcast skipped for trackId={}: {}",
                        safeTrackId(r), e.getMessage());
            }
            if (r.getUpdatedAt() != null) {
                lastSeen.set(r.getUpdatedAt());
            }
        }
        log.info("[R134Y][ActivityStreamConsumer] broadcast {} transfer updates since cursor={}",
                broadcast, cursor);
    }

    private static String mapStatusToSseEvent(String status) {
        if (status == null) return "transfer-update";
        return switch (status) {
            case "SUCCESS", "COMPLETED" -> "transfer-completed";
            case "FAILED", "ERROR", "REJECTED" -> "transfer-failed";
            case "PENDING", "IN_PROGRESS", "STARTED", "RECEIVED" -> "transfer-new";
            default -> "transfer-update";
        };
    }

    /**
     * Build the SSE payload. Field set chosen to roughly match what the
     * previous RabbitMQ-driven event carried; UI consumers already
     * tolerate missing fields.
     */
    private static Map<String, Object> toEventMap(FileTransferRecord r) {
        Map<String, Object> m = new HashMap<>();
        m.put("trackId", safeTrackId(r));
        m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        m.put("eventType", r.getStatus() != null ? r.getStatus().name() : "update");
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static String safeTrackId(FileTransferRecord r) {
        try { return r.getTrackId(); } catch (Exception e) { return null; }
    }
}
