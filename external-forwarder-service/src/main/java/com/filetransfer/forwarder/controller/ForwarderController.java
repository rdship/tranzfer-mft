package com.filetransfer.forwarder.controller;

import com.filetransfer.forwarder.service.FtpForwarderService;
import com.filetransfer.forwarder.service.KafkaForwarderService;
import com.filetransfer.forwarder.service.SftpForwarderService;
import com.filetransfer.shared.entity.ExternalDestination;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.repository.ExternalDestinationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * External Forwarder API
 *
 * POST /api/forward/{destinationId}           — forward a multipart file
 * POST /api/forward/{destinationId}/base64    — forward a Base64-encoded file payload
 */
@Slf4j
@RestController
@RequestMapping("/api/forward")
@RequiredArgsConstructor
public class ForwarderController {

    private final ExternalDestinationRepository destinationRepository;
    private final SftpForwarderService sftpForwarder;
    private final FtpForwarderService ftpForwarder;
    private final KafkaForwarderService kafkaForwarder;

    @PostMapping(value = "/{destinationId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forward(@PathVariable UUID destinationId,
                                        @RequestPart("file") MultipartFile file) throws Exception {
        ExternalDestination dest = findDest(destinationId);
        dispatch(dest, file.getOriginalFilename(), file.getBytes());
        return Map.of("status", "forwarded", "destination", dest.getName(), "file", file.getOriginalFilename());
    }

    @PostMapping("/{destinationId}/base64")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forwardBase64(@PathVariable UUID destinationId,
                                              @RequestParam String filename,
                                              @RequestBody String base64Content) throws Exception {
        ExternalDestination dest = findDest(destinationId);
        byte[] bytes = Base64.getDecoder().decode(base64Content);
        dispatch(dest, filename, bytes);
        return Map.of("status", "forwarded", "destination", dest.getName(), "file", filename);
    }

    private void dispatch(ExternalDestination dest, String filename, byte[] bytes) throws Exception {
        log.info("Forwarding {} ({} bytes) → {} [{}]", filename, bytes.length, dest.getName(), dest.getType());
        if (dest.getType() == ExternalDestinationType.SFTP) {
            sftpForwarder.forward(dest, filename, bytes);
        } else if (dest.getType() == ExternalDestinationType.FTP) {
            ftpForwarder.forward(dest, filename, bytes);
        } else if (dest.getType() == ExternalDestinationType.KAFKA) {
            kafkaForwarder.forward(dest, filename, bytes);
        } else {
            throw new IllegalArgumentException("Unknown destination type: " + dest.getType());
        }
    }

    private ExternalDestination findDest(UUID id) {
        return destinationRepository.findById(id)
                .filter(ExternalDestination::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "External destination not found or inactive: " + id));
    }
}
