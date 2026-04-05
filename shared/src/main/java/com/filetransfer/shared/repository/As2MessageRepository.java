package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.As2Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface As2MessageRepository extends JpaRepository<As2Message, UUID> {

    Optional<As2Message> findByMessageId(String messageId);

    List<As2Message> findByPartnershipIdAndStatus(UUID partnershipId, String status);

    List<As2Message> findByStatus(String status);

    List<As2Message> findByTrackId(String trackId);
}
