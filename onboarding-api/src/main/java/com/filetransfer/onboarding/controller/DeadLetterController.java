package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.transfer.DeadLetterMessage;
import com.filetransfer.shared.repository.transfer.DeadLetterMessageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dead Letter Queue Management API.
 *
 * Allows admins to inspect, retry, or discard messages that failed
 * processing and were dead-lettered by RabbitMQ.
 */
@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Dead Letter Queue", description = "Inspect, retry, or discard failed RabbitMQ messages")
@PreAuthorize("hasRole('ADMIN')")
public class DeadLetterController {

    private final DeadLetterMessageRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @GetMapping("/messages")
    @Operation(summary = "List dead-lettered messages with pagination")
    public Page<DeadLetterMessage> list(
            @RequestParam(required = false) DeadLetterMessage.Status status,
            Pageable pageable) {
        if (status != null) {
            return repository.findByStatus(status, pageable);
        }
        return repository.findAll(pageable);
    }

    @PostMapping("/messages/{id}/retry")
    @Operation(summary = "Re-publish a dead-lettered message to its original exchange")
    public DeadLetterMessage retry(@PathVariable UUID id) {
        DeadLetterMessage msg = findOrThrow(id);
        if (msg.getStatus() != DeadLetterMessage.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Message is " + msg.getStatus() + " — only PENDING messages can be retried");
        }

        rabbitTemplate.convertAndSend(
                msg.getOriginalExchange(), msg.getRoutingKey(), msg.getPayload());

        msg.setStatus(DeadLetterMessage.Status.RETRIED);
        msg.setRetryCount(msg.getRetryCount() + 1);
        msg.setRetriedAt(Instant.now());
        repository.save(msg);

        log.info("Dead-letter message {} retried to exchange={} routingKey={}",
                id, msg.getOriginalExchange(), msg.getRoutingKey());
        return msg;
    }

    @DeleteMapping("/messages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Discard a dead-lettered message")
    public void discard(@PathVariable UUID id) {
        DeadLetterMessage msg = findOrThrow(id);
        msg.setStatus(DeadLetterMessage.Status.DISCARDED);
        repository.save(msg);
        log.info("Dead-letter message {} discarded", id);
    }

    @PostMapping("/retry-all")
    @Operation(summary = "Re-publish all PENDING dead-lettered messages")
    public int retryAll() {
        List<DeadLetterMessage> pending = repository.findByStatus(DeadLetterMessage.Status.PENDING);
        int count = 0;
        for (DeadLetterMessage msg : pending) {
            try {
                rabbitTemplate.convertAndSend(
                        msg.getOriginalExchange(), msg.getRoutingKey(), msg.getPayload());
                msg.setStatus(DeadLetterMessage.Status.RETRIED);
                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setRetriedAt(Instant.now());
                repository.save(msg);
                count++;
            } catch (Exception e) {
                log.error("Failed to retry message {}: {}", msg.getId(), e.getMessage());
            }
        }
        log.info("Retried {} dead-letter messages", count);
        return count;
    }

    private DeadLetterMessage findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dead-letter message not found: " + id));
    }
}
