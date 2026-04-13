package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.transfer.TransferTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferTicketRepository extends JpaRepository<TransferTicket, UUID> {
    Optional<TransferTicket> findByTicketIdAndStatus(String ticketId, TransferTicket.TicketStatus status);
    Optional<TransferTicket> findByTicketId(String ticketId);
    List<TransferTicket> findByStatusAndExpiresAtBefore(TransferTicket.TicketStatus status, Instant now);
    List<TransferTicket> findBySenderAccountUsernameOrReceiverAccountUsernameOrderByCreatedAtDesc(
            String sender, String receiver);
}
