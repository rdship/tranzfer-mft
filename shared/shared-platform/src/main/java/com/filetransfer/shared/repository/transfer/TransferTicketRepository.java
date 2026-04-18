package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.TransferTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * R127: senderAccount and receiverAccount are FetchType.LAZY. With
     * spring.jpa.open-in-view=false (the platform default), Jackson's
     * serialization runs after the transaction closes and trips a
     * LazyInitializationException on those relations — the R124 audit's
     * /api/p2p/tickets 500 was that exact path. Eager-fetch both accounts
     * here so the list endpoint renders without touching the async hot path.
     */
    @Query("SELECT t FROM TransferTicket t " +
           "LEFT JOIN FETCH t.senderAccount " +
           "LEFT JOIN FETCH t.receiverAccount " +
           "ORDER BY t.createdAt DESC")
    List<TransferTicket> findAllWithAccounts();
}
