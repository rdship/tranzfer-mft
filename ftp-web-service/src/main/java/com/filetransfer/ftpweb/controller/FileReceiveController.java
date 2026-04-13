package com.filetransfer.ftpweb.controller;

import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class FileReceiveController {

    private final RoutingEngine routingEngine;

    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('INTERNAL')")
    public void receive(@RequestBody FileForwardRequest request) throws IOException {
        routingEngine.receiveForwardedFile(request);
    }

    @PostMapping(value = "/receive-stream", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('INTERNAL')")
    public void receiveStream(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                               @RequestParam java.util.UUID recordId,
                               @RequestParam String destinationPath,
                               @RequestParam String originalFilename) throws IOException {
        routingEngine.receiveStreamedFile(recordId, destinationPath, originalFilename, file.getInputStream());
    }
}
