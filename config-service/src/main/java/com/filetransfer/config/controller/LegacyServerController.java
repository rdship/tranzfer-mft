package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.LegacyServerConfig;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.LegacyServerConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Legacy Server API — configure fallback FTP/SFTP servers for unknown users.
 *
 * GET    /api/legacy-servers             list all
 * GET    /api/legacy-servers?protocol=SFTP
 * POST   /api/legacy-servers            create
 * PUT    /api/legacy-servers/{id}        update
 * DELETE /api/legacy-servers/{id}        delete
 */
@RestController
@RequestMapping("/api/legacy-servers")
@RequiredArgsConstructor
public class LegacyServerController {

    private final LegacyServerConfigRepository repository;

    @GetMapping
    public List<LegacyServerConfig> list(@RequestParam(required = false) Protocol protocol) {
        return protocol != null
                ? repository.findByProtocolAndActiveTrue(protocol)
                : repository.findAll();
    }

    @GetMapping("/{id}")
    public LegacyServerConfig get(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LegacyServerConfig create(@RequestBody LegacyServerConfig config) {
        config.setId(null);
        return repository.save(config);
    }

    @PutMapping("/{id}")
    public LegacyServerConfig update(@PathVariable UUID id, @RequestBody LegacyServerConfig config) {
        if (!repository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        config.setId(id);
        return repository.save(config);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
