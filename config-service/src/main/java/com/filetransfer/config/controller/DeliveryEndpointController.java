package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.enums.DeliveryProtocol;
import com.filetransfer.shared.repository.DeliveryEndpointRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD API for managing delivery endpoints — external client communication configurations.
 * Supports SFTP, FTP, FTPS, HTTP, HTTPS, API protocols.
 */
@RestController
@RequestMapping("/api/delivery-endpoints")
@RequiredArgsConstructor
public class DeliveryEndpointController {

    private final DeliveryEndpointRepository repository;

    @GetMapping
    public List<DeliveryEndpoint> listAll(@RequestParam(required = false) DeliveryProtocol protocol,
                                           @RequestParam(required = false) String tag) {
        if (protocol != null) {
            return repository.findByProtocolAndActiveTrue(protocol);
        }
        if (tag != null && !tag.isBlank()) {
            return repository.findByTagAndActiveTrue(tag);
        }
        return repository.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public DeliveryEndpoint getById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Delivery endpoint not found: " + id));
    }

    @PostMapping
    public ResponseEntity<DeliveryEndpoint> create(@RequestBody DeliveryEndpoint endpoint) {
        if (repository.existsByName(endpoint.getName())) {
            throw new IllegalArgumentException("Endpoint name already exists: " + endpoint.getName());
        }
        endpoint.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(endpoint));
    }

    @PutMapping("/{id}")
    public DeliveryEndpoint update(@PathVariable UUID id, @RequestBody DeliveryEndpoint endpoint) {
        if (!repository.existsById(id)) {
            throw new EntityNotFoundException("Delivery endpoint not found: " + id);
        }
        endpoint.setId(id);
        return repository.save(endpoint);
    }

    @PatchMapping("/{id}/toggle")
    public DeliveryEndpoint toggle(@PathVariable UUID id) {
        DeliveryEndpoint ep = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Delivery endpoint not found: " + id));
        ep.setActive(!ep.isActive());
        return repository.save(ep);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        DeliveryEndpoint ep = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Delivery endpoint not found: " + id));
        ep.setActive(false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalActive", repository.countActive());

        Map<String, Long> byProtocol = new LinkedHashMap<>();
        for (Object[] row : repository.countActiveByProtocol()) {
            byProtocol.put(row[0].toString(), (Long) row[1]);
        }
        result.put("byProtocol", byProtocol);
        return result;
    }

    @GetMapping("/protocols")
    public DeliveryProtocol[] getProtocols() {
        return DeliveryProtocol.values();
    }
}
