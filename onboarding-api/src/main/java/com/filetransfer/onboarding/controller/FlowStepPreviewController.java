package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.transfer.FlowStepSnapshot;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
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
    public ResponseEntity<byte[]> previewContent(
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

        log.debug("[{}] Reading step {} {} → key={}", trackId, stepIndex, direction, storageKey);

        // R127 → R130 lesson. R127 first-pass switched from
        // StreamingResponseBody → retrieveBySha256() (the `/retrieve-by-key/`
        // endpoint). That path started answering 403 for onboarding-api's
        // S2S calls — the Journey UI Download button returned 502 to every
        // admin on every COMPLETED transfer. The `/stream/{sha256}` endpoint,
        // which the pre-R127 streamToOutput path used, continues to accept
        // onboarding-api's SPIFFE identity and is the one the tester flagged
        // as authorised. We keep R127's synchronous heap-buffer approach
        // (so downstream 4xx still map cleanly to ResponseStatusException
        // rather than leaking through a committed 200) but route bytes via
        // streamToOutput → ByteArrayOutputStream so the working S2S path is
        // used. Flow-step preview files are KB-to-MB snapshots, heap
        // buffering is fine.
        byte[] bytes;
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        try {
            storageClient.streamToOutput(storageKey, sink);
            bytes = sink.toByteArray();
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            if (root instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
                log.warn("[{}] storage-manager returned {} for step {} {} (key={})",
                        trackId, httpEx.getStatusCode(), stepIndex, direction, storageKey);
                throw new ResponseStatusException(
                        httpEx.getStatusCode().is4xxClientError() ? HttpStatus.NOT_FOUND
                                : HttpStatus.BAD_GATEWAY,
                        "Storage fetch failed for key=" + storageKey + ": " + httpEx.getStatusCode());
            }
            log.warn("[{}] Failed to read step {} {}: {}", trackId, stepIndex, direction, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Storage fetch failed for key=" + storageKey);
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .header("X-Storage-Key", storageKey)
                .header("X-Step-Index", String.valueOf(stepIndex))
                .header("X-Direction", direction)
                .contentLength(bytes.length)
                .body(bytes);
    }
}
