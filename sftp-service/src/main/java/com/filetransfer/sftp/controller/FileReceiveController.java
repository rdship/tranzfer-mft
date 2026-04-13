package com.filetransfer.sftp.controller;

import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Internal endpoint — receives files forwarded from other service instances / clusters.
 * Protected by SPIFFE JWT-SVID (ROLE_INTERNAL via PlatformJwtAuthFilter).
 */
@Slf4j
@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class FileReceiveController {

    private final RoutingEngine routingEngine;

    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('INTERNAL')")
    public void receive(@RequestBody FileForwardRequest request) throws IOException {
        log.info("Receiving forwarded file: record={} dest={}", request.getRecordId(), request.getDestinationAbsolutePath());
        routingEngine.receiveForwardedFile(request);
    }

    /** Streaming multipart receive — no Base64, bytes flow directly from HTTP body to disk. */
    @PostMapping(value = "/receive-stream", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('INTERNAL')")
    public void receiveStream(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                               @RequestParam java.util.UUID recordId,
                               @RequestParam String destinationPath,
                               @RequestParam String originalFilename) throws IOException {
        log.info("Receiving streamed file: record={} dest={}", recordId, destinationPath);
        routingEngine.receiveStreamedFile(recordId, destinationPath, originalFilename, file.getInputStream());
    }
}
