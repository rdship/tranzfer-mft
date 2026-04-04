package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.entity.ClientPresence;
import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.entity.TransferTicket;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.ClientPresenceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.TransferTicketRepository;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side coordination for direct peer-to-peer file transfers.
 *
 * Flow:
 * 1. Both clients register presence (POST /api/p2p/presence)
 * 2. Sender requests transfer ticket (POST /api/p2p/tickets)
 * 3. Sender connects directly to receiver using ticket
 * 4. Both report completion (POST /api/p2p/tickets/{id}/complete)
 */
@RestController
@RequestMapping("/api/p2p")
@RequiredArgsConstructor
@Slf4j
public class PeerTransferController {

    private final ClientPresenceRepository presenceRepository;
    private final TransferTicketRepository ticketRepository;
    private final TransferAccountRepository accountRepository;
    private final TrackIdGenerator trackIdGenerator;
    private final AuditService auditService;

    // === Presence ===

    @PostMapping("/presence")
    public ResponseEntity<Map<String, Object>> registerPresence(@RequestBody PresenceRequest req) {
        ClientPresence presence = presenceRepository.findByUsername(req.username)
                .orElse(ClientPresence.builder().username(req.username).build());
        presence.setHost(req.host);
        presence.setPort(req.port);
        presence.setClientVersion(req.clientVersion);
        presence.setLastSeen(Instant.now());
        presence.setOnline(true);
        presenceRepository.save(presence);

        log.info("Client presence: {} at {}:{}", req.username, req.host, req.port);
        return ResponseEntity.ok(Map.of("status", "registered", "username", req.username));
    }

    @GetMapping("/presence")
    public List<Map<String, Object>> getOnlineClients() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.MINUTES);
        return presenceRepository.findByOnlineTrueAndLastSeenAfter(cutoff).stream()
                .map(p -> Map.<String, Object>of(
                        "username", p.getUsername(),
                        "host", p.getHost(),
                        "port", p.getPort(),
                        "lastSeen", p.getLastSeen().toString(),
                        "clientVersion", p.getClientVersion() != null ? p.getClientVersion() : ""))
                .collect(Collectors.toList());
    }

    // === Transfer Tickets ===

    @PostMapping("/tickets")
    public ResponseEntity<Map<String, Object>> createTicket(@RequestBody TicketRequest req) {
        // Validate sender
        TransferAccount sender = accountRepository.findByUsernameAndProtocolAndActiveTrue(req.senderUsername, Protocol.SFTP)
                .or(() -> accountRepository.findByUsernameAndProtocolAndActiveTrue(req.senderUsername, Protocol.FTP))
                .orElseThrow(() -> new IllegalArgumentException("Sender account not found: " + req.senderUsername));

        // Validate receiver
        TransferAccount receiver = accountRepository.findByUsernameAndProtocolAndActiveTrue(req.receiverUsername, Protocol.SFTP)
                .or(() -> accountRepository.findByUsernameAndProtocolAndActiveTrue(req.receiverUsername, Protocol.FTP))
                .orElseThrow(() -> new IllegalArgumentException("Receiver account not found: " + req.receiverUsername));

        // Check receiver is online
        ClientPresence receiverPresence = presenceRepository.findByUsername(req.receiverUsername)
                .orElseThrow(() -> new IllegalArgumentException("Receiver is not online: " + req.receiverUsername));

        if (receiverPresence.getLastSeen().isBefore(Instant.now().minus(2, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Receiver appears offline (last seen > 2 min ago)");
        }

        String trackId = trackIdGenerator.generate();
        String senderToken = UUID.randomUUID().toString().replace("-", "");

        TransferTicket ticket = TransferTicket.builder()
                .ticketId(UUID.randomUUID().toString())
                .trackId(trackId)
                .senderAccount(sender)
                .receiverAccount(receiver)
                .filename(req.filename)
                .fileSizeBytes(req.fileSizeBytes)
                .sha256Checksum(req.sha256Checksum)
                .receiverHost(receiverPresence.getHost())
                .receiverPort(receiverPresence.getPort())
                .senderToken(senderToken)
                .expiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                .build();

        ticketRepository.save(ticket);

        log.info("[{}] P2P ticket created: {} -> {} (file: {})",
                trackId, req.senderUsername, req.receiverUsername, req.filename);

        auditService.logFileRoute(trackId, "p2p:" + req.senderUsername,
                "p2p:" + req.receiverUsername, null);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "ticketId", ticket.getTicketId(),
                "trackId", trackId,
                "senderToken", senderToken,
                "receiverHost", receiverPresence.getHost(),
                "receiverPort", receiverPresence.getPort(),
                "expiresAt", ticket.getExpiresAt().toString()
        ));
    }

    @PostMapping("/tickets/{ticketId}/validate")
    public ResponseEntity<Map<String, Object>> validateTicket(
            @PathVariable String ticketId,
            @RequestBody Map<String, String> body) {
        String token = body.get("senderToken");

        TransferTicket ticket = ticketRepository.findByTicketIdAndStatus(ticketId, TransferTicket.TicketStatus.PENDING)
                .orElse(null);

        if (ticket == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("valid", false, "error", "Ticket not found or not pending"));
        }
        if (Instant.now().isAfter(ticket.getExpiresAt())) {
            ticket.setStatus(TransferTicket.TicketStatus.EXPIRED);
            ticketRepository.save(ticket);
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("valid", false, "error", "Ticket expired"));
        }
        if (!ticket.getSenderToken().equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("valid", false, "error", "Invalid sender token"));
        }

        ticket.setStatus(TransferTicket.TicketStatus.IN_PROGRESS);
        ticketRepository.save(ticket);

        return ResponseEntity.ok(Map.of(
                "valid", true,
                "trackId", ticket.getTrackId(),
                "filename", ticket.getFilename(),
                "sha256Checksum", ticket.getSha256Checksum() != null ? ticket.getSha256Checksum() : "",
                "senderUsername", ticket.getSenderAccount().getUsername(),
                "receiverUsername", ticket.getReceiverAccount().getUsername()
        ));
    }

    @PostMapping("/tickets/{ticketId}/complete")
    public ResponseEntity<Map<String, String>> completeTicket(
            @PathVariable String ticketId,
            @RequestBody Map<String, String> body) {
        TransferTicket ticket = ticketRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        String receiverChecksum = body.get("sha256Checksum");
        boolean success = Boolean.parseBoolean(body.getOrDefault("success", "true"));

        if (success) {
            // Verify checksum matches
            if (ticket.getSha256Checksum() != null && receiverChecksum != null
                    && !ticket.getSha256Checksum().equals(receiverChecksum)) {
                log.error("[{}] P2P INTEGRITY MISMATCH: sender={} receiver={}",
                        ticket.getTrackId(), ticket.getSha256Checksum(), receiverChecksum);
                ticket.setStatus(TransferTicket.TicketStatus.FAILED);
                ticket.setErrorMessage("Checksum mismatch: sender=" + ticket.getSha256Checksum()
                        + " receiver=" + receiverChecksum);
                auditService.logFailure(null, ticket.getTrackId(), "P2P_INTEGRITY_FAIL",
                        ticket.getFilename(), ticket.getErrorMessage());
            } else {
                ticket.setStatus(TransferTicket.TicketStatus.COMPLETED);
                ticket.setCompletedAt(Instant.now());
                log.info("[{}] P2P transfer completed: {} -> {}",
                        ticket.getTrackId(), ticket.getSenderAccount().getUsername(),
                        ticket.getReceiverAccount().getUsername());
            }
        } else {
            ticket.setStatus(TransferTicket.TicketStatus.FAILED);
            ticket.setErrorMessage(body.get("error"));
        }

        ticketRepository.save(ticket);
        return ResponseEntity.ok(Map.of("status", ticket.getStatus().name(), "trackId", ticket.getTrackId()));
    }

    @GetMapping("/tickets")
    public List<TransferTicket> getTickets(
            @RequestParam(required = false) String username) {
        if (username != null) {
            return ticketRepository.findBySenderAccountUsernameOrReceiverAccountUsernameOrderByCreatedAtDesc(
                    username, username);
        }
        return ticketRepository.findAll();
    }

    // --- DTOs ---
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PresenceRequest {
        String username;
        String host;
        int port;
        String clientVersion;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TicketRequest {
        String senderUsername;
        String receiverUsername;
        String filename;
        Long fileSizeBytes;
        String sha256Checksum;
    }
}
