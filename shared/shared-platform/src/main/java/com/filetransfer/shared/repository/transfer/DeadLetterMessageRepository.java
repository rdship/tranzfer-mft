package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.DeadLetterMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeadLetterMessageRepository extends JpaRepository<DeadLetterMessage, UUID> {

    Page<DeadLetterMessage> findByStatus(DeadLetterMessage.Status status, Pageable pageable);

    Page<DeadLetterMessage> findByOriginalQueue(String originalQueue, Pageable pageable);

    List<DeadLetterMessage> findByStatus(DeadLetterMessage.Status status);
}
