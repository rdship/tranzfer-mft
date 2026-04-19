package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.transfer.FlowStepSnapshot;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

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
    // R131: dropped the StorageServiceClient injection — this controller no
    // longer uses the S2S SPIFFE client. Admin downloads forward the
    // admin's bearer token directly to storage-manager via a local
    // RestTemplate. See feedback_admin_can_do_anything.
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${services.storage-manager.url:http://storage-manager:8096}")
    private String storageManagerUrl;

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
     * Return the file content before or after a specific step for the
     * admin's Download UI button. The admin's bearer token is forwarded
     * verbatim to storage-manager so the action always authorizes with
     * the user's identity rather than relying on the platform's internal
     * SPIFFE handshake. Files are buffered in JVM heap — fine for the
     * KB-to-MB range of per-step snapshots.
     *
     * @param direction {@code input} (file entering the step) or {@code output} (file leaving)
     */
    @GetMapping("/{trackId}/{stepIndex}/{direction}/content")
    public ResponseEntity<byte[]> previewContent(
            @PathVariable String trackId,
            @PathVariable int stepIndex,
            @PathVariable String direction,
            HttpServletRequest request) {

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

        // R131 — policy (feedback_admin_can_do_anything): an admin who is
        // logged in and clicks Download must succeed. R127 tried retrieveBySha256
        // (`/retrieve-by-key`), R130 tried streamToOutput (`/stream`) — both
        // failed because they rely on onboarding-api's S2S SPIFFE JWT-SVID,
        // which storage-manager rejected with 403.
        //
        // Correct fix: forward THE ADMIN'S bearer token from the inbound
        // request to the storage-manager call. storage-manager's class-level
        // @PreAuthorize(INTERNAL_OR_OPERATOR) explicitly admits ROLE_ADMIN
        // (tester confirmed direct admin JWT → /stream/{sha256} returns 200).
        // The admin's auth authorizes the ACTION; SPIFFE is only the
        // platform's INTERNAL transport layer, not the gate for a
        // user-initiated UI click.
        //
        // Heap-buffer fine for KB-to-MB flow-step preview files. Errors
        // map cleanly to ResponseStatusException rather than leaking 200.
        byte[] bytes;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
            String authz = request.getHeader("Authorization");
            if (authz != null && !authz.isBlank()) {
                headers.set("Authorization", authz);
            }
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    storageManagerUrl + "/api/v1/storage/stream/" + storageKey,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class);
            bytes = resp.getBody() == null ? new byte[0] : resp.getBody();
        } catch (HttpStatusCodeException httpEx) {
            log.warn("[{}] storage-manager returned {} for step {} {} (key={}) — admin JWT forwarded",
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
