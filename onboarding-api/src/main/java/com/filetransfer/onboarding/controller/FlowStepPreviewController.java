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

        // R127: was StreamingResponseBody → storageClient.streamToOutput.
        // Problem: when storage-manager returned 4xx (e.g. a 403 from SPIFFE
        // auth race, or 404 for a missing backend object), RestTemplate threw
        // HttpStatusCodeException from inside the async body lambda. Spring
        // had already committed 200+headers, so the error came out as either
        // a 500 generic or a bare 403 body, leaving the Activity Monitor with
        // no usable per-step preview.
        // Fix: read synchronously into heap and return ResponseEntity<byte[]>.
        // Flow-step preview files are per-step snapshots (KB-to-MB range, not
        // GB payloads), so heap buffering is fine, and downstream status codes
        // propagate cleanly via ResponseStatusException.
        byte[] bytes;
        try {
            bytes = storageClient.retrieveBySha256(storageKey);
        } catch (org.springframework.web.client.HttpStatusCodeException httpEx) {
            log.warn("[{}] storage-manager returned {} for step {} {} (key={})",
                    trackId, httpEx.getStatusCode(), stepIndex, direction, storageKey);
            throw new ResponseStatusException(
                    httpEx.getStatusCode().is4xxClientError() ? HttpStatus.NOT_FOUND
                            : HttpStatus.BAD_GATEWAY,
                    "Storage fetch failed for key=" + storageKey + ": " + httpEx.getStatusCode());
        } catch (Exception e) {
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
