package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.FlowStepSnapshot;
import com.filetransfer.shared.repository.FlowStepSnapshotRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * REST API for pipeline step visibility — before/after file preview per transfer.
 *
 * <p>All endpoints are read-only. Files are never copied or buffered here:
 * the {@code /content} endpoint proxies a true streaming response from storage-manager
 * ({@code GET /api/v1/storage/stream/{sha256}}) directly into the HTTP response body.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/flow-steps/{trackId}} — all step snapshots for a transfer
 *   <li>{@code GET /api/flow-steps/{trackId}/{stepIndex}} — single step detail
 *   <li>{@code GET /api/flow-steps/{trackId}/{stepIndex}/{direction}/content} — stream file bytes
 *       ({@code direction} = {@code input} or {@code output})
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/flow-steps")
@RequiredArgsConstructor
@PreAuthorize(Roles.VIEWER)
public class FlowStepPreviewController {

    private final FlowStepSnapshotRepository snapshotRepo;
    private final StorageServiceClient storageClient;

    /** All step snapshots for a transfer, ordered by step index. */
    @GetMapping("/{trackId}")
    public List<FlowStepSnapshot> listSteps(@PathVariable String trackId) {
        return snapshotRepo.findByTrackIdOrderByStepIndex(trackId);
    }

    /** Single step detail. */
    @GetMapping("/{trackId}/{stepIndex}")
    public FlowStepSnapshot getStep(@PathVariable String trackId, @PathVariable int stepIndex) {
        return snapshotRepo.findByTrackIdAndStepIndex(trackId, stepIndex)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No snapshot for trackId=" + trackId + " step=" + stepIndex));
    }

    /**
     * Stream file content before or after a specific step.
     *
     * <p>Bytes flow: {@code storage-manager disk → HTTP socket}. Zero heap buffering.
     * The response is streamed via {@link StreamingResponseBody} so Tomcat does not
     * wait for the full payload before writing to the client.
     *
     * @param direction {@code input} (file entering the step) or {@code output} (file leaving)
     */
    @GetMapping("/{trackId}/{stepIndex}/{direction}/content")
    public ResponseEntity<StreamingResponseBody> previewContent(
            @PathVariable String trackId,
            @PathVariable int stepIndex,
            @PathVariable String direction) {

        FlowStepSnapshot snap = snapshotRepo.findByTrackIdAndStepIndex(trackId, stepIndex)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No snapshot for trackId=" + trackId + " step=" + stepIndex));

        boolean wantInput = "input".equalsIgnoreCase(direction);
        String storageKey = wantInput ? snap.getInputStorageKey() : snap.getOutputStorageKey();
        if (storageKey == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No " + direction + " file for step " + stepIndex
                            + (snap.getStepStatus() != null && snap.getStepStatus().startsWith("FAIL")
                            ? " (step failed — no output produced)" : ""));
        }

        // Derive a sensible filename from the virtual path
        String virtualPath = wantInput ? snap.getInputVirtualPath() : snap.getOutputVirtualPath();
        String filename = virtualPath != null
                ? virtualPath.substring(virtualPath.lastIndexOf('/') + 1)
                : "file-step" + stepIndex + "-" + direction;

        log.debug("[{}] Streaming step {} {} → key={}", trackId, stepIndex, direction, storageKey);

        StreamingResponseBody body = out -> {
            try {
                storageClient.streamToOutput(storageKey, out);
            } catch (Exception e) {
                log.warn("[{}] Failed to stream step {} {}: {}", trackId, stepIndex, direction, e.getMessage());
                throw new RuntimeException(e);
            }
        };

        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .header("X-Storage-Key", storageKey)
                .header("X-Step-Index", String.valueOf(stepIndex))
                .header("X-Direction", direction)
                .body(body);
    }
}
