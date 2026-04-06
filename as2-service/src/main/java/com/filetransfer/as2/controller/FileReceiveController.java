package com.filetransfer.as2.controller;

import com.filetransfer.shared.dto.FileForwardRequest;
import com.filetransfer.shared.routing.RoutingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Internal endpoint — receives files forwarded from other service instances / clusters.
 * Protected by X-Internal-Key header. Same pattern as sftp-service/ftp-service.
 */
@Slf4j
@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class FileReceiveController {

    private final RoutingEngine routingEngine;

    @Value("${control-api.key}")
    private String controlApiKey;

    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestHeader("X-Internal-Key") String key,
                        @RequestBody FileForwardRequest request) throws IOException {
        if (!MessageDigest.isEqual(controlApiKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
        log.info("Receiving forwarded file: record={} dest={}", request.getRecordId(), request.getDestinationAbsolutePath());
        routingEngine.receiveForwardedFile(request);
    }
}
