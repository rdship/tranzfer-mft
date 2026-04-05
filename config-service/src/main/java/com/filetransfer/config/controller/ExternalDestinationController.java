package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.ExternalDestination;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.repository.ExternalDestinationRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * External Destination API — configure SFTP/FTP/Kafka forwarding targets.
 *
 * GET    /api/external-destinations
 * GET    /api/external-destinations?type=KAFKA
 * GET    /api/external-destinations/{id}
 * POST   /api/external-destinations
 * PUT    /api/external-destinations/{id}
 * DELETE /api/external-destinations/{id}
 */
@RestController
@RequestMapping("/api/external-destinations")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ExternalDestinationController {

    private final ExternalDestinationRepository repository;

    @GetMapping
    public List<ExternalDestination> list(@RequestParam(required = false) ExternalDestinationType type) {
        return type != null ? repository.findByTypeAndActiveTrue(type) : repository.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public ExternalDestination get(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExternalDestination create(@RequestBody ExternalDestination dest) {
        dest.setId(null);
        return repository.save(dest);
    }

    @PutMapping("/{id}")
    public ExternalDestination update(@PathVariable UUID id, @RequestBody ExternalDestination dest) {
        if (!repository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        dest.setId(id);
        return repository.save(dest);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
